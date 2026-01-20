package fansirsqi.xposed.sesame.task

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.model.CustomSettings
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.customTasks.ManualTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 协程任务执行器 (优化版)
 *
 * 核心改进:
 * 1. **并发执行**: 支持任务并发运行，缩短总耗时。
 * 2. **生命周期**: 绑定到调用者的生命周期，防止泄漏。
 * 3. **逻辑简化**: 移除复杂的宽限期嵌套，使用标准的协程超时机制。
 */
class CoroutineTaskRunner(allModels: List<Model>) {

    companion object {
        private const val TAG = "CoroutineTaskRunner"
        private const val DEFAULT_TASK_TIMEOUT = 10 * 60 * 1000L // 10分钟

        // 最大并发数，防止请求过于频繁触发风控
        // 可以做成配置项，目前硬编码为 3
        private const val MAX_CONCURRENCY = 2

        private val TIMEOUT_WHITELIST = setOf("森林", "庄园", "运动")
    }

    private val taskList: List<ModelTask> = allModels.filterIsInstance<ModelTask>()

    // 统计数据
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val taskExecutionTimes = ConcurrentHashMap<String, Long>()

    /**
     * 启动任务执行流程
     * 注意：现在这是一个 suspend 函数，需要在一个协程作用域内调用
     */
    suspend fun run(
        isFirst: Boolean = true,
        rounds: Int = BaseModel.taskExecutionRounds.value
    ) = coroutineScope { // 使用 coroutineScope 创建子作用域
        val startTime = System.currentTimeMillis()

        // 【互斥检查】如果手动任务流正在运行，则跳过本次自动执行
        if (ManualTask.isManualRunning) {
            Log.record(TAG, "⏸ 检测到“手动庄园任务流”正在运行中，跳过本次自动任务调度")
            return@coroutineScope
        }

        if (isFirst) {
            ApplicationHook.updateDay()
            val showName = UserMap.get(UserMap.currentUid)?.showName
            if (!showName.isNullOrEmpty()) {
                Log.removeAnimalStatus(showName)
            }
            resetCounters()
            // 【关键修复】开始新一轮任务流时，必须重置全局停止信号
            // 否则如果之前调用过 stopAllTask()，新任务会检测到信号而立即退出
            ModelTask.isGlobalStopRequested = false
            Log.record(TAG, "♻️ 已重置全局停止信号，开始新任务流")
        }

        try {
            Log.record(TAG, "🚀 开始执行任务流程 (并发数: $MAX_CONCURRENCY)")

            val status = CustomSettings.getOnceDailyStatus(true)

            // 执行多轮任务
            repeat(rounds) { roundIndex ->
                val round = roundIndex + 1
                executeRound(round, rounds, status)
            }

            if (CustomSettings.onlyOnceDaily.value) {
                // 确保时间状态是最新的
                TaskCommon.update()
                if (TaskCommon.IS_MODULE_SLEEP_TIME) {
                    Log.record(TAG, "💤 当前处于模块休眠时间，不设置 OnceDaily::Finished 标记")
                } else {
                    Status.setFlagToday("OnceDaily::Finished")
                }
            }

        } catch (e: CancellationException) {
            Log.record(TAG, "🚫 任务流程被取消")
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "任务流程异常", e)
        } finally {
            // 只有在没有请求全局停止的情况下才调度下一次
            if (!ModelTask.isGlobalStopRequested) {
                scheduleNext()
            } else {
                Log.record(TAG, "🛑 任务已停止，跳过下次调度")
            }
            printExecutionSummary(startTime, System.currentTimeMillis())
        }
    }

    /**
     * 执行一轮任务 (并发模式)
     */
    private suspend fun executeRound(round: Int, totalRounds: Int, status: CustomSettings.Companion.OnceDailyStatus) = coroutineScope {
        val roundStartTime = System.currentTimeMillis()

        // 1. 筛选任务
        val tasksToRun = taskList.filter { task ->
            task.isEnable && !CustomSettings.isOnceDailyBlackListed(task.getName(), status)
        }

        val excludedCount = taskList.count { it.isEnable } - tasksToRun.size
        if (excludedCount > 0) skippedCount.addAndGet(excludedCount)

        Log.record(TAG, "🔄 [第 $round/$totalRounds 轮] 开始，共 ${tasksToRun.size} 个任务")

        if (status.isEnabledOverride && status.isFinishedToday) {
            val customBlacklistedNames = taskList.filter {
                it.isEnable && CustomSettings.isOnceDailyBlackListed(
                    it.getName(),
                    status
                )
            }
                .map { it.getName() ?: "未知" }
            if (customBlacklistedNames.isNotEmpty()) {
                Log.record(
                    "🚫 [每日单次运行] 模式已生效，将跳过以下已开启且完成过一次的项目: ${
                        customBlacklistedNames.joinToString(
                            ", "
                        )
                    }"
                )
            }
        }

        // 检查全局停止信号
        if (ModelTask.isGlobalStopRequested) {
            Log.record(TAG, "🛑 检测到全局停止信号，终止第${round}轮任务执行")
            return@coroutineScope
        }

        // 2. 并发执行
        // 使用 Semaphore 限制并发数量
        val semaphore = Semaphore(MAX_CONCURRENCY)

        // 创建所有任务的 Deferred 对象
        val deferreds = tasksToRun.map { task ->
            async {
                if (ModelTask.isGlobalStopRequested) return@async
                // 【互斥检查】再次检查手动任务，防止并发启动
                if (ManualTask.isManualRunning) {
                     Log.record(TAG, "⏸ 任务 ${task.getName()} 因手动模式启动而中止")
                     return@async
                }
                semaphore.withPermit {
                    executeSingleTask(task, round)
                }
            }
        }

        // 3. 等待本轮所有任务完成
        deferreds.awaitAll()

        val roundTime = System.currentTimeMillis() - roundStartTime
        Log.record(TAG, "✅ [第 $round/$totalRounds 轮] 结束，耗时: ${TimeUtil.formatDuration(roundTime)}ms")
    }

    /**
     * 执行单个任务
     */
    private suspend fun executeSingleTask(task: ModelTask, round: Int) {
        if (ModelTask.isGlobalStopRequested) return

        val taskName = task.getName() ?: "未知任务"
        val taskId = "$taskName-R$round"
        val startTime = System.currentTimeMillis()

        val isWhitelist = TIMEOUT_WHITELIST.contains(taskName)

        // 如果是白名单任务（如森林），它们往往是“启动后即视为完成”，或者是长运行任务
        // 我们可以给一个较短的“启动超时时间”，而不是等待整个任务结束
        val timeout = if (isWhitelist) 30_000L else DEFAULT_TASK_TIMEOUT

        try {
            Log.record(TAG, "▶️ 启动: $taskId")
            task.addRunCents()

            withTimeout(timeout) {
                // startTask 是一个 suspend 函数，或者返回一个 Job
                val job = task.startTask(force = false, rounds = 1)

                // 如果是白名单任务，我们只等待它启动成功（job active），不 join
                if (isWhitelist) {
                    if (job.isActive) {
                        Log.record(TAG, "✨ $taskId 启动成功 (后台运行中)")
                        return@withTimeout
                    }
                }

                // 普通任务等待完成
                job.join()
            }

            // 任务结束后立即检查全局停止信号
            if (ModelTask.isGlobalStopRequested) {
                throw CancellationException("Manual stop detected after job completion")
            }

            // 成功
            val time = System.currentTimeMillis() - startTime
            successCount.incrementAndGet()
            taskExecutionTimes[taskId] = time
            Log.record(TAG, "✅ 完成: $taskId (耗时: ${time}ms)")

        } catch (e: TimeoutCancellationException) {
            val time = System.currentTimeMillis() - startTime

            if (isWhitelist) {
                // 白名单任务超时通常意味着它还在后台跑，视作成功
                successCount.incrementAndGet()
                taskExecutionTimes[taskId] = time
                Log.record(TAG, "✅ $taskId 已运行 ${time}ms (后台继续)")
            } else {
                // 普通任务超时 -> 失败
                failureCount.incrementAndGet()
                Log.error(TAG, "⏰ 超时: $taskId (${time}ms > ${timeout}ms)")
                // 尝试停止任务
                task.stopTask()
            }

        } catch (e: Exception) {
            val time = System.currentTimeMillis() - startTime
            failureCount.incrementAndGet()
            Log.error(TAG, "❌ 失败: $taskId (${e.message})")
        }
    }

    private fun scheduleNext() {
        try {
            ApplicationHook.scheduleNextExecutionInternal(ApplicationHook.lastExecTime)
            Log.record(TAG, "📅 已调度下次执行")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "调度失败", e)
        }
    }

    private fun resetCounters() {
        successCount.set(0)
        failureCount.set(0)
        skippedCount.set(0)
        taskExecutionTimes.clear()
    }

    @SuppressLint("DefaultLocale")
    private fun printExecutionSummary(startTime: Long, endTime: Long) {
        val totalTime = endTime - startTime
        val avgTime = if (taskExecutionTimes.isNotEmpty()) taskExecutionTimes.values.average() else 0.0

        val separator1 = ">".repeat(15)
        val separator2 = "<".repeat(17)

        Log.record(TAG, "\n📈 $separator1 执行统计 (并发模式) $separator2")
        Log.record(TAG, "⏱️ 总耗时: ${TimeUtil.formatDuration(totalTime)}ms")
        Log.record(TAG, "✅ 成功: ${successCount.get()} | ❌ 失败: ${failureCount.get()} | ⏭️ 跳过: ${skippedCount.get()}")
        if (taskExecutionTimes.isNotEmpty()) {
            Log.record(TAG, "⚡ 平均耗时: ${TimeUtil.formatDuration(avgTime.toLong())}")
        }

        val nextTime = ApplicationHook.nextExecutionTime
        if (nextTime > 0) {
            Log.record(TAG, "📅 下次: ${TimeUtil.getCommonDate(nextTime)}")
            val showName = UserMap.get(UserMap.currentUid)?.showName ?: "未知用户"
            Log.animalStatus("$showName📅 下次运行: ${TimeUtil.getCommonDate(nextTime)}", 5)
        }
        listScheduledTask()
        Log.record(TAG, "=".repeat(50))
    }

    fun listScheduledTask(){
        // 获取等待任务的总数
        val count = ModelTask.ChildModelTask.getWaitingCount()
        if (count == 0) return
        Log.record("定时执行任务总数：${count}个")

        // 打印所有正在等待的任务详情
        val tasks = ModelTask.ChildModelTask.getWaitingTasks()
        tasks.forEach { task ->
            val displayName = when {
                task.group == "FA" || task.id.startsWith("FA|") -> "庄园蹲点喂小鸡"
                task.group == "AW" || task.id.startsWith("AW|") -> "小鸡定时起床"
                task.group == "AS" || task.id.startsWith("AS|") -> "小鸡定时睡觉"
                task.group == "KC" || task.id.startsWith("KC|") -> "小鸡蹲点驱赶偷吃"
                else -> task.id
            }
            Log.record("正在等待的任务: 名称=${displayName}, 计划执行时间=${TimeUtil.getCommonDate(task.execTime)}")
        }
    }
}