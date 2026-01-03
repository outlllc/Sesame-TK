package fansirsqi.xposed.sesame.model

import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.ChoiceModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField.MultiplyIntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.ListModelField.ListJoinCommaToStringModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.StringModelField
import fansirsqi.xposed.sesame.util.ListUtil
import lombok.Getter

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
            modelFields.addField(antForest) //
            modelFields.addField(antFarm) //
            modelFields.addField(antOcean) //
            modelFields.addField(antOrchard) //
            modelFields.addField(antStall) //
            modelFields.addField(antDodo) //
            modelFields.addField(antCooperate) //
            modelFields.addField(antSports) //
            modelFields.addField(antMember) //
            modelFields.addField(EcoProtection) //
            modelFields.addField(greenFinance) //
            modelFields.addField(reserve) //
            return modelFields
        }

//        interface TimedTaskModel {
//            companion object {
//                const val SYSTEM: Int = 0
//                const val PROGRAM: Int = 1
//                val nickNames: Array<String?> = arrayOf<String?>("🤖系统计时", "📦程序计时")
//            }
//        }

        companion object {
            private const val TAG = "CustomSettings"

            /**
             * 是否启用每次只运行一次功能
             */
            @Getter
            val onlyOnceDaily: BooleanModelField = BooleanModelField("onlyOnceDaily", "每日只运行一次的模块(选中)", true)

            /**
             * //每日只运行一次森林
             */
            @Getter
            val antForest: BooleanModelField = BooleanModelField("antForest", "蚂蚁森林", false)
            val antFarm: BooleanModelField = BooleanModelField("antFarm", "蚂蚁庄园", false)
            val antOcean: BooleanModelField = BooleanModelField("antOcean", "海洋", false)
            val antOrchard: BooleanModelField = BooleanModelField("antOrchard", "农场", true)
            val antStall: BooleanModelField = BooleanModelField("antStall", "新村", false)
            val antDodo: BooleanModelField = BooleanModelField("antDodo", "神奇物种", false)
            val antCooperate: BooleanModelField = BooleanModelField("antCooperate", "蚂蚁森林合种", true)
            val antSports: BooleanModelField = BooleanModelField("antSports", "运动", true)
            val antMember: BooleanModelField = BooleanModelField("antMember", "会员", true)
            val EcoProtection: BooleanModelField = BooleanModelField("EcoProtection", "生态保护", true)
            val greenFinance: BooleanModelField = BooleanModelField("greenFinance", "绿色经营", true)
            val reserve: BooleanModelField = BooleanModelField("reserve", "保护地", true)

            /**
             * 执行间隔时间（分钟）
             */
            @Getter
            val checkInterval: MultiplyIntegerModelField = MultiplyIntegerModelField("checkInterval", "执行间隔(分钟)", 50, 1, 12 * 60, 60000) //此处调整至30分钟执行一次，可能会比平常耗电一点。。

            /**
             * 定时唤醒的时间点列表
             */
            @Getter
            val wakenAtTimeList: ListJoinCommaToStringModelField = ListJoinCommaToStringModelField(
                "wakenAtTimeList", "定时唤醒(关闭:-1)", ListUtil.newArrayList<String?>(
                    "0010", "0030", "0100", "0650", "2350" // 添加多个0点后的时间点
                )
            )

            /**
             * 能量收集的时间范围
             */
            @Getter
            val energyTime: ListJoinCommaToStringModelField = ListJoinCommaToStringModelField("energyTime", "只收能量时间(范围|关闭:-1)", ListUtil.newArrayList<String?>("0700-0730"))

            /**
             * 定时任务模式选择
             */
//            @Getter
//            val timedTaskModel: ChoiceModelField = ChoiceModelField("timedTaskModel", "定时任务模式", TimedTaskModel.Companion.SYSTEM, TimedTaskModel.Companion.nickNames)

            /**
             * 异常发生时的等待时间（分钟）
             */
            @Getter
            val waitWhenException: MultiplyIntegerModelField = MultiplyIntegerModelField("waitWhenException", "异常等待时间(分钟)", 60, 0, 24 * 60, 60000)

            @Getter
            val toastPerfix: StringModelField = StringModelField("toastPerfix", "气泡前缀", null)

            /**
             * 气泡提示的纵向偏移量
             */
            @Getter
            val toastOffsetY: IntegerModelField = IntegerModelField("toastOffsetY", "气泡纵向偏移", 99)

        }


}