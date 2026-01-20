package fansirsqi.xposed.sesame.model

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.Color
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.entity.MapperEntity
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.ChoiceModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField.MultiplyIntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.ListModelField.ListJoinCommaToStringModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.StringModelField
import fansirsqi.xposed.sesame.ui.widget.ListDialog
import fansirsqi.xposed.sesame.util.FansirsqiUtil
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.ListUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.ToastUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import lombok.Getter
import java.lang.reflect.Field
import kotlin.collections.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomSettings: Model() {
    /**
     * 自定义设置
     */
    init {        // 默认开启
        this.enableField.value = true
    }

    override fun getName(): String {
        return "自定义设置"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.BASE
    }

    override fun getIcon(): String {
        return "BaseModel.png"
    }

    override fun getEnableFieldName(): String {
        return "启用自定义设置"
    }

    override fun boot(classLoader: ClassLoader?) {
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(onlyOnceDaily) //每日只运行一次
        modelFields.addField(onlyOnceDailyList) //每日只运行一次的模块选择
        modelFields.addField(autoHandleOnceDaily) //自动模式
        modelFields.addField(autoHandleOnceDailyTimes) //自动模式时间设置

        return modelFields
    }

    companion object {
        private const val TAG = "CustomSettings"

        /**
         * 是否启用每次只运行一次功能
         */
        @Getter
        val onlyOnceDaily: BooleanModelField =
            BooleanModelField("onlyOnceDaily", "每日只运行一次的模块(选中)", true)
        val autoHandleOnceDaily =
            BooleanModelField("autoHandleOnceDaily", "自动模式", false)

        val autoHandleOnceDailyTimes = ListJoinCommaToStringModelField(
            "autoHandleOnceDailyTimes",
            "自动全量时间点",
            ListUtil.newArrayList("0600", "2000")
        )

        @Getter
        val onlyOnceDailyList: SelectModelField = SelectModelField(
            "onlyOnceDailyList",
            "每日只运行一次 | 模块选择",
            LinkedHashSet<String>().apply {
                add("antOrchard")
                add("antCooperate")
                add("antSports")
                add("antMember")
                add("EcoProtection")
                add("greenFinance")
                add("reserve")
            },
            getModuleList()
        )

    private fun getModuleList(): List<MapperEntity> {
        return listOf(
            SimpleEntity("antForest", "蚂蚁森林"),
            SimpleEntity("antFarm", "蚂蚁庄园"),
            SimpleEntity("antOcean", "海洋"),
            SimpleEntity("antOrchard", "农场"),
            SimpleEntity("antStall", "新村"),
            SimpleEntity("antDodo", "神奇物种"),
            SimpleEntity("antCooperate", "蚂蚁森林合种"),
            SimpleEntity("antSports", "运动"),
            SimpleEntity("antMember", "会员"),
            SimpleEntity("EcoProtection", "生态保护"),
            SimpleEntity("greenFinance", "绿色经营"),
            SimpleEntity("reserve", "保护地"),
            SimpleEntity("other", "其他任务")
        )
    }

    fun getModuleId(taskInfo: String?): String? {
        if (taskInfo == null) return null
        return when {
            taskInfo.contains("合种") || taskInfo.contains("antCooperate") -> "antCooperate"
            taskInfo.contains("蚂蚁森林") || taskInfo.contains("antForest") -> "antForest"
            taskInfo.contains("蚂蚁庄园") || taskInfo.contains("antFarm") -> "antFarm"
            taskInfo.contains("海洋") || taskInfo.contains("antOcean") -> "antOcean"
            taskInfo.contains("农场") || taskInfo.contains("antOrchard") -> "antOrchard"
            taskInfo.contains("新村") || taskInfo.contains("antStall") -> "antStall"
            taskInfo.contains("神奇物种") || taskInfo.contains("antDodo") -> "antDodo"
            taskInfo.contains("运动") || taskInfo.contains("antSports") -> "antSports"
            taskInfo.contains("会员") || taskInfo.contains("antMember") -> "antMember"
            taskInfo.contains("生态保护") || taskInfo.contains("EcoProtection") -> "EcoProtection"
            taskInfo.contains("绿色经营") || taskInfo.contains("greenFinance") -> "greenFinance"
            taskInfo.contains("保护地") || taskInfo.contains("reserve") -> "reserve"
            taskInfo.contains("其他任务") || taskInfo.contains("other") -> "other"
            else -> null
        }
    }

    fun isOnceDailyBlackListed(taskInfo: String?, status: OnceDailyStatus? = null): Boolean {
        val s = status ?: getOnceDailyStatus(false)
        // 只有当单次运行模式生效，且今日已经完成过首轮全量运行的情况下，才执行黑名单排除
        if (s.isEnabledOverride && s.isFinishedToday) {
            val moduleId = getModuleId(taskInfo)
            if (moduleId != null) {
                return onlyOnceDailyList.value.contains(moduleId)
            }
        }
        return false
    }

    data class OnceDailyStatus(
        val isEnabledOverride: Boolean,
        val isFinishedToday: Boolean
    )

    @JvmStatic
    fun getOnceDailyStatus(enableLog: Boolean = false): OnceDailyStatus {
        val configEnabled = onlyOnceDaily.value == true
        val isFinished = try {
            Status.hasFlagToday("OnceDaily::Finished")
        } catch (e: Throwable) {
            false
        }

        val now = System.currentTimeMillis()
        val interval = BaseModel.checkInterval.value.toLong()
        val isSpecialTime = autoHandleOnceDailyTimes.value.any { timeStr ->
            val startCal = TimeUtil.getTodayCalendarByTimeStr(timeStr)
            if (startCal != null) {
                val startTime = startCal.timeInMillis
                val endTime = startTime + interval
                now in startTime..endTime
            } else {
                false
            }
        }

        var isEnabled = configEnabled

        if (isSpecialTime && autoHandleOnceDaily.value) {
            isEnabled = false
            if (enableLog) Log.record("自动单次运行触发: 现在处于自动全量运行时段，本次将运行所有已开启的任务")
        } else if (enableLog && autoHandleOnceDaily.value) {
            val sdf = SimpleDateFormat("HHmm", Locale.getDefault())
            val ranges = autoHandleOnceDailyTimes.value.mapNotNull { timeStr ->
                TimeUtil.getTodayCalendarByTimeStr(timeStr)?.let {
                    val endTime = it.timeInMillis + interval
                    "$timeStr-${sdf.format(Date(endTime))}"
                }
            }.joinToString(", ")
            Log.record("已设置自动全量运行，时段为：$ranges")
        }

        // 如果今日尚未完成首次全量运行，则不启用“跳过”拦截逻辑
        if (isEnabled && !isFinished) {
            isEnabled = false
            if (enableLog) Log.record("当日单次运行模式生效: 今日尚未完成首次全量运行，本次将运行所有任务")
        } else if (isEnabled) {
            if (enableLog) Log.record("当日单次运行模式生效: 今日已完成全量运行，已启用跳过黑名单任务")
        }

        return OnceDailyStatus(isEnabled, isFinished)
    }

        data class ButtonState(val text: String, val color: Color)

        @JvmStatic
        fun toggleOnceDailyMode() {
            // 直接读取底层值并处理 null
            val isEnabled = onlyOnceDaily.value == true
            val isAuto = autoHandleOnceDaily.value == true

            // 采用显式的原子化互斥分支，确保状态机精确流转：
            // 状态 1: 当前是“关闭” -> 切换到“单次启用”
            if (!isEnabled) {
                onlyOnceDaily.setObjectValue(true)
                autoHandleOnceDaily.setObjectValue(false)
            }
            // 状态 2: 当前是“启用”且不是自动 -> 切换到“自动单次”
            else if (!isAuto) {
                onlyOnceDaily.setObjectValue(true)
                autoHandleOnceDaily.setObjectValue(true)
            }
            // 状态 3: 当前已是“自动” -> 切换到“关闭”
            else {
                onlyOnceDaily.setObjectValue(false)
                autoHandleOnceDaily.setObjectValue(false)
            }
        }

        /**
         * 获取按钮状态
         * @param isEnabled 对应 onlyOnceDaily
         * @param isAuto 对应 autoHandleOnceDaily
         * @param isFinished 对应 今日是否已完成任务
         */
        @JvmStatic
        fun getButtonState(
            isEnabled: Boolean = onlyOnceDaily.value == true,
            isAuto: Boolean = autoHandleOnceDaily.value == true,
            isFinished: Boolean = try {
                Status.hasFlagToday("OnceDaily::Finished")
            } catch (e: Throwable) {
                false
            }
        ): ButtonState {
            return when {
                !isEnabled -> {
                    // 关闭状态：显式红色
                    ButtonState("单次已关闭", Color(0xFFF44336))
                }
                isAuto -> {
                    // 自动单次模式：根据今日完成情况切换 绿色/橙色
                    val color = if (isFinished) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    ButtonState("自动单次", color)
                }
                else -> {
                    // 普通启用状态：根据今日完成情况切换 绿色/橙色
                    val color = if (isFinished) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    ButtonState("单次已启用", color)
                }
            }
        }
    }
}

private class SimpleEntity(id: String, name: String) : MapperEntity() {
    init {
        this.id = id
        this.name = name
    }
}
