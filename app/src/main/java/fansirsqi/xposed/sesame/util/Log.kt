package fansirsqi.xposed.sesame.util

import android.content.Context
import android.util.Log
import fansirsqi.xposed.sesame.model.BaseModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.text.DateFormat;


/**
 * 日志工具类，负责初始化和管理各种类型的日志记录器，并提供日志输出方法。
 */
object Log {
    private const val DEFAULT_TAG = ""
    private const val MAX_DUPLICATE_ERRORS = 3 // 最多打印3次相同错误

    // 错误去重机制
    private val errorCountMap = ConcurrentHashMap<String, AtomicInteger>()

    // Logger 实例
    private val RECORD_LOGGER: Logger
    private val DEBUG_LOGGER: Logger
    private val FOREST_LOGGER: Logger
    private val FARM_LOGGER: Logger
    private val OTHER_LOGGER: Logger
    private val ERROR_LOGGER: Logger
    private val CAPTURE_LOGGER: Logger

    init {
        // 🔥 1. 立即初始化 Logcat，确保在任何 Context 到来之前控制台可用
        Logback.initLogcatOnly()

        // 2. 初始化 Logger 实例 (此时它们已经有了 Logcat 能力)
        RECORD_LOGGER = LoggerFactory.getLogger("record")
        DEBUG_LOGGER = LoggerFactory.getLogger("debug")
        FOREST_LOGGER = LoggerFactory.getLogger("forest")
        FARM_LOGGER = LoggerFactory.getLogger("farm")
        OTHER_LOGGER = LoggerFactory.getLogger("other")
        ERROR_LOGGER = LoggerFactory.getLogger("error")
        CAPTURE_LOGGER = LoggerFactory.getLogger("capture")
    }

    /**
     * 🔥 新增初始化方法
     * 在这里传入 Context，追加文件日志功能
     */
    @JvmStatic
    fun init(context: Context) {
        try {
            Logback.initFileLogging(context)
        } catch (e: Exception) {
            android.util.Log.e("SesameLog", "Log init failed", e)
        }
    }

    // --- 日志方法 ---


    @JvmStatic
    fun record(msg: String) {
        if (BaseModel.recordLog.value == true) {
            RECORD_LOGGER.debug("$DEFAULT_TAG{}", msg)
        }
    }

    @JvmStatic
    fun record(tag: String, msg: String) {
        record("[$tag]: $msg")
    }

    @JvmStatic
    fun forest(msg: String) {
        record(msg)
        FOREST_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun forest(tag: String, msg: String) {
        forest("[$tag]: $msg")
    }

    @JvmStatic
    fun farm(msg: String) {
        record(msg)
        FARM_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun other(msg: String) {
        record(msg)
        OTHER_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun other(tag: String, msg: String) {
        other("[$tag]: $msg")
    }

    @JvmStatic
    fun debug(msg: String) {
        record(msg)
        DEBUG_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun debug(tag: String, msg: String) {
        debug("[$tag]: $msg")
    }

    @JvmStatic
    fun error(msg: String) {
        record(msg)
        ERROR_LOGGER.error("$DEFAULT_TAG{}", msg)
    }

    @JvmStatic
    fun error(tag: String, msg: String) {
        error("[$tag]: $msg")
    }

    @JvmStatic
    fun capture(msg: String) {
        CAPTURE_LOGGER.info("$DEFAULT_TAG{}", msg)
    }

    @JvmStatic
    fun capture(tag: String, msg: String) {
        capture("[$tag]: $msg")
    }


    /**
     * 检查是否应该打印此错误（去重机制）
     */
    private fun shouldPrintError(th: Throwable?): Boolean {
        if (th == null) return false

        // 提取错误特征
        var errorSignature = th.javaClass.simpleName + ":" +
                (th.message?.take(50) ?: "null")

        // 特殊处理：JSON解析空字符串错误
        if (th.message?.contains("End of input at character 0") == true) {
            errorSignature = "JSONException:EmptyResponse"
        }

        val count = errorCountMap.computeIfAbsent(errorSignature) { AtomicInteger(0) }
        val currentCount = count.incrementAndGet()

        // 如果是第3次，记录一个汇总信息
        if (currentCount == MAX_DUPLICATE_ERRORS) {
            record("⚠️ 错误【$errorSignature】已出现${currentCount}次，后续将不再打印详细堆栈")
            return true
        }

        // 超过最大次数后不再打印
        return currentCount <= MAX_DUPLICATE_ERRORS
    }

    @JvmStatic

    fun printStackTrace(th: Throwable) {
        if (shouldPrintError(th)) return
        val stackTrace = "error: " + android.util.Log.getStackTraceString(th)
        error(stackTrace)
    }

    @JvmStatic

    fun printStackTrace(msg: String, th: Throwable) {
        if (shouldPrintError(th)) return
        val stackTrace = "Throwable error: " + android.util.Log.getStackTraceString(th)
        error(msg, stackTrace)
    }

    @JvmStatic
    fun printStackTrace(tag: String, msg: String, th: Throwable) {
        if (shouldPrintError(th)) return
        val stackTrace = "[$tag] Throwable error: " + android.util.Log.getStackTraceString(th)
        error(msg, stackTrace)
    }

    // 兼容 Exception 参数的重载 (Kotlin 中 Exception 是 Throwable 的子类，其实可以直接用上面的)
    // 但为了保持原有 Java API 的签名习惯，这里保留
    @JvmStatic
    fun printStackTrace(e: Exception) {
        printStackTrace(e as Throwable)
    }

    @JvmStatic
    fun printStackTrace(msg: String, e: Exception) {
        printStackTrace(msg, e as Throwable)
    }

    @JvmStatic
    fun printStackTrace(tag: String, msg: String, e: Exception) {
        printStackTrace(tag, msg, e as Throwable)
    }

    @JvmStatic
    fun printStack(tag: String) {
        val stackTrace = "stack: " + android.util.Log.getStackTraceString(Exception("获取当前堆栈$tag:"))
        record(stackTrace)
    }

    /**
     * 动物状态日志输出（默认10小时过期）
     */
    @Synchronized
    fun animalStatus(msg: String) {
        animalStatus(msg, 10)
    }

    /**
     * 动物状态日志输出
     * @param msg 消息内容
     * @param expiryHours 过期时间（小时）
     */
    @Synchronized
    fun animalStatus(msg: String, expiryHours: Int) {
        // 依然记录到 record.log 备份，防止数据丢失
        record(msg)

        try {
            val logFile = Files.getAnimalStatusLogFile()

            // 1. 读取当前文件所有行
            val content = Files.readFromFile(logFile)
            val lines: MutableList<String?> = ArrayList<String?>()

            val now = System.currentTimeMillis()
            val expiryMillis = expiryHours.toLong() * 60 * 60 * 1000
            val sdf: DateFormat = TimeUtil.getCommonDateFormat()

            // 1. 遍历清理：同时处理“过期”和“完全重复”
            for (line in content.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()) {
                val trimmedLine = line.trim { it <= ' ' }
                if (trimmedLine.isEmpty()) continue

                // 如果发现内容完全一样，直接跳过（即删除旧的），稍后会在末尾添加新的
                if (trimmedLine == msg.trim { it <= ' ' }) {
                    continue
                }

                var isExpired = false
                val timePart = extractTimePart(trimmedLine)
                if (timePart != null) {
                    try {
                        val logDate: Date? = sdf.parse(timePart)
                        if (logDate != null) {
                            val logCal = Calendar.getInstance()
                            logCal.setTime(logDate)
                            val nowCal = Calendar.getInstance()
                            logCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR))
                            logCal.set(Calendar.MONTH, nowCal.get(Calendar.MONTH))

                            val logMs = logCal.getTimeInMillis()
                            var diff = now - logMs
                            // 跨月补正逻辑保持不变...
                            if (diff < -25L * 24 * 60 * 60 * 1000) {
                                logCal.add(Calendar.MONTH, -1)
                                diff = now - logCal.getTimeInMillis()
                            } else if (diff > 25L * 24 * 60 * 60 * 1000) {
                                logCal.add(Calendar.MONTH, 1)
                                diff = now - logCal.getTimeInMillis()
                            }
                            if (diff > 0 && diff > expiryMillis) isExpired = true
                        }
                    } catch (ignored: java.lang.Exception) {
                    }
                }
                if (!isExpired) lines.add(trimmedLine)
            }

            // 2. 将最新的 msg 添加到列表末尾（实现追加）
            lines.add(msg.trim { it <= ' ' })

            // 3. 写回文件
            val sb = StringBuilder()
            for (line in lines) {
                sb.append(line).append("\n")
            }
            Files.write2File(sb.toString(), logFile)
        } catch (e: java.lang.Exception) {
            Log.e("AnimalStatus-Log", "Update failed", e)
        }
    }

    /**
     * 辅助方法：提取消息中的标识符（括号之前的内容）
     */
    private fun extractId(msg: String): String {
        var idx = msg.indexOf("[")
        if (idx == -1) idx = msg.indexOf("【")
        return if (idx != -1) msg.substring(0, idx).trim { it <= ' ' } else msg.trim { it <= ' ' }
    }

    /**
     * 辅助方法：提取消息中的时间部分
     */
    private fun extractTimePart(line: String): String? {
        var start = line.lastIndexOf("[")
        if (start == -1) start = line.lastIndexOf("【")
        var end = line.lastIndexOf("]")
        if (end == -1) end = line.lastIndexOf("】")

        if (start != -1 && end != -1 && end > start) {
            return line.substring(start + 1, end)
        }
        return null
    }

    fun animalStatus(TAG: String?, msg: String?) {
        animalStatus("[" + TAG + "]: " + msg)
    }

    /**
     * 物理删除特定的动物状态日志 * @param identifier 标识符（即 [ 之前的内容）
     */
    @Synchronized
    fun removeAnimalStatus(identifier: String) {
        try {
            val logFile = Files.getAnimalStatusLogFile()
            val content = Files.readFromFile(logFile)
            val lines: MutableList<String?> = ArrayList<String?>()
            var changed = false

            for (line in content.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()) {
                if (line.trim { it <= ' ' }.isEmpty()) continue
                // 如果这行包含我们要删除的标识符，跳过它（即删除）
                if (line.startsWith(identifier)) {
                    changed = true
                    continue
                }
                lines.add(line)
            }

            if (changed) {
                val sb = StringBuilder()
                for (line in lines) {
                    sb.append(line).append("\n")
                }
                Files.write2File(sb.toString(), logFile)
            }
        } catch (e: java.lang.Exception) {
            Log.e("AnimalStatus", "Remove failed", e)
        }
    }
}