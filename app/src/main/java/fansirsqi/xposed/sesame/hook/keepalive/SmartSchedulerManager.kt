package fansirsqi.xposed.sesame.hook.keepalive

import android.annotation.SuppressLint
import android.content.Context
import fansirsqi.xposed.sesame.hook.CoroutineScheduler
import fansirsqi.xposed.sesame.util.Log

/**
 * 调度器管理器
 *
 * 功能：
 * 1. 封装并提供统一的调度器访问接口。
 * 2. 底层使用 AlarmManager 实现精确、可靠的任务调度。
 *
 */
object SmartSchedulerManager {

    private const val TAG = "SchedulerManager"

    // 调度器实例
    @SuppressLint("StaticFieldLeak")
    private var scheduler: CoroutineScheduler? = null

    // 初始化标志（避免重复初始化）
    @Volatile
    private var initialized = false

    /**
     * 初始化调度器
     */
    @Synchronized
    fun initialize(context: Context?) {
        if (initialized) {
            Log.debug(TAG, "调度器已经初始化，跳过重复初始化")
            return
        }

        if (context == null) {
            Log.error(TAG, "❌ 初始化失败: context 为 null")
            return
        }

        try {
            val appContext = context.applicationContext ?: context
            Log.debug(TAG, "🔧 正在初始化调度器...")

            // 创建调度器
            scheduler = CoroutineScheduler(appContext)
            initialized = true
            
            Log.record(TAG, "✅ 调度器管理器已初始化 (基于 AlarmManager)")
        } catch (e: Exception) {
            Log.error(TAG, "❌ 初始化失败: ${e.message}")
            Log.printStackTrace(TAG, e)
            scheduler = null
        }
    }

    /**
     * 调度精确执行
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        scheduler?.scheduleExactExecution(delayMillis, exactTimeMillis)
            ?: Log.error(TAG, "调度器未初始化")

        val h = delayMillis / 3600000
        val m = (delayMillis % 3600000) / 60000
        val s = (delayMillis % 60000) / 1000
        val delayStr = buildString {
            if (h > 0) append("${h}时")
            if (m > 0 || h > 0) append("${m}分")
            append("${s}秒")
        }

        Log.record(TAG, "📅 已调度 (闹钟调度器) | 延迟: $delayStr")
    }

    /**
     * 调度延迟执行
     */
    fun scheduleDelayedExecution(delayMillis: Long) {
        scheduler?.scheduleDelayedExecution(delayMillis)
    }

    /**
     * 调度唤醒任务
     */
    fun scheduleWakeupAlarm(triggerAtMillis: Long, requestCode: Int, isMainAlarm: Boolean): Boolean {
        return scheduler?.scheduleWakeupAlarm(triggerAtMillis, requestCode, isMainAlarm) ?: false
    }

    /**
     * 取消所有唤醒任务
     */
    fun cancelAllWakeupAlarms() {
        scheduler?.cancelAllWakeupAlarms()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        scheduler?.cleanup()
        Log.record(TAG, "调度器管理器已清理")
    }
}