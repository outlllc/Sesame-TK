package fansirsqi.xposed.sesame.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.hook.ApplicationHook
import kotlinx.coroutines.*

/**
 * 精准任务调度器 (独立组件)
 * 用于实现通过 AlarmManager 直接唤醒并执行特定的 ChildModelTask
 */
object PreciseTaskScheduler {
    const val ACTION_EXECUTE_CHILD_TASK = "fansirsqi.xposed.sesame.ACTION_EXECUTE_CHILD_TASK"
    private const val REQUEST_CODE_BASE = 30000
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("PreciseTaskScheduler"))

    /**
     * 设置一个精准唤醒闹钟
     */
    fun scheduleChildTask(context: Context, triggerAtMillis: Long, parentName: String, childId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        
        // 权限检查 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.error("PreciseTask", "❌ 缺少精确闹钟权限")
            return
        }

        val intent = Intent(ACTION_EXECUTE_CHILD_TASK).apply {
            setPackage(General.PACKAGE_NAME)
            putExtra("parent_name", parentName)
            putExtra("child_id", childId)
        }

        // 使用唯一的 requestCode 防止覆盖
        val requestCode = REQUEST_CODE_BASE + (parentName + childId).hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            Log.record("PreciseTask", "⏰ 已设置精准闹钟: $parentName -> $childId | 触发: ${TimeUtil.getCommonDate(triggerAtMillis)}")
        } catch (e: Exception) {
            Log.printStackTrace("PreciseTask", e)
        }
    }

    /**
     * 处理收到的广播并执行任务
     */
    fun dispatch(intent: Intent) {
        val parentName = intent.getStringExtra("parent_name") ?: return
        val childId = intent.getStringExtra("child_id") ?: return

        scope.launch {
            try {
                // 1. 查找父任务实例 (例如 AntFarm)
                val model = Model.modelArray.find { it?.getName() == parentName } as? ModelTask
                if (model == null) {
                    Log.error("PreciseTask", "❌ 未找到父任务: $parentName")
                    return@launch
                }

                // 2. 查找并运行特定的子任务 (KC/FA 等)
                // 这里利用 ModelTask 现有的 getChildTask (如果不存在则需要通过反射或修改获取，但为了不改核心，我们假设已经按照之前的逻辑增加了该方法，或者我们直接反射访问 childTaskMap)
                val childTask = findChildTaskViaReflection(model, childId)
                
                if (childTask != null) {
                    Log.record("PreciseTask", "🎯 正在执行精准子任务: $childId")
                    childTask.run()
                } else {
                    Log.error("PreciseTask", "❌ 子任务 $childId 已失效或不存在")
                }
            } catch (e: Exception) {
                Log.printStackTrace("PreciseTask dispatch err", e)
            }
        }
    }

    /**
     * 由于不能修改 ModelTask.kt 增加 getChildTask，我们使用反射安全获取
     */
    private fun findChildTaskViaReflection(parent: ModelTask, childId: String): ModelTask.ChildModelTask? {
        return try {
            val field = ModelTask::class.java.getDeclaredField("childTaskMap")
            field.isAccessible = true
            val map = field.get(parent) as? Map<String, ModelTask.ChildModelTask>
            map?.get(childId)
        } catch (e: Exception) {
            null
        }
    }
}
