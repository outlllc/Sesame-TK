package fansirsqi.xposed.sesame.task

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.model.CustomSettings
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.newutil.TaskBlacklist
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * 基于协程的任务执行器类
 * 
 * 该类替代原有的TaskRunner，提供基于Kotlin协程的任务执行能力。
 * 仅支持顺序执行模式，确保任务执行的稳定性和可靠性。
 * 
 * 主要特性:
 * 1. **协程驱动**: 使用Kotlin协程进行任务调度和执行
 * 2. **结构化并发**: 通过协程作用域管理任务生命周期
 * 3. **顺序执行**: 按顺序一个接一个执行任务，避免并发冲突
 * 4. **多轮执行**: 支持配置任务执行轮数
 * 5. **统计监控**: 提供详细的执行统计和状态监控
 * 6. **错误处理**: 完善的异常处理和恢复机制
 * 7. **自动恢复**: 任务超时自动恢复机制
 */
class CoroutineTaskRunner(allModels: List<Model>) {
    companion object {
        private const val TAG = "CoroutineTaskRunner"
        
        /**
         * 任务超时时间配置（毫秒）
         * 优化后的固定超时时间，足够各类任务完成
         * - 森林：主任务完成后，蹲点在后台独立运行，不占用主流程
         * - 庄园：主任务完成后，定时任务在后台独立运行
         * - 运动：任务执行时间较长，超时后继续在后台运行
         * - 其他：一般任务都能在此时间内完成
         */
        private const val DEFAULT_TASK_TIMEOUT = 10 * 60 * 1000L // 10分钟统一超时
        
        /**
         * 超时白名单：这些任务超时后不会被停止或恢复
         * 主要用于有后台长期运行任务的模块（如森林蹲点、庄园定时任务）
         */
        private val TIMEOUT_WHITELIST = setOf(
            "森林",      // 森林有蹲点功能，需要长期运行
            "庄园",      // 庄园有定时任务，需要长期运行
            "运动"       // 运动任务执行时间较长
        )
        
        // 恢复任务的超时设置（毫秒）- 只用于日志提示，不会取消恢复任务
        private const val RECOVERY_TIMEOUT = 30_000L // 增加到30秒
        
        // 恢复前的延迟时间（毫秒）
        private const val RECOVERY_DELAY = 3_000L // 增加到3秒，给任务更多清理时间
        
        // 最大恢复尝试次数
        private const val MAX_RECOVERY_ATTEMPTS = 3 // 增加到3次
        
        // 恢复任务的最大运行时间（毫秒）- 超过此时间后任务会被自动标记为完成
        private const val MAX_RECOVERY_RUNTIME = 10 * 60 * 1000L // 10分钟
    }

    private val taskList: List<ModelTask> = allModels.filterIsInstance<ModelTask>()
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    
    // 记录任务恢复尝试次数
    private val recoveryAttempts = ConcurrentHashMap<String, Int>()
    
    // 性能监控指标
    private val taskExecutionTimes = ConcurrentHashMap<String, Long>()
    private val coroutineCreationCount = AtomicInteger(0)
    private val logRecordCount = AtomicInteger(0)
    
    // 执行器协程作用域
    private val runnerScope = CoroutineScope(
        Dispatchers.Default + 
        SupervisorJob() + 
        CoroutineName("CoroutineTaskRunner")
    )

    init {
        Log.record(TAG, "初始化协程任务执行器，共发现 ${taskList.size} 个任务")
    }

    /**
     * 启动任务执行流程（协程版本）
     * 
     * @param isFirst 是否为首次执行（用于重置统计计数器）
     * @param rounds 执行轮数，默认从BaseModel配置读取
     */
    fun run(
        isFirst: Boolean = true,
        rounds: Int = BaseModel.taskExecutionRounds.value
    ) {
        runnerScope.launch {
            // 【关键】启动前重置全局停止标志位
            ModelTask.isGlobalStopRequested = false

            if (isFirst) {
                resetCounters()
            }
            
            val startTime = System.currentTimeMillis()

            var isOnceDailyEnabledOverride = CustomSettings.onlyOnceDaily.value == true
            val isOnceDailyFinished = Status.hasFlagToday("OnceDaily::Finished")
            val isInsideTimeRangeDay = TimeUtil.checkNowInTimeRange("0600-0710")
            val isInsideTimeRangeNight = TimeUtil.checkNowInTimeRange("2000-2100")
            if (isInsideTimeRangeDay || isInsideTimeRangeNight){
                isOnceDailyEnabledOverride = false
            }
            if(isOnceDailyEnabledOverride && isOnceDailyFinished){
                Log.other("已启用当日单次运行模式，今日已经完成任务，本次将跳过部分项目")
            }

            try {
                executeTasksWithMode(rounds, isOnceDailyEnabledOverride, isOnceDailyFinished)
                // 设置今日已完成标志，标记一次完整的任务执行流程已结束
                if (CustomSettings.onlyOnceDaily.value) {
                    Status.setFlagToday("OnceDaily::Finished")
                }
            } catch (e: CancellationException) {
                Log.record(TAG, "⏹️ 任务执行流程已被手动停止")
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "run err", e)
            } finally {
                val endTime = System.currentTimeMillis()
                printExecutionSummary(startTime, endTime)
                // 清空恢复尝试计数
                recoveryAttempts.clear()

                // 调度下次执行
                try {
                    ApplicationHook.scheduleNextExecution()
                    Log.record(TAG, "✅ 已调度下次执行")
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "run err: ${e.message}",e)
                }
            }
        }
    }

    /**
     * 执行任务（仅支持顺序执行）
     */
    private suspend fun executeTasksWithMode(
        rounds: Int, onceMode: Boolean, finished: Boolean
    ) {
        // 无论传入什么模式，都使用顺序执行
        executeSequentialTasks(rounds, onceMode, finished)
    }

    /**
     * 顺序执行所有任务
     */
    private suspend fun executeSequentialTasks(rounds: Int, isOnceDailyEnabled: Boolean, isOnceDailyFinished: Boolean) {
        val configuredRounds = BaseModel.taskExecutionRounds.value
        Log.record(TAG, "⚙️ 任务执行配置：传入${rounds}轮，BaseModel配置${configuredRounds}轮（用户可在基础设置中调整）")
        // 当发现自定义黑名单启用时，打印Log.other()
        if (isOnceDailyFinished && isOnceDailyEnabled) {
            val customBlacklistedNames = taskList.filter { it.isEnable && isCustomBlacklisted(it.getName()) }
                .map { it.getName() ?: "未知" }
            if (customBlacklistedNames.isNotEmpty()) {
                Log.other("🚫 [每日单次运行] 模式已生效，将跳过以下已完成项目: ${customBlacklistedNames.joinToString(", ")}")
            }
        }

        for (round in 1..rounds) {
            // 【核心修正】检查全局停止标志，实现硬断路
            if (ModelTask.isGlobalStopRequested) break
            coroutineContext.ensureActive()
            
            val roundStartTime = System.currentTimeMillis()
            
            // 过滤启用且不在黑名单/自定义排除名单的任务
            val enabledTasksInRound = taskList.filter { task ->
                val name = task.getName()
                val isBasicEligible = task.isEnable && !TaskBlacklist.isTaskInBlacklist(name)
                
                if (isOnceDailyFinished && isOnceDailyEnabled) {
                    // hasflagtoday返回true时执行黑名单逻辑
                    isBasicEligible && !isCustomBlacklisted(name)
                } else {
                    // hasflagtoday返回false时执行原有逻辑
                    isBasicEligible
                }
            }
            
            // 统计被排除的任务（用于准确汇总）
            val allEnabledTasksCount = taskList.count { it.isEnable }
            val excludedCount = allEnabledTasksCount - enabledTasksInRound.size
            if (excludedCount > 0) {
                skippedCount.addAndGet(excludedCount)
            }
            
            Log.record(TAG, "🔄 开始顺序执行第${round}/${rounds}轮任务，共${enabledTasksInRound.size}个有效任务")
            
            for ((index, task) in enabledTasksInRound.withIndex()) {
                // 【核心修正】每次迭代开始前硬性检查全局停止标志
                if (ModelTask.isGlobalStopRequested) {
                    Log.record(TAG, "🛑 检测到全局停止信号，终止第${round}轮剩余任务启动")
                    break
                }
                coroutineContext.ensureActive()
                
                val taskName = task.getName()
                Log.record(TAG, "📍 第${round}轮任务进度: ${index + 1}/${enabledTasksInRound.size} - ${taskName}")
                executeTaskWithTimeout(task, round)
                // 任务执行完成（白名单任务可能在后台继续运行），继续下一个
                if (index < enabledTasksInRound.size - 1) {
                    Log.record(TAG, "➡️ 任务[$taskName]处理完成，准备执行下一任务...")
                }
            }
            
            val roundTime = System.currentTimeMillis() - roundStartTime
            Log.record(TAG, "✅ 第${round}/${rounds}轮任务完成，耗时: ${TimeUtil.formatDuration(roundTime)}")
        }
    }

    /**
     * 执行单个任务（带智能超时控制和自动恢复机制）
     */
    private suspend fun executeTaskWithTimeout(task: ModelTask, round: Int) {
        val taskId = "${task.getName()}-Round$round"
        val taskStartTime = System.currentTimeMillis()
        
        // 所有任务统一使用10分钟超时
        // 森林和庄园的蹲点/定时任务会在后台独立协程中运行，不影响主流程
        val effectiveTimeout = DEFAULT_TASK_TIMEOUT
        
        Log.record(TAG, "🚀 开始执行任务[$taskId]，超时设置: ${effectiveTimeout/1000}秒")
        try {
            // 使用智能超时机制
            val taskName = task.getName() ?: ""
            val isWhitelistTask = TIMEOUT_WHITELIST.contains(taskName)
            executeTaskWithGracefulTimeout(task, round, taskStartTime, taskId, effectiveTimeout)
            val executionTime = System.currentTimeMillis() - taskStartTime
            
            // 根据任务类型输出不同的完成日志
            if (isWhitelistTask && executionTime >= effectiveTimeout) {
                // 白名单任务超时但继续在后台运行
                successCount.incrementAndGet()
                Log.record(TAG, "✅ 任务[$taskId]主流程完成（耗时: ${executionTime}ms），后台任务继续运行")
            } else {
                // 正常完成
                Log.record(TAG, "✅ 任务[$taskId]执行完成，耗时: ${executionTime}ms")
            }
        } catch (e: CancellationException) {
            // 【关键修改】如果全局停止信号已开启，必须重新抛出以打断外层 for 循环
            if (ModelTask.isGlobalStopRequested || e !is TimeoutCancellationException) {
                throw e
            }
            
            // 超时异常：说明任务在宽限期后仍未完成（白名单任务已在内层处理）
            val executionTime = System.currentTimeMillis() - taskStartTime
            val timeoutMsg = "${executionTime}ms > ${effectiveTimeout}ms"
            // 执行自动恢复逻辑
            failureCount.incrementAndGet()
            Log.error(TAG, "⏰ 任务[$taskId]执行超时($timeoutMsg)，准备自动恢复")
            // 记录任务状态信息
            logTaskStatusInfo(task, taskId)
            // 获取当前恢复尝试次数
            val attempts = recoveryAttempts.getOrPut(taskId) { 0 }
            // 检查是否超过最大尝试次数
            if (attempts >= MAX_RECOVERY_ATTEMPTS) {
                Log.error(TAG, "任务[$taskId]已达到最大恢复尝试次数($MAX_RECOVERY_ATTEMPTS)，放弃恢复")
                return
            }
            
            // 增加恢复尝试计数
            recoveryAttempts[taskId] = attempts + 1
            
            // 取消当前任务的所有协程
            task.stopTask()
            Log.record(TAG, "❌ 任务[$taskId]执行超时，准备自动恢复")
            
            // 短暂延迟后重新启动任务
            delay(RECOVERY_DELAY) // 等待3秒钟
            
            // 【关键修正】延迟后检查停止信号
            if (ModelTask.isGlobalStopRequested) throw CancellationException("Manual stop during recovery delay")

            try {
                Log.record(TAG, "正在自动恢复任务[$taskId]，第${attempts + 1}次尝试")
                // 强制重启任务
                val recoveryJob = task.startTask(
                    force = true,
                    rounds = 1
                )
                
                // 使用非阻塞方式等待任务完成
                try {
                    // 创建监控协程，负责监控恢复任务的状态
                    val monitorJob = runnerScope.launch {
                        try {
                            // 监控超时提示（不取消任务）
                            delay(RECOVERY_TIMEOUT)
                            if (recoveryJob.isActive) {
                                Log.record(TAG, "任务[$taskId]恢复执行已超过${RECOVERY_TIMEOUT/1000}秒，继续在后台运行")
                            }
                            
                            // 监控最大运行时间
                            delay(MAX_RECOVERY_RUNTIME - RECOVERY_TIMEOUT)
                            if (recoveryJob.isActive) {
                                Log.record(TAG, "任务[$taskId]恢复执行已超过最大运行时间(${MAX_RECOVERY_RUNTIME/1000/60}分钟)，标记为已完成")
                                // 取消恢复任务，避免无限运行
                                recoveryJob.cancel()
                                // 标记为成功，避免重复恢复
                                successCount.incrementAndGet()
                            }
                        } catch (_: CancellationException) {
                            // 监控协程被取消是正常的
                        }
                    }
                    
                    // 等待恢复任务完成或超时任务触发
                    recoveryJob.invokeOnCompletion { cause ->
                        // 恢复任务完成后取消监控协程
                        monitorJob.cancel()
                        
                        when (cause) {
                            null -> {
                                // 任务正常完成
                                successCount.incrementAndGet()
                                Log.record(TAG, "任务[$taskId]自动恢复成功")
                            }

                            is CancellationException -> {
                                // 任务可能被……取消是由于超时或手动取消）
                                Log.record(TAG, "任务[$taskId]恢复过程被取消")
                            }

                            else -> {
                                // 任务因错误而结束
                                Log.printStackTrace(TAG, "任务[$taskId]恢复过程中出错: ${cause.message}",cause)
                            }
                        }
                    }
                    
                    // 不阻塞当前协程，让恢复任务在后台继续执行
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "监控恢复任务时出错: ${e.message}",e)
                }
            } catch (e2: Exception) {
                Log.printStackTrace(TAG, "任务[$taskId]自动恢复失败: ${e2.message}",e2)
            }
        }
    }

    /**
     * 智能超时执行机制
     * 当接近超时时给任务额外的时间来完成，避免强制中断
     * 支持用户配置的动态超时时间
     */
    private suspend fun executeTaskWithGracefulTimeout(
        task: ModelTask, 
        round: Int, 
        taskStartTime: Long, 
        taskId: String,
        taskTimeout: Long
    ) {
        // 如果配置为无限等待，直接执行任务
        if (taskTimeout == -1L) {
            Log.record(TAG, "🔄 任务[$taskId]配置为无限等待，直接执行...")
            executeTask(task, round)
            return
        }
        
        try {
            withTimeout(taskTimeout) {
                executeTask(task, round)
            }
        } catch (e: TimeoutCancellationException) {
            // 超时后先检查是否在白名单中
            val currentTime = System.currentTimeMillis()
            val runningTime = currentTime - taskStartTime
            val taskName = task.getName() ?: ""
            
            // 白名单任务：超时后直接跳过，不进入宽限期
            if (TIMEOUT_WHITELIST.contains(taskName)) {
                Log.record(TAG, "⏰ 任务[$taskId]达到超时(${runningTime}ms)，在白名单中，跳过宽限期")
                Log.record(TAG, "📌 ${taskName}模块后台任务继续运行，主流程将继续执行下一任务")
                // 不抛出异常，让外层正常处理（会标记为成功并继续下一任务）
                return
            }
            
            // 非白名单任务：检查是否还在运行，决定是否给宽限期
            Log.record(TAG, "⚠️ 任务[$taskId]达到基础超时(${runningTime}ms)，检查是否可以继续等待...")
            if (task.isRunning) {
                // 给任务额外30秒的宽限期
                val gracePeriod = 30_000L
                Log.record(TAG, "🕐 任务[$taskId]仍在运行，给予${gracePeriod/1000}秒宽限期...")
                try {
                    withTimeout(gracePeriod) {
                        // 等待任务自然完成
                        var lastLogTime = System.currentTimeMillis()
                        while (task.isRunning) {
                            // 【关键修正】宽限期循环中检查停止信号
                            if (ModelTask.isGlobalStopRequested) throw CancellationException("Manual stop during graceful period")
                            delay(5000) // 增加到5秒，减少检查频率
                            val currentRunningTime = System.currentTimeMillis() - taskStartTime
                            val now = System.currentTimeMillis()
                            // 优化：使用时间差判断，避免模运算
                            if (now - lastLogTime >= 10000) { // 每10秒输出一次
                                Log.record(TAG, "⏳ 任务[$taskId]宽限期运行中... ${currentRunningTime/1000}秒")
                                lastLogTime = now
                            }
                        }
                        Log.record(TAG, "✅ 任务[$taskId]在宽限期内完成")
                    }
                } catch (e: TimeoutCancellationException) {
                    // 宽限期也超时了，重新抛出原始超时异常
                    Log.error(TAG, "❌ 任务[$taskId]宽限期(${gracePeriod/1000}秒)也超时，强制超时处理")
                    throw e
                }
            } else {
                // 任务已经不在运行了，重新抛出超时异常
                Log.record(TAG, "🔍 任务[$taskId]已停止运行，执行超时处理")
                throw e
            }
        }
    }

    /**
     * 执行单个任务
     */
    private suspend fun executeTask(task: ModelTask, round: Int) {
        val taskName = task.getName()
        val taskStartTime = System.currentTimeMillis()
        
        try {
            task.addRunCents()

            Log.record(TAG, "🎯 启动模块[${taskName}]第${round}轮执行...")
            logRecordCount.incrementAndGet() //性能监控：记录日志调用次数
            
            // 启动任务（使用新的协程接口）
            coroutineCreationCount.incrementAndGet() //性能监控：协程创建计数
            val job = task.startTask(
                force = false,
                rounds = 1
            )

            // 监控任务执行状态
            val monitorJob = runnerScope.launch {
                var lastLogTime = System.currentTimeMillis()
                try {
                    while (job.isActive) {
                        delay(10000) // 每10秒检查一次
                        val currentTime = System.currentTimeMillis()
                        val runningTime = currentTime - taskStartTime
                        if (currentTime - lastLogTime >= 10000) { // 每10秒输出一次状态
                            Log.record(TAG, "🔄 模块[${taskName}]第${round}轮运行中... 已执行${runningTime/1000}秒")
                            lastLogTime = currentTime
                        }
                    }
                } catch (_: CancellationException) {
                    // 监控协程被取消是正常的
                }
            }
            
            // 等待任务完成
            try {
                job.join()
            } finally {
                // 确保监控协程被取消
                monitorJob.cancel()
            }
            
            // 【关键修正】任务结束后立即检查全局停止信号
            if (ModelTask.isGlobalStopRequested) {
                throw CancellationException("Manual stop detected after job completion")
            }

            val executionTime = System.currentTimeMillis() - taskStartTime
            successCount.incrementAndGet()
            
            // 性能监控：记录任务执行时间
            val taskId = "${taskName}-Round${round}"
            taskExecutionTimes[taskId] = executionTime
            
            Log.record(TAG, "✅ 模块[${taskName}]第${round}轮执行成功，耗时: ${executionTime}ms")
            logRecordCount.incrementAndGet()
            
        } catch (e: CancellationException) {
            // 任务取消是正常的协程控制流程
            val executionTime = System.currentTimeMillis() - taskStartTime
            skippedCount.incrementAndGet()
            Log.record(TAG, "⏹️ 模块[${taskName}]第${round}轮被取消，耗时: ${executionTime}ms")
            throw e
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - taskStartTime
            failureCount.incrementAndGet()
            Log.printStackTrace(TAG, "❌ 执行任务[${taskName}]第${round}轮时发生错误(耗时: ${executionTime}ms): ${e.message}",e)
        }
    }

    /**
     * 记录任务状态信息
     */
    private fun logTaskStatusInfo(task: ModelTask, taskId: String) {
        try {
            val isEnabled = task.isEnable
            val isRunning = task.isRunning
            val taskName = task.getName()

            Log.record(TAG, "📊 任务[$taskId]状态信息:")
            Log.record(TAG, "  - 任务名称: $taskName")
            Log.record(TAG, "  - 是否启用: $isEnabled")
            Log.record(TAG, "  - 是否运行中: $isRunning")

            // 尝试获取更多状态信息
            try {
                val runCents = task.runCents
                val taskScope = if (task.isRunning) "运行中" else "已停止"
                Log.record(TAG, "  - 运行次数: $runCents")
                Log.record(TAG, "  - 任务状态: $taskScope")
            } catch (e: Exception) {
                Log.record(TAG, "  - 任务状态: 获取失败(${e.message})")
            }
            
        } catch (e: Exception) {
            Log.error(TAG, "记录任务状态信息失败: ${e.message}")
        }
    }

    /**
     * 重置计数器
     */
    private fun resetCounters() {
        successCount.set(0)
        failureCount.set(0)
        skippedCount.set(0)
        recoveryAttempts.clear()
        
        // 重置性能监控指标
        taskExecutionTimes.clear()
        coroutineCreationCount.set(0)
        logRecordCount.set(0)
    }

    /**
     * 打印执行摘要
     */
    @SuppressLint("DefaultLocale")
    private fun printExecutionSummary(startTime: Long, endTime: Long) {
        val totalTime = endTime - startTime
        val totalTasks = taskList.size
        val enabledTasks = taskList.count { it.isEnable }

        Log.record(TAG, "📈 ===== 协程任务执行统计摘要 =====")
        Log.record(TAG, "🕐 执行时间: ${TimeUtil.formatDuration(totalTime)}")
        val nextTime = ApplicationHook.nextExecutionTime
        if (nextTime > 0) {
            val maskName = UserMap.getCurrentMaskName() ?: "未知用户"
            Log.record(TAG, "📅 下次执行: ${TimeUtil.getCommonDate(nextTime)}")
            Log.animalStatus("[$maskName]📅下次执行:${TimeUtil.getCommonDate(nextTime)}",5)
        }
        Log.record(TAG, "📋 任务总数: $totalTasks (启用: $enabledTasks)")
        Log.record(TAG, "✅ 成功任务: ${successCount.get()}")
        Log.record(TAG, "❌ 失败任务: ${failureCount.get()}")
        Log.record(TAG, "⏭️ 跳过任务: ${skippedCount.get()}")
        Log.record(TAG, "🔄 恢复尝试: ${recoveryAttempts.size}")
        
        if (recoveryAttempts.isNotEmpty()) {
            Log.record(TAG, "🔧 恢复详情:")
            recoveryAttempts.forEach { (taskId, attempts) ->
                Log.record(TAG, "  - $taskId: $attempts 次尝试")
            }
        }
        
        // 计算成功率
        val totalExecuted = successCount.get() + failureCount.get()
        if (totalExecuted > 0) {
            val successRate = (successCount.get() * 100.0) / totalExecuted
            Log.record(TAG, "📊 成功率: ${String.format("%.1f", successRate)}%")
        }
        
        // 性能监控指标
        Log.record(TAG, "⚡ 性能指标:")
        Log.record(TAG, "  - 协程创建次数: ${coroutineCreationCount.get()}")
        Log.record(TAG, "  - 日志记录次数: ${logRecordCount.get()}")
        
        // 任务执行时间分析
        if (taskExecutionTimes.isNotEmpty()) {
            val avgTime = taskExecutionTimes.values.average()
            val maxTime = taskExecutionTimes.values.maxOrNull() ?: 0L
            val minTime = taskExecutionTimes.values.minOrNull() ?: 0L
            Log.record(TAG, "  - 任务平均耗时: ${String.format("%.1f", avgTime)}ms")
            Log.record(TAG, "  - 最长任务耗时: ${maxTime}ms")
            Log.record(TAG, "  - 最短任务耗时: ${minTime}ms")
            
            // 找出最慢的3个任务
            val slowestTasks = taskExecutionTimes.entries
                .sortedByDescending { it.value }
                .take(3)
            if (slowestTasks.isNotEmpty()) {
                Log.record(TAG, "  - 最慢的任务:")
                slowestTasks.forEach { (taskId, time) ->
                    Log.record(TAG, "    * $taskId: ${time}ms")
                }
            }
        }
        
        // 性能分析
        if (totalTime > 60000) { // 超过1分钟
            Log.record(TAG, "⚠️ 执行时间较长，建议检查任务配置或网络状况")
        }

        listScheduledTask()
        
        Log.record(TAG, "================================")
    }

    /**
     * 停止任务执行器
     */
    fun stop() {
        runnerScope.cancel()
        Log.record(TAG, "协程任务执行器已停止")
    }

    fun listScheduledTask(){
        // 获取等待任务的总数
        val count = ModelTask.ChildModelTask.getWaitingCount()
        if (count == 0) return
        Log.other("定时执行任务总数：${count}个")

        // 打印所有正在等待的任务详情
        val tasks = ModelTask.ChildModelTask.getWaitingTasks()
        tasks.forEach { task ->
            // 匹配 FA| 开头的 ID 并转换
            val displayName = if (task.id.startsWith("FA|")) {
                "庄园蹲点喂小鸡"
            } else if (task.id.startsWith("AW|")) {
                "小鸡定时起床"
            }else if (task.id.startsWith("AS|")) {
                "小鸡定时睡觉"
            }else if (task.id.startsWith("KC|")) {
                "小鸡蹲点驱赶偷吃"
            }
            else {
                task.id
            }
            Log.other("正在等待的任务: 名称=${displayName}, 计划执行时间=${TimeUtil.getCommonDate(task.execTime)}")
        }
    }

    /**
     * 检查任务是否被用户自定义设置排除（黑名单）
     */
    private fun isCustomBlacklisted(name: String?): Boolean {
        if (name == null) return false
        return when (name) {
            "蚂蚁森林" -> CustomSettings.antForest.value
            "蚂蚁庄园" -> CustomSettings.antFarm.value
            "海洋" -> CustomSettings.antOcean.value
            "农场" -> CustomSettings.antOrchard.value
            "新村" -> CustomSettings.antStall.value
            "神奇物种" -> CustomSettings.antDodo.value
            "蚂蚁森林合种" -> CustomSettings.antCooperate.value
            "运动" -> CustomSettings.antSports.value
            "会员" -> CustomSettings.antMember.value
            "生态保护" -> CustomSettings.EcoProtection.value
            "绿色经营" -> CustomSettings.greenFinance.value
            "保护地" -> CustomSettings.reserve.value
            else -> false
        }
    }
}
