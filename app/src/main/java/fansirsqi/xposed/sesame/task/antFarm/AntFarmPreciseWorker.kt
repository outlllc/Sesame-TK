package fansirsqi.xposed.sesame.task.antFarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.WakeLockManager
import kotlinx.coroutines.*

/**
 * 蚂蚁庄园精准子任务工作器 (独立文件)
 * 专门负责精准唤醒 KC (赶鸡) 和 FA (喂鸡) 子任务，而不运行整个庄园
 */
object AntFarmPreciseWorker {
    const val ACTION_PREC_KC_FA = "fansirsqi.xposed.sesame.ANTFARM_PREC_KC_FA"
    private const val REQ_KC_FA_BASE = 40000
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("AntFarmPreciseWorker"))

    /**
     * 设置一个精准唤醒闹钟
     * @param type "KC" 或 "FA"
     */
    fun schedule(context: Context, triggerAtMillis: Long, type: String, ownerFarmId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        
        // 1. 构造 Intent，携带精准定位参数
        val intent = Intent(ACTION_PREC_KC_FA).apply {
            setPackage(General.PACKAGE_NAME)
            putExtra("child_type", type)
            putExtra("child_id", "$type|$ownerFarmId")
        }

        // 2. 构造唯一 RequestCode
        val requestCode = REQ_KC_FA_BASE + type.hashCode() + ownerFarmId.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        // 3. 设置系统闹钟
        try {
            // 在 Android 12+，设置精准闹钟可能需要权限检查，这里通过 try-catch 处理 SecurityException
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.record("AntFarmPrec", "⏰ 已设置[${type}]精准闹钟 | 触发: ${TimeUtil.getCommonDate(triggerAtMillis)}")
        } catch (e: SecurityException) {
            Log.error("AntFarmPrec", "❌ 设置精准闹钟失败: 缺少 SCHEDULE_EXACT_ALARM 权限")
        } catch (e: Exception) {
            Log.printStackTrace("AntFarmPrec", e)
        }
    }

    /**
     * 取消特定的闹钟
     */
    fun cancel(context: Context, type: String, ownerFarmId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val requestCode = REQ_KC_FA_BASE + type.hashCode() + ownerFarmId.hashCode()
        val intent = Intent(ACTION_PREC_KC_FA).apply {
            setPackage(General.PACKAGE_NAME)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        alarmManager.cancel(pendingIntent)
        Log.record("AntFarmPrec", "🚫 已取消[${type}]精准闹钟 | farmId: $ownerFarmId")
    }

    /**
     * 取消该庄园的所有精准闹钟
     */
    fun cancelAll(context: Context, ownerFarmId: String) {
        cancel(context, "KC", ownerFarmId)
        cancel(context, "FA", ownerFarmId)
    }

    /**
     * 响应广播：精准定位并运行子任务
     */
    fun receive(context: Context, intent: Intent) {
        val childId = intent.getStringExtra("child_id") ?: return
        Log.record("AntFarmPrec", "🔔 闹钟唤醒成功，目标子任务: $childId")

        // 持锁保活，防止执行过程中断
        WakeLockManager.acquire(context, 120_000L)

        workerScope.launch {
            try {
                // 1. 获取庄园实例
                val antFarm = Model.getModel(AntFarm::class.java) as? ModelTask
                if (antFarm == null) {
                    Log.error("AntFarmPrec", "❌ 未找到庄园模块实例")
                    return@launch
                }

                // 2. 通过反射从 ModelTask 的私有 Map 中提取 ChildModelTask
                // 这样就不需要修改 ModelTask.kt 了
                val childTask = getChildTaskViaReflection(antFarm, childId)
                
                if (childTask != null) {
                    Log.record("AntFarmPrec", "🎯 正在精准执行子任务逻辑: $childId")
                    childTask.run()
                } else {
                    Log.error("AntFarmPrec", "❌ 子任务 $childId 不存在或已被清理")
                }
            } catch (e: Exception) {
                Log.printStackTrace("AntFarmPrec run err", e)
            }
        }
    }

    private fun getChildTaskViaReflection(parent: ModelTask, childId: String): ModelTask.ChildModelTask? {
        return try {
            val field = ModelTask::class.java.getDeclaredField("childTaskMap")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(parent) as? Map<String, ModelTask.ChildModelTask>
            map?.get(childId)
        } catch (ignore: Exception) {
            null
        }
    }
}
