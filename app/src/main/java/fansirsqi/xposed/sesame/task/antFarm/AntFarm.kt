@file:Suppress("ClassName")

package fansirsqi.xposed.sesame.task.antFarm

import android.annotation.SuppressLint
import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.entity.AlipayUser
import fansirsqi.xposed.sesame.entity.MapperEntity
import fansirsqi.xposed.sesame.entity.OtherEntityProvider.farmFamilyOption
import fansirsqi.xposed.sesame.entity.ParadiseCoinBenefit
import fansirsqi.xposed.sesame.hook.CoroutineScheduler
import fansirsqi.xposed.sesame.hook.Toast
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit.addIntervalLimit
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.ChoiceModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.ListModelField.ListJoinCommaToStringModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectAndCountModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.StringModelField
import fansirsqi.xposed.sesame.newutil.DataStore
import fansirsqi.xposed.sesame.newutil.DataStore.getOrCreate
import fansirsqi.xposed.sesame.newutil.TaskBlacklist
import fansirsqi.xposed.sesame.task.AnswerAI.AnswerAI
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.task.TaskCommon
import fansirsqi.xposed.sesame.task.antFarm.AntFarmFamily.familyClaimRewardList
import fansirsqi.xposed.sesame.task.antFarm.AntFarmFamily.familySign
import fansirsqi.xposed.sesame.task.antForest.TaskTimeChecker
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.ListUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.RandomUtil
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.StringUtil
import fansirsqi.xposed.sesame.util.TimeCounter
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.IdMapManager
import fansirsqi.xposed.sesame.util.maps.ParadiseCoinBenefitIdMap
import fansirsqi.xposed.sesame.util.maps.UserMap
import fansirsqi.xposed.sesame.util.maps.VipDataIdMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import lombok.ToString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar
import java.util.Locale
import java.util.Objects
import java.util.Random
import kotlin.math.min
@Suppress("unused", "EnumEntryName", "EnumEntryName", "EnumEntryName", "EnumEntryName")
class AntFarm : ModelTask() {
    private var ownerFarmId: String? = null
    private var animals: Array<Animal>? = null
    private var ownerAnimal = Animal()
    private var rewardProductNum: String? = null
    private var rewardList: Array<RewardFriend>? = null
    private var countdown: Long? = null
    /**
     * 慈善评分
     */
    private var benevolenceScore = 0.0
    private var harvestBenevolenceScore = 0.0

    /**
     * 未领取的饲料奖励
     */
    private var unreceiveTaskAward = 0

    /**
     * 小鸡心情值
     */
    private var finalScore = 0.0
    private var familyGroupId: String? = null
    private var farmTools: Array<FarmTool> = emptyArray()

    // 服务端返回的“是否已使用加饭卡”状态（从 subFarmVO.useBigEaterTool 解析）
    private var serverUseBigEaterTool: Boolean = false

    // 当前食槽上限（从 subFarmVO.foodInTroughLimit 解析，默认 180；使用加饭卡后为 360）
    private var foodInTroughLimitCurrent: Int = 180

    /**
     * 标记农场是否已满（用于雇佣小鸡逻辑）
     */
    private var isFarmFull: Boolean = false

    /**
     * 将服务端的饲喂状态代码转换为可读中文
     */
    private fun toFeedStatusName(status: String?): String {
        return when (status) {
            AnimalFeedStatus.HUNGRY.name -> "饥饿"
            AnimalFeedStatus.EATING.name -> "进食中"
            AnimalFeedStatus.SLEEPY.name -> "睡觉中"
            else -> status ?: "未知"
        }
    }

    override fun getName(): String {
        return "蚂蚁庄园"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.FARM
    }

    override fun getIcon(): String {
        return "AntFarm.png"
    }

    /**
     * 小鸡睡觉时间
     */
    private var sleepTime: StringModelField? = null

    /**
     * 小鸡睡觉时长
     */
    private var sleepMinutes: IntegerModelField? = null

    /**
     * 自动喂鸡
     */
    private var feedAnimal: BooleanModelField? = null

    /**
     * 打赏好友
     */
    private var rewardFriend: BooleanModelField? = null

    /**
     * 遣返小鸡
     */
    private var sendBackAnimal: BooleanModelField? = null

    /**
     * 遣返方式
     */
    private var sendBackAnimalWay: ChoiceModelField? = null

    /**
     * 遣返动作
     */
    private var sendBackAnimalType: ChoiceModelField? = null

    /**
     * 遣返好友列表
     */
    private var sendBackAnimalList: SelectModelField? = null

    /**
     * 召回小鸡
     */
    private var recallAnimalType: ChoiceModelField? = null

    /**
     * s收取道具奖励
     */
    private var receiveFarmToolReward: BooleanModelField? = null

    /**
     * 游戏改分
     */
    private var recordFarmGame: BooleanModelField? = null

    /**
     * 小鸡游戏时间
     */
    private var farmGameTime: ListJoinCommaToStringModelField? = null

    /**
     * 小鸡厨房
     */
    private var kitchen: BooleanModelField? = null

    /**
     * 使用特殊食品
     */
    private var useSpecialFood: BooleanModelField? = null
    private var useNewEggCard: BooleanModelField? = null
    private var harvestProduce: BooleanModelField? = null
    private var donation: BooleanModelField? = null
    private var donationCount: ChoiceModelField? = null

    /**
     * 饲料任务
     */
    private var doFarmTask: BooleanModelField? = null // 做饲料任务
    private var doFarmTaskTime: StringModelField? = null // 饲料任务执行时间

    /**
     * 收取饲料奖励（无时间限制）
     */
    private var receiveFarmTaskAward: BooleanModelField? = null
    private var useAccelerateTool: BooleanModelField? = null
    private var useBigEaterTool: BooleanModelField? = null // ✅ 新增加饭卡
    private var useAccelerateToolContinue: BooleanModelField? = null
    private var useAccelerateToolWhenMaxEmotion: BooleanModelField? = null

    /**
     * 喂鸡列表
     */
    private var feedFriendAnimalList: SelectAndCountModelField? = null
    private var notifyFriend: BooleanModelField? = null
    private var notifyFriendType: ChoiceModelField? = null
    private var notifyFriendList: SelectModelField? = null
    private var acceptGift: BooleanModelField? = null
    private var visitFriendList: SelectAndCountModelField? = null
    private var chickenDiary: BooleanModelField? = null
    private var diaryTietie: BooleanModelField? = null
    private var collectChickenDiary: ChoiceModelField? = null
    private lateinit var remainingTime: IntegerModelField
    private var enableChouchoule: BooleanModelField? = null
    private var enableChouchouleTime: StringModelField? = null // 抽抽乐执行时间
    private var listOrnaments: BooleanModelField? = null
    private var hireAnimal: BooleanModelField? = null
    private var hireAnimalType: ChoiceModelField? = null
    private var hireAnimalList: SelectModelField? = null
    private var enableDdrawGameCenterAward: BooleanModelField? = null
    private var getFeed: BooleanModelField? = null
    private var getFeedlList: SelectModelField? = null
    private var getFeedType: ChoiceModelField? = null
    private var family: BooleanModelField? = null
    private var familyOptions: SelectModelField? = null
    private var notInviteList: SelectModelField? = null
    private val giftFamilyDrawFragment: StringModelField? = null
    private var paradiseCoinExchangeBenefit: BooleanModelField? = null
    private var paradiseCoinExchangeBenefitList: SelectModelField? = null

    private var visitAnimal: BooleanModelField? = null

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            StringModelField(
                "sleepTime",
                "小鸡睡觉时间(关闭:-1)",
                "2330"
            ).also { sleepTime = it })
        modelFields.addField(
            IntegerModelField(
                "sleepMinutes",
                "小鸡睡觉时长(分钟)",
                10 * 36,
                1,
                10 * 60
            ).also { sleepMinutes = it })
        modelFields.addField(
            ChoiceModelField(
                "recallAnimalType",
                "召回小鸡",
                RecallAnimalType.ALWAYS,
                RecallAnimalType.nickNames
            ).also { recallAnimalType = it })
        modelFields.addField(
            BooleanModelField(
                "rewardFriend",
                "打赏好友",
                false
            ).also { rewardFriend = it })
        modelFields.addField(
            BooleanModelField(
                "feedAnimal",
                "自动喂小鸡",
                false
            ).also { feedAnimal = it })

        modelFields.addField(
            SelectAndCountModelField(
                "feedFriendAnimalList",
                "帮喂小鸡 | 好友列表",
                LinkedHashMap<String?, Int?>(),
                { AlipayUser.getList() },
                "记得设置帮喂次数.."
            ).also {
                feedFriendAnimalList = it
            })
        modelFields.addField(BooleanModelField("getFeed", "一起拿饲料", false).also {
            getFeed = it
        })
        modelFields.addField(
            ChoiceModelField(
                "getFeedType",
                "一起拿饲料 | 动作",
                GetFeedType.GIVE,
                GetFeedType.nickNames
            ).also { getFeedType = it })
        modelFields.addField(
            SelectModelField(
                "getFeedlList",
                "一起拿饲料 | 好友列表",
                LinkedHashSet<String?>()
            ) { AlipayUser.getList() }.also {
                getFeedlList = it
            })
        modelFields.addField(BooleanModelField("acceptGift", "收麦子", false).also {
            acceptGift = it
        })
        modelFields.addField(
            SelectAndCountModelField(
                "visitFriendList",
                "送麦子好友列表",
                LinkedHashMap<String?, Int?>(),
                { AlipayUser.getList() },
                "设置赠送次数？？"
            ).also {
                visitFriendList = it
            })
        modelFields.addField(
            BooleanModelField(
                "hireAnimal",
                "雇佣小鸡 | 开启",
                false
            ).also { hireAnimal = it })
        modelFields.addField(
            ChoiceModelField(
                "hireAnimalType",
                "雇佣小鸡 | 动作",
                HireAnimalType.DONT_HIRE,
                HireAnimalType.nickNames
            ).also { hireAnimalType = it })
        modelFields.addField(
            SelectModelField(
                "hireAnimalList",
                "雇佣小鸡 | 好友列表",
                LinkedHashSet<String?>()
            ) { AlipayUser.getList() }.also {
                hireAnimalList = it
            })
        modelFields.addField(
            BooleanModelField(
                "sendBackAnimal",
                "遣返 | 开启",
                false
            ).also { sendBackAnimal = it })
        modelFields.addField(
            ChoiceModelField(
                "sendBackAnimalWay",
                "遣返 | 方式",
                SendBackAnimalWay.NORMAL,
                SendBackAnimalWay.nickNames
            ).also { sendBackAnimalWay = it })
        modelFields.addField(
            ChoiceModelField(
                "sendBackAnimalType",
                "遣返 | 动作",
                SendBackAnimalType.NOT_BACK,
                SendBackAnimalType.nickNames
            ).also { sendBackAnimalType = it })
        modelFields.addField(
            SelectModelField(
                "dontSendFriendList",
                "遣返 | 好友列表",
                LinkedHashSet<String?>()
            ) { AlipayUser.getList() }.also {
                sendBackAnimalList = it
            })
        modelFields.addField(
            BooleanModelField(
                "notifyFriend",
                "通知赶鸡 | 开启",
                false
            ).also { notifyFriend = it })
        modelFields.addField(
            ChoiceModelField(
                "notifyFriendType",
                "通知赶鸡 | 动作",
                NotifyFriendType.NOTIFY,
                NotifyFriendType.nickNames
            ).also { notifyFriendType = it })
        modelFields.addField(
            SelectModelField(
                "notifyFriendList",
                "通知赶鸡 | 好友列表",
                LinkedHashSet<String?>()
            ) { AlipayUser.getList() }.also {
                notifyFriendList = it
            })
        modelFields.addField(
            BooleanModelField(
                "donation",
                "每日捐蛋 | 开启",
                false
            ).also { donation = it })
        modelFields.addField(
            ChoiceModelField(
                "donationCount",
                "每日捐蛋 | 次数",
                DonationCount.ONE,
                DonationCount.nickNames
            ).also { donationCount = it })
        modelFields.addField(
            BooleanModelField(
                "useBigEaterTool",
                "加饭卡 | 使用",
                false
            ).also { useBigEaterTool = it })
        modelFields.addField(
            BooleanModelField(
                "useAccelerateTool",
                "加速卡 | 使用",
                false
            ).also { useAccelerateTool = it })
        modelFields.addField(
            BooleanModelField(
                "useAccelerateToolContinue",
                "加速卡 | 连续使用",
                false
            ).also { useAccelerateToolContinue = it })
        modelFields.addField(
            IntegerModelField("remainingTime", "饲料剩余时间大于多少时直接使用加速（分钟）（-1关闭）", 20).also { remainingTime = it }
        )
        modelFields.addField(
            BooleanModelField(
                "useAccelerateToolWhenMaxEmotion",
                "加速卡 | 仅在满状态时使用",
                false
            ).also { useAccelerateToolWhenMaxEmotion = it })
        modelFields.addField(
            BooleanModelField(
                "useSpecialFood",
                "使用特殊食品",
                false
            ).also { useSpecialFood = it })
        modelFields.addField(
            BooleanModelField(
                "useNewEggCard",
                "使用新蛋卡",
                false
            ).also { useNewEggCard = it })
        modelFields.addField(
            BooleanModelField(
                "doFarmTask",
                "做饲料任务",
                false
            ).also { doFarmTask = it })
        modelFields.addField(
            StringModelField(
                "doFarmTaskTime",
                "饲料任务执行时间 | 默认8:30后执行",
                "0830"
            ).also { doFarmTaskTime = it })

        modelFields.addField(
            BooleanModelField(
                "receiveFarmTaskAward",
                "收取饲料奖励",
                false
            ).also { receiveFarmTaskAward = it })

        modelFields.addField(
            BooleanModelField(
                "receiveFarmToolReward",
                "收取道具奖励",
                false
            ).also { receiveFarmToolReward = it })
        modelFields.addField(
            BooleanModelField(
                "harvestProduce",
                "收获爱心鸡蛋",
                false
            ).also { harvestProduce = it })
        modelFields.addField(BooleanModelField("kitchen", "小鸡厨房", false).also { kitchen = it })
        modelFields.addField(
            BooleanModelField(
                "chickenDiary",
                "小鸡日记",
                false
            ).also { chickenDiary = it })
        modelFields.addField(
            BooleanModelField(
                "diaryTietze",
                "小鸡日记 | 贴贴",
                false
            ).also { diaryTietie = it })
        modelFields.addField(
            ChoiceModelField(
                "collectChickenDiary",
                "小鸡日记 | 点赞",
                collectChickenDiaryType.ONCE,
                collectChickenDiaryType.nickNames
            ).also { collectChickenDiary = it })
        modelFields.addField(
            BooleanModelField(
                "enableChouchoule",
                "开启小鸡抽抽乐",
                false
            ).also { enableChouchoule = it })
        modelFields.addField(
            StringModelField(
                "enableChouchouleTime",
                "小鸡抽抽乐执行时间 | 默认9:00后执行",
                "0900"
            ).also { enableChouchouleTime = it })
        modelFields.addField(
            BooleanModelField(
                "listOrnaments",
                "小鸡每日换装",
                false
            ).also { listOrnaments = it })
        modelFields.addField(
            BooleanModelField(
                "enableDdrawGameCenterAward",
                "开宝箱",
                false
            ).also { enableDdrawGameCenterAward = it })
        modelFields.addField(
            BooleanModelField(
                "recordFarmGame",
                "游戏改分(星星球、登山赛、飞行赛、揍小鸡)",
                false
            ).also { recordFarmGame = it })
        modelFields.addField(
            ListJoinCommaToStringModelField(
                "farmGameTime",
                "小鸡游戏时间(范围)",
                ListUtil.newArrayList("2200-2400")
            ).also { farmGameTime = it })
        modelFields.addField(BooleanModelField("family", "家庭 | 开启", false).also { family = it })
        modelFields.addField(
            SelectModelField(
                "familyOptions",
                "家庭 | 选项",
                LinkedHashSet<String?>(),
                farmFamilyOption()
            ).also { familyOptions = it })
        modelFields.addField(
            SelectModelField(
                "notInviteList",
                "家庭 | 好友分享排除列表",
                LinkedHashSet<String?>()
            ) { AlipayUser.getList() }.also {
                notInviteList = it
            })
        //        modelFields.addField(giftFamilyDrawFragment = new StringModelField("giftFamilyDrawFragment", "家庭 | 扭蛋碎片赠送用户ID(配置目录查看)", ""));
        modelFields.addField(
            BooleanModelField(
                "paradiseCoinExchangeBenefit",
                "小鸡乐园 | 兑换权益",
                false
            ).also { paradiseCoinExchangeBenefit = it })
        modelFields.addField(
            SelectModelField(
                "paradiseCoinExchangeBenefitList",
                "小鸡乐园 | 权益列表",
                LinkedHashSet<String?>()
            ) { ParadiseCoinBenefit.getList() }.also {
                paradiseCoinExchangeBenefitList = it
            })
        modelFields.addField(
            BooleanModelField(
                "visitAnimal",
                "到访小鸡送礼",
                false
            ).also { visitAnimal = it })
        return modelFields
    }

    override fun boot(classLoader: ClassLoader?) {
        super.boot(classLoader)
        addIntervalLimit("com.alipay.antfarm.enterFarm", 2000)
    }

    override fun check(): Boolean {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.record(
                TAG,
                "⏸ 当前为只收能量时间【" + BaseModel.energyTime
                    .value + "】，停止执行" + name + "任务！"
            )
            return false
        } else if (TaskCommon.IS_MODULE_SLEEP_TIME) {
            Log.record(
                TAG,
                "💤 模块休眠时间【" + BaseModel.modelSleepTime
                    .value + "】停止执行" + name + "任务！"
            )
            return false
        } else {
            return true
        }
    }


    override suspend fun runSuspend() {
        try {
            val tc = TimeCounter(TAG)
            val userId = UserMap.currentUid
            Log.record(TAG, "执行开始-蚂蚁$name")

            if (enterFarm() == null) {
                return
            }
            // 雇佣小鸡
            if (hireAnimal!!.value) {
                hireAnimal()
            }

            // 做饲料任务
            if (doFarmTask!!.value) {
                if(!Status.hasFlagToday("farm::farmTaskFinished")) {
                    // 检查是否到达执行时间
                    if (TaskTimeChecker.isTimeReached(doFarmTaskTime?.value, "0830")) {
                        doFarmTasks()
                        tc.countDebug("饲料任务")
                        Status.setFlagToday("farm::farmTaskFinished")
                    } else {
                        Log.record(TAG, "饲料任务未到执行时间，跳过")
                    }
                }
            }

            if (sendBackAnimal!!.value) {
                sendBackAnimal()
                tc.countDebug("遣返")
            }

            handleAutoFeedAnimal()
            tc.countDebug("喂食")

            recallAnimal()
            tc.countDebug("召回小鸡")

            listFarmTool() //装载道具信息
            tc.countDebug("装载道具信息")

            if (rewardFriend!!.value) {
                rewardFriend()
                tc.countDebug("打赏好友")
            }

            if (receiveFarmToolReward!!.value) {
                receiveToolTaskReward()
                tc.countDebug("收取道具奖励")
            }
            if (recordFarmGame!!.value) {
                if (!Status.hasFlagToday("farm::farmGameFinished")) {
                    if (Status.hasFlagToday("farm::accelerateLimit") || !Status.canUseAccelerateTool()) {
                        syncAnimalStatus(ownerFarmId)
                        // 180,暂时修改为360以观察飞行和打小鸡能否得到360g能量
                        if (foodStock >= foodStockLimit - 360) {
                            recordFarmGame(GameType.flyGame)
                            recordFarmGame(GameType.hitGame)
                            recordFarmGame(GameType.starGame)
                            recordFarmGame(GameType.jumpGame)
                            Status.setFlagToday("farm::farmGameFinished")
                        } else {
                            Log.farm("加速卡已使用达上限;饲料没有满，暂不执行游戏改分")
                        }
                    } else {
                        Log.farm("加速卡未使用到达上限，暂不执行游戏改分")
                    }
                } else {
                    Log.farm("今日庄园游戏改分已完成")
                }
                tc.countDebug("游戏改分(星星球、登山赛、飞行赛、揍小鸡)")
            }
            if (kitchen!!.value) {
                // 检查小鸡是否在睡觉，如果在睡觉则跳过厨房功能
                if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
                    Log.record(TAG, "小鸡厨房🐔[小鸡正在睡觉中，跳过厨房功能]")
                } else {
                    collectDailyFoodMaterial()
                    collectDailyLimitedFoodMaterial()
                    cook()
                }
                tc.countDebug("小鸡厨房")
            }

            if (chickenDiary!!.value) {
                doChickenDiary()
                tc.countDebug("小鸡日记")
            }

            if (useNewEggCard!!.value) {
                useFarmTool(ownerFarmId, ToolType.NEWEGGTOOL)
                syncAnimalStatus(ownerFarmId)
                tc.countDebug("使用新蛋卡")
            }
            if (harvestProduce!!.value && benevolenceScore >= 1) {
                Log.record(TAG, "有可收取的爱心鸡蛋")
                harvestProduce(ownerFarmId)
                tc.countDebug("收鸡蛋")
            }
            if (donation!!.value && Status.canDonationEgg(userId) && harvestBenevolenceScore >= 1) {
                handleDonation(donationCount!!.value)
                tc.countDebug("每日捐蛋")
                Log.farm("今日捐蛋完成")
            }


            // 做饲料任务
            if (doFarmTask!!.value) {
                // 检查是否到达执行时间
                if (TaskTimeChecker.isTimeReached(doFarmTaskTime?.value, "0830")) {
                    doFarmTasks()
                    tc.countDebug("饲料任务")
                    Status.setFlagToday("farm::farmTaskFinished")
                } else {
                    Log.record(TAG, "饲料任务未到执行时间，跳过")
                }
            }

            // 收取饲料奖励（无时间限制）
            if (receiveFarmTaskAward!!.value) {
                receiveFarmAwards()
                tc.countDebug("收取饲料奖励")
            }

            // 到访小鸡送礼
            if (visitAnimal!!.value) {
                visitAnimal()
                tc.countDebug("到访小鸡送礼")
                // 送麦子
                visit()
                tc.countDebug("送麦子")
            }
            // 帮好友喂鸡
            feedFriend()
            tc.countDebug("帮好友喂鸡")
            // 通知好友赶鸡
            if (notifyFriend!!.value) {
                notifyFriend()
                tc.countDebug("通知好友赶鸡")
            }

            // 抽抽乐
            if (enableChouchoule!!.value) {
                if (!Status.hasFlagToday("farm::chouChouLeFinished")) {
                    if (Status.hasFlagToday("farm::farmGameFinished")) {
                        val ccl = ChouChouLe()
                        ccl.chouchoule()
                        tc.countDebug("抽抽乐")
                        Status.setFlagToday("farm::chouChouLeFinished")
                    } else {
                    Log.farm("游戏改分还没有执行，暂不执行抽抽乐")
                    }
                } else {
                    Log.farm("今日抽抽乐已完成")
                }
            }

            if (getFeed!!.value) {
                letsGetChickenFeedTogether()
                tc.countDebug("一起拿饲料")
            }
            //家庭
            if (family!!.value) {
                //                family();

                AntFarmFamily.run(familyOptions!!, notInviteList!!)
                tc.countDebug("家庭任务")
            }
            // 开宝箱
            if (enableDdrawGameCenterAward!!.value) {
                drawGameCenterAward()
                tc.countDebug("开宝箱")
            }
            // 小鸡乐园道具兑换
            if (paradiseCoinExchangeBenefit!!.value) {
                paradiseCoinExchangeBenefit()
                tc.countDebug("小鸡乐园道具兑换")
            }
            //小鸡睡觉&起床
            animalSleepAndWake()
            tc.countDebug("小鸡睡觉&起床")

            // 小鸡睡觉后领取饲料
            if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
                Log.record(TAG, "小鸡正在睡觉，领取饲料")
                receiveFarmAwards()
            }

            tc.stop()
        } catch (e: CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.debug(TAG, "AntFarm 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "AntFarm.start.run err:",t)
        } finally {
            Log.record(TAG, "执行结束-蚂蚁$name")
        }
    }

    /**
     * 召回小鸡
     */
    private fun recallAnimal() {
        try {
            //召回小鸡相关操作
            if (AnimalInteractStatus.HOME.name != ownerAnimal.animalInteractStatus) { //如果小鸡不在家
                if ("ORCHARD" == ownerAnimal.locationType) {
                    Log.farm("庄园通知📣[你家的小鸡给拉去除草了！]")
                    val joRecallAnimal = JSONObject(
                        AntFarmRpcCall.orchardRecallAnimal(
                            ownerAnimal.animalId,
                            ownerAnimal.currentFarmMasterUserId
                        )
                    )
                    val manureCount = joRecallAnimal.getInt("manureCount")
                    Log.farm("召回小鸡📣[收获:肥料" + manureCount + "g]")
                } else {
                    Log.runtime(TAG, "DEBUG:$ownerAnimal")

                    syncAnimalStatus(ownerFarmId)
                    var guest = false
                    when (SubAnimalType.valueOf(ownerAnimal.subAnimalType!!)) {
                        SubAnimalType.GUEST -> {
                            guest = true
                            Log.record(TAG, "小鸡到好友家去做客了")
                        }

                        SubAnimalType.NORMAL -> Log.record(TAG, "小鸡太饿，离家出走了")
                        SubAnimalType.PIRATE -> Log.record(TAG, "小鸡外出探险了")
                        SubAnimalType.WORK -> Log.record(TAG, "小鸡出去工作啦")
                    }
                    var hungry = false
                    val userName =
                        UserMap.getMaskName(AntFarmRpcCall.farmId2UserId(ownerAnimal.currentFarmId))
                    when (AnimalFeedStatus.valueOf(ownerAnimal.animalFeedStatus!!)) {
                        AnimalFeedStatus.HUNGRY -> {
                            hungry = true
                            Log.record(TAG, "小鸡在[$userName]的庄园里挨饿")
                        }

                        AnimalFeedStatus.EATING -> Log.record(
                            TAG,
                            "小鸡在[$userName]的庄园里吃得津津有味"
                        )
                        AnimalFeedStatus.SLEEPY -> Log.record(TAG, "小鸡在[$userName]的庄园里睡觉")
                        AnimalFeedStatus.NONE -> Log.record(TAG, "小鸡在[$userName]的庄园里状态未知")
                    }
                    val recall = when (recallAnimalType!!.value) {
                        RecallAnimalType.ALWAYS -> true
                        RecallAnimalType.WHEN_THIEF -> !guest
                        RecallAnimalType.WHEN_HUNGRY -> hungry
                        else -> false
                    }
                    if (recall) {
                        recallAnimal(
                            ownerAnimal.animalId,
                            ownerAnimal.currentFarmId,
                            ownerFarmId,
                            userName
                        )
                        syncAnimalStatus(ownerFarmId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "recallAnimal err:", e)
        }
    }

    private suspend fun paradiseCoinExchangeBenefit() {
        try {
            val jo = JSONObject(AntFarmRpcCall.getMallHome())

            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "小鸡乐园币💸[未获取到可兑换权益]")
                return
            }
            val mallItemSimpleList = jo.getJSONArray("mallItemSimpleList")
            for (i in 0..<mallItemSimpleList.length()) {
                val mallItemInfo = mallItemSimpleList.getJSONObject(i)
                val oderInfo: String?
                val spuName = mallItemInfo.getString("spuName")
                val minPrice = mallItemInfo.getInt("minPrice")
                val controlTag = mallItemInfo.getString("controlTag")
                val spuId = mallItemInfo.getString("spuId")
                oderInfo = spuName + "\n价格" + minPrice + "乐园币\n" + controlTag
                IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java)
                    .add(spuId, oderInfo)
                val itemStatusList = mallItemInfo.getJSONArray("itemStatusList")
                if (!Status.canParadiseCoinExchangeBenefitToday(spuId) || !paradiseCoinExchangeBenefitList!!.value
                        .contains(spuId) || isExchange(itemStatusList, spuId, spuName)
                ) {
                    continue
                }
                var exchangedCount = 0
                while (exchangeBenefit(spuId)) {
                    exchangedCount += 1
                    Log.farm("乐园币兑换💸#花费[" + minPrice + "乐园币]" + "#第" + exchangedCount + "次兑换" + "[" + spuName + "]")
                    delay(3000)
                }
            }
            IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java)
                .save(UserMap.currentUid)
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "paradiseCoinExchangeBenefit 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "paradiseCoinExchangeBenefit err:",t)
        }
    }

    private fun exchangeBenefit(spuId: String?): Boolean {
        try {
            val jo = JSONObject(AntFarmRpcCall.getMallItemDetail(spuId))
            if (!ResChecker.checkRes(TAG, jo)) {
                return false
            }
            val mallItemDetail = jo.getJSONObject("mallItemDetail")
            val mallSubItemDetailList = mallItemDetail.getJSONArray("mallSubItemDetailList")
            for (i in 0..<mallSubItemDetailList.length()) {
                val mallSubItemDetail = mallSubItemDetailList.getJSONObject(i)
                val skuId = mallSubItemDetail.getString("skuId")
                val skuName = mallSubItemDetail.getString("skuName")
                val itemStatusList = mallSubItemDetail.getJSONArray("itemStatusList")

                if (isExchange(itemStatusList, spuId, skuName)) {
                    return false
                }

                if (exchangeBenefit(spuId, skuId)) {
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeBenefit err:",t)
        }
        return false
    }

    private fun exchangeBenefit(spuId: String?, skuId: String?): Boolean {
        try {
            val jo = JSONObject(AntFarmRpcCall.buyMallItem(spuId, skuId))
            return ResChecker.checkRes(TAG, jo)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeBenefit err:",t)
        }
        return false
    }

    private fun isExchange(itemStatusList: JSONArray, spuId: String?, spuName: String?): Boolean {
        try {
            for (j in 0..<itemStatusList.length()) {
                val itemStatus = itemStatusList.getString(j)
                if (PropStatus.REACH_LIMIT.name == itemStatus
                    || PropStatus.REACH_USER_HOLD_LIMIT.name == itemStatus
                    || PropStatus.NO_ENOUGH_POINT.name == itemStatus
                ) {
                    Log.record(
                        TAG,
                        "乐园兑换💸[$spuName]停止:" + PropStatus.valueOf(itemStatus)
                            .nickName()
                    )
                    if (PropStatus.REACH_LIMIT.name == itemStatus) {
                        Status.setFlagToday("farm::paradiseCoinExchangeLimit::$spuId")
                    }
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "isItemExchange err:",t)
        }
        return false
    }

    private fun animalSleepAndWake() {
        try {
            val sleepTimeStr = sleepTime!!.value
            if ("-1" == sleepTimeStr) {
                Log.runtime(TAG, "当前已关闭小鸡睡觉")
                return
            }
            val now = TimeUtil.getNow()
            val animalSleepTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(sleepTimeStr)
            if (animalSleepTimeCalendar == null) {
                Log.record(TAG, "小鸡睡觉时间格式错误，请重新设置")
                return
            }
            val sleepMinutesInt = sleepMinutes!!.value
            val animalWakeUpTimeCalendar = animalSleepTimeCalendar.clone() as Calendar
            animalWakeUpTimeCalendar.add(Calendar.MINUTE, sleepMinutesInt)
            var animalSleepTime = animalSleepTimeCalendar.getTimeInMillis()
            var animalWakeUpTime = animalWakeUpTimeCalendar.getTimeInMillis()
            if (animalSleepTime > animalWakeUpTime) {
                Log.record(TAG, "小鸡睡觉设置有误，请重新设置")
                return
            }
            val afterSleepTime = now > animalSleepTimeCalendar
            val afterWakeUpTime = now > animalWakeUpTimeCalendar
            if (afterSleepTime && afterWakeUpTime) {
                if (!Status.canAnimalSleep()) {
                    return
                }
                Log.record(TAG, "已错过小鸡今日睡觉时间")
                return
            }

            // 检查是否是昨晚开始的觉还没睡完
            if (now.before(animalWakeUpTimeCalendar)) {
                val lastNightSleepCalendar = animalSleepTimeCalendar.clone() as Calendar
                lastNightSleepCalendar.add(Calendar.DAY_OF_MONTH, -1)
                val lastNightWakeUpCalendar = animalWakeUpTimeCalendar.clone() as Calendar
                lastNightWakeUpCalendar.add(Calendar.DAY_OF_MONTH, -1)
                if (now.after(lastNightSleepCalendar) && now.before(lastNightWakeUpCalendar)) {
                    animalSleepTime = lastNightSleepCalendar.timeInMillis
                    animalWakeUpTime = lastNightWakeUpCalendar.timeInMillis
                }
            }

            val sleepTaskId = "AS|$animalSleepTime"
            val wakeUpTaskId = "AW|$animalWakeUpTime"
            if (!hasChildTask(sleepTaskId) && !afterSleepTime) {
                val maskName = UserMap.getCurrentMaskName()
                val logIdentifier = "${maskName}小鸡睡觉计划"
                val task = ChildModelTask(
                    sleepTaskId,
                    "AS",
                    suspendRunnable = { this.animalSleepNow() },
                    animalSleepTime
                )
                task.onCompleted = { success ->
                    if (success) {
                        Log.animalStatus("$logIdentifier[${
                            TimeUtil.getCommonDate(animalSleepTime)
                        } 已执行", 1)
                    } else {
                        Log.animalStatus("$logIdentifier[${
                            TimeUtil.getCommonDate(animalSleepTime)
                        } 已取消", 1)
                    }
                }
                addChildTask(task)
                Log.record(
                    TAG,
                    "添加定时睡觉🛌[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(
                        animalSleepTime
                    ) + "]执行"
                )
                Log.animalStatus("${logIdentifier}[${
                    TimeUtil.getCommonDate(animalSleepTime)}]",24)
            } else if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus){
                Log.animalStatus("${UserMap.getCurrentMaskName()}小鸡正在睡觉${TimeUtil.getCommonDate(System.currentTimeMillis())}",10)
            }
            if (!hasChildTask(wakeUpTaskId) && !afterWakeUpTime) {
                val maskName = UserMap.getCurrentMaskName()
                val logIdentifier = "${maskName}小鸡起床计划"
                val task = ChildModelTask(
                    wakeUpTaskId,
                    "AW",
                    suspendRunnable = { this.animalWakeUpNow() },
                    animalWakeUpTime
                )
                task.onCompleted = { success ->
                    if (success) {
                        Log.animalStatus("$logIdentifier[${
                            TimeUtil.getCommonDate(animalWakeUpTime)
                        }] 已执行", 1)
                    } else {
                        Log.animalStatus("$logIdentifier[${
                            TimeUtil.getCommonDate(animalWakeUpTime)
                        }] 已取消", 1)
                    }
                }
                addChildTask(task)
                Log.record(
                    TAG,
                    "添加定时起床🛌[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(
                        animalWakeUpTime
                    ) + "]执行"
                )
                Log.animalStatus("${logIdentifier}[${
                    TimeUtil.getCommonDate(animalWakeUpTime)}]",24)
            }
            if (afterSleepTime) {
                if (Status.canAnimalSleep()) {
                    animalSleepNow()
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG,"animalSleepAndWake err:",e)
        }
    }

    /**
     * 初始化庄园
     *
     * @return 庄园信息
     */
    private fun enterFarm(): JSONObject? {
        try {
            val userId = UserMap.currentUid
            val jo = JSONObject(AntFarmRpcCall.enterFarm(userId, userId))

            // 关键：先获取原始字符串 s
            val s = AntFarmRpcCall.enterFarm(userId, userId)
            // 打印庄园原始网页数据json到 Log.other，debug用
//            Log.other(TAG, "enterFarm 原始数据: $s")
            if (s.isNullOrEmpty()) return null

            if (ResChecker.checkRes(TAG, jo)) {
                rewardProductNum =
                    jo.getJSONObject("dynamicGlobalConfig").getString("rewardProductNum")
                val joFarmVO = jo.getJSONObject("farmVO")
                val familyInfoVO = jo.getJSONObject("familyInfoVO")
                foodStock = joFarmVO.getInt("foodStock")
                foodStockLimit = joFarmVO.getInt("foodStockLimit")
                harvestBenevolenceScore = joFarmVO.getDouble("harvestBenevolenceScore")

                parseSyncAnimalStatusResponse(joFarmVO)

                joFarmVO.getJSONObject("masterUserInfoVO").getString("userId")
                familyGroupId = familyInfoVO.optString("groupId", "")
                // 领取活动食物
                val activityData = jo.optJSONObject("activityData")
                if (activityData != null) {
                    val it = activityData.keys()
                    while (it.hasNext()) {
                        val key = it.next()
                        if (key.contains("Gifts")) {
                            val gifts = activityData.optJSONArray(key) ?: continue
                            for (i in 0..<gifts.length()) {
                                val gift = gifts.optJSONObject(i)
                                clickForGiftV2(gift)
                            }
                        }
                    }
                }
                if (useSpecialFood!!.value) { //使用特殊食品
                    val cuisineList = jo.getJSONArray("cuisineList")
                    if (AnimalFeedStatus.SLEEPY.name != ownerAnimal.animalFeedStatus) useSpecialFood(
                        cuisineList
                    )
                }

                if (jo.has("lotteryPlusInfo")) { //彩票附加信息
                    drawLotteryPlus(jo.getJSONObject("lotteryPlusInfo"))
                }

                if (acceptGift!!.value && joFarmVO.getJSONObject("subFarmVO").has("giftRecord")
                    && foodStockLimit - foodStock >= 10
                ) {
                    acceptGift()
                }
                return jo
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
        return null
    }

    /**
     * 自动喂鸡
     */
    private suspend fun handleAutoFeedAnimal() {
        if (AnimalInteractStatus.HOME.name != ownerAnimal.animalInteractStatus) {
            return  // 小鸡不在家，不执行喂养逻辑
        }

        if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
            Log.record(TAG, "投喂小鸡🥣[小鸡正在睡觉中，暂停投喂]")
            return
        }

        // 1. 判断是否有待领取的饲料
        if (receiveFarmTaskAward!!.value && unreceiveTaskAward > 0) {
            Log.record(TAG, "还有待领取的饲料")
            receiveFarmAwards() // 该步骤会自动计算饲料数量，不需要重复刷新状态
        }
        // 2. 判断是否需要喂食
        if (AnimalFeedStatus.HUNGRY.name == ownerAnimal.animalFeedStatus) {
            if (feedAnimal!!.value) {
                Log.record("小鸡在挨饿~Tk 尝试为你自动喂食")
                if (feedAnimal(ownerFarmId)) {
                    // 刷新状态
                    syncAnimalStatus(ownerFarmId)
                }
            }
        }

        // 3. 使用加饭卡（仅当正在吃饭且开启配置）
        if (useBigEaterTool!!.value && AnimalFeedStatus.EATING.name == ownerAnimal.animalFeedStatus) {
            // 若服务端已标记今日使用过（或当前有效），本地直接跳过
            if (serverUseBigEaterTool) {
                Log.record("服务端标记已使用加饭卡，跳过使用")
                // 这里可选：尝试与本地计数对齐（仅在计数为0时+1，避免重复累加）
                val today = LocalDate.now().toString()
                val uid = UserMap.currentUid
                val usedKey = "AF_BIG_EATER_USED_COUNT|$uid|$today"
                val usedCount = DataStore.get(usedKey, Int::class.java) ?: 0
                if (usedCount == 0) {
                    DataStore.put(usedKey, 1)
                }
            } else {
                // 使用 DataStore 记录“当日已用次数”，每日上限为 2 次（按账号维度）
                val today = LocalDate.now().toString()
                val uid = UserMap.currentUid
                val usedKey = "AF_BIG_EATER_USED_COUNT|$uid|$today"
                val usedCount = DataStore.get(usedKey, Int::class.java) ?: 0

                if (usedCount >= 2) {
                    Log.record("今日加饭卡已使用${usedCount}/2，跳过使用")
                } else {
                    val result = useFarmTool(ownerFarmId, ToolType.BIG_EATER_TOOL)
                    if (result) {
                        Log.farm("使用道具🎭[加饭卡]！")
                        DataStore.put(usedKey, usedCount + 1)
                        delay(1000)
                        // 刷新状态
                        syncAnimalStatus(ownerFarmId)
                    } else {
                        Log.record("⚠️使用道具🎭[加饭卡]失败，可能卡片不足或状态异常~")
                    }
                }
            }
        }

        // 4. 判断是否需要使用加速道具（仅在正在吃饭时尝试）
        if (useAccelerateTool!!.value && AnimalFeedStatus.EATING.name == ownerAnimal.animalFeedStatus) {
            // 记录调试日志：加速卡判定前的关键状态
            Log.record(
                TAG,
                "加速卡判断⏩[动物状态=" + toFeedStatusName(ownerAnimal.animalFeedStatus) +
                        ", 今日封顶=" + Status.hasFlagToday("farm::accelerateLimit") + "]"
            )
            val accelerated = useAccelerateTool()
            if (accelerated) {
                Log.farm("使用道具🎭[加速卡]⏩成功")
                // 刷新状态
                syncAnimalStatus(ownerFarmId)
            }
        }
// 5. 计算并安排下一次自动喂食任务（仅当小鸡不在睡觉时）
        if (AnimalFeedStatus.SLEEPY.name != ownerAnimal.animalFeedStatus) {
            try {
                //创建蹲点任务时间点前先同步countdown，因为可能因为好友小鸡在两次执行间隔间偷吃而引起蹲点时间变动。
                syncAnimalStatus(ownerFarmId)
                // 直接使用服务器计算的权威倒计时（单位：秒）
                val remainingSec = countdown?.toDouble()?.coerceAtLeast(0.0)
                // 如果倒计时为0，跳过任务创建
                remainingSec?.let {
                    if (it > 0) {
                        // 计算下次执行时间（毫秒）
                        val nextFeedTime = System.currentTimeMillis() + (remainingSec * 1000).toLong()
                        // 调试日志：显示服务器倒计时详情
                        Log.record(
                            TAG, "服务器倒计时🕐[小鸡状态=" + toFeedStatusName(ownerAnimal.animalFeedStatus) +
                                    ", 剩余=${remainingSec.toInt()}秒" +
                                    ", 执行时间=" + TimeUtil.getCommonDate(nextFeedTime) + "]"
                        )
                        val taskId = "FA|$ownerFarmId"
                        val maskName = UserMap.getCurrentMaskName()
                        val logIdentifier = "${maskName}蹲点喂鸡计划"
                        val task = ChildModelTask(
                            id = taskId,
                            group = "FA",
                            suspendRunnable = {
                                try {
                                    Log.record(TAG, "🔔 蹲点投喂任务触发")
                                    // 重新进入庄园，获取最新状态
                                    enterFarm()
                                    // 同步最新状态
                                    syncAnimalStatus(ownerFarmId)
                                    handleAutoFeedAnimal()
                                    Log.record(TAG, "🔄 下一次蹲点任务已创建")
                                } catch (e: Exception) {
                                    Log.error(TAG, "蹲点投喂任务执行失败: ${e.message}")
                                    Log.printStackTrace(TAG, e)
                                }
                            },
                            execTime = nextFeedTime
                        )
                        task.onCompleted = { success ->
                            if (success) {
                                Log.animalStatus("$logIdentifier[${TimeUtil.getCommonDate(nextFeedTime)}] 已执行", 1)
                            } else {
                                Log.animalStatus("${logIdentifier}[${TimeUtil.getCommonDate(nextFeedTime)}] 已取消", 1)
                            }
                        }
                        addChildTask(task)
                        Log.record(
                            TAG,
                            "添加蹲点投喂🥣[" + UserMap.getCurrentMaskName() + "]在[" +
                                    TimeUtil.getCommonDate(nextFeedTime) + "]执行"
                        )
                        Log.farm(maskName + "小鸡的蹲点投喂时间[" + TimeUtil.getCommonDate(nextFeedTime)+"]")
                        Log.animalStatus("${logIdentifier}[${TimeUtil.getCommonDate(nextFeedTime)}]",10)
                        if(remainingSec > 0) setAlarm("FA", (remainingSec * 1000).toLong())
                    } else {
                        Log.record(TAG, "蹲点投喂🥣[倒计时为0，开始投喂]")
                        if (feedAnimal(ownerFarmId)) {
                            // 刷新状态
                            syncAnimalStatus(ownerFarmId)
                        }
                        //handleAutoFeedAnimal()
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "创建蹲点任务失败: ${e.message}",e)
            }
        } else {
            // 小鸡在睡觉，跳过创建蹲点投喂任务
            // 注意：已存在的任务会在小鸡醒来时被新任务自动替换
            Log.record(TAG, "蹲点投喂🥣[小鸡正在睡觉，暂不安排投喂任务]")
        }

        // 6. 其他功能（换装、领取饲料）
        // 小鸡换装
        if (listOrnaments!!.value && Status.canOrnamentToday()) {
            listOrnaments()
        }
    }
    private fun animalSleepNow() {
        try {
            var s = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val sleepNotifyInfo = jo.getJSONObject("sleepNotifyInfo")
                if (sleepNotifyInfo.optBoolean("canSleep", false)) {
                    val groupId = jo.optString("groupId")
                    s = if (groupId.isNotEmpty()) {
                        AntFarmRpcCall.sleep(groupId)
                    } else {
                        AntFarmRpcCall.sleep()
                    }
                    jo = JSONObject(s)
                    if (ResChecker.checkRes(TAG, jo)) {
                        if (groupId.isNotEmpty()) {
                            Log.farm("家庭🏡小鸡睡觉🛌")
                        } else {
                            Log.farm("小鸡睡觉🛌")
                        }
                        Status.animalSleep()
                    }
                } else {
                    Log.farm("小鸡无需睡觉🛌")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "animalSleepNow err:",t)
        }
    }

    private fun animalWakeUpNow() {
        try {
            var s = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val sleepNotifyInfo = jo.getJSONObject("sleepNotifyInfo")
                if (!sleepNotifyInfo.optBoolean("canSleep", true)) {
                    s = AntFarmRpcCall.wakeUp()
                    jo = JSONObject(s)
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡起床 🛏")
                    }
                } else {
                    Log.farm("小鸡无需起床 🛏")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "animalWakeUpNow err:",t)
        }
    }

    /**
     * 同步小鸡状态通用方法
     *
     * @param farmId 庄园id
     */
    private fun syncAnimalStatus(
        farmId: String?,
        operTag: String?,
        operateType: String?
    ): JSONObject? {
        try {
            val s = AntFarmRpcCall.syncAnimalStatus(farmId, operTag, operateType)
            // 打印庄园原始网页json数据到 Log.other ,debug用
//            Log.other(TAG, "syncAnimalStatus 原始数据: $s")
            return JSONObject(s)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            return null
        }
    }

    private fun syncAnimalStatus(farmId: String?) {
        try {
            val jo = syncAnimalStatus(farmId, "SYNC_RESUME", "QUERY_ALL")
            parseSyncAnimalStatusResponse(jo!!)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncAnimalStatus err:", t)
        }
    }

    private fun syncAnimalStatusAfterFeedAnimal(farmId: String?): JSONObject? {
        try {
            return syncAnimalStatus(
                farmId,
                "SYNC_AFTER_FEED_ANIMAL",
                "QUERY_EMOTION_INFO|QUERY_ORCHARD_RIGHTS"
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
        return null
    }

    private fun syncAnimalStatusQueryFamilyAnimals(farmId: String?): JSONObject? {
        try {
            return syncAnimalStatus(farmId, "SYNC_RESUME_FAMILY", "QUERY_ALL|QUERY_FAMILY_ANIMAL")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
        return null
    }


    private fun syncAnimalStatusAtOtherFarm(userId: String?, friendUserId: String?) {
        try {
            val s = AntFarmRpcCall.enterFarm(userId, friendUserId)
            var jo = JSONObject(s)
            Log.runtime(TAG, "DEBUG$jo")
            jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO")
            val jaAnimals = jo.getJSONArray("animals")
            for (i in 0..<jaAnimals.length()) {
                val jaAnimaJson = jaAnimals.getJSONObject(i)
                if (jaAnimaJson.getString("masterFarmId") == ownerFarmId) { // 过滤出当前用户的小鸡
                    val animal = jaAnimals.getJSONObject(i)
                    ownerAnimal =
                        objectMapper.readValue(animal.toString(), Animal::class.java)
                    break
                }
            }
        } catch (j: JSONException) {
            Log.printStackTrace(TAG, "syncAnimalStatusAtOtherFarm err:", j)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncAnimalStatusAtOtherFarm err:", t)
        }
    }

    private fun rewardFriend() {
        try {
            if (rewardList != null) {
                for (rewardFriend in rewardList) {
                    val s = AntFarmRpcCall.rewardFriend(
                        rewardFriend.consistencyKey, rewardFriend.friendId,
                        rewardProductNum, rewardFriend.time
                    )
                    val jo = JSONObject(s)
                    val memo = jo.getString("memo")
                    if (ResChecker.checkRes(TAG, jo)) {
                        val rewardCount = benevolenceScore - jo.getDouble("farmProduct")
                        benevolenceScore -= rewardCount
                        Log.farm(
                            String.format(
                                Locale.CHINA,
                                "打赏好友💰[%s]# 得%.2f颗爱心鸡蛋",
                                UserMap.getMaskName(rewardFriend.friendId),
                                rewardCount
                            )
                        )
                    } else {
                        Log.record(memo)
                        Log.runtime(s)
                    }
                }
                rewardList = null
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG,"rewardFriend err:", t)
        }
    }

    private fun recallAnimal(
        animalId: String?,
        currentFarmId: String?,
        masterFarmId: String?,
        user: String?
    ) {
        try {
            val s = AntFarmRpcCall.recallAnimal(animalId, currentFarmId, masterFarmId)
            val jo = JSONObject(s)
            val memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val foodHaveStolen = jo.getDouble("foodHaveStolen")
                Log.farm("召回小鸡📣，偷吃[" + user + "]#" + foodHaveStolen + "g")
                // 这里不需要加
                // add2FoodStock((int)foodHaveStolen);
            } else {
                Log.record(memo)
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "recallAnimal err:",t)
        }
    }

    private fun sendBackAnimal() {
        if (animals == null) {
            return
        }
        try {
            for (animal in animals) {
                if (AnimalInteractStatus.STEALING.name == animal.animalInteractStatus && (SubAnimalType.GUEST.name != animal.subAnimalType) && (SubAnimalType.WORK.name != animal.subAnimalType)) {
                    // 赶鸡
                    var user = AntFarmRpcCall.farmId2UserId(animal.masterFarmId)
                    var isSendBackAnimal = sendBackAnimalList!!.value.contains(user)
                    if (sendBackAnimalType!!.value == SendBackAnimalType.BACK) {
                        isSendBackAnimal = !isSendBackAnimal
                    }
                    if (isSendBackAnimal) {
                        continue
                    }
                    val sendTypeInt = sendBackAnimalWay!!.value
                    user = UserMap.getMaskName(user)
                    var s = AntFarmRpcCall.sendBackAnimal(
                        SendBackAnimalWay.nickNames[sendTypeInt],
                        animal.animalId,
                        animal.currentFarmId,
                        animal.masterFarmId
                    )
                    val jo = JSONObject(s)
                    val memo = jo.getString("memo")
                    if (ResChecker.checkRes(TAG, jo)) {
                        if (sendTypeInt == SendBackAnimalWay.HIT) {
                            if (jo.has("hitLossFood")) {
                                s =
                                    "胖揍小鸡🤺[" + user + "]，掉落[" + jo.getInt("hitLossFood") + "g]"
                                if (jo.has("finalFoodStorage")) foodStock =
                                    jo.getInt("finalFoodStorage")
                            } else s = "[$user]的小鸡躲开了攻击"
                        } else {
                            s = "驱赶小鸡🧶[$user]"
                        }
                        Log.farm(s)
                    } else {
                        Log.record(memo)
                        Log.runtime(s)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "sendBackAnimal err:",t)
        }
    }

    private fun receiveToolTaskReward() {
        try {
            var s = AntFarmRpcCall.listToolTaskDetails()
            var jo = JSONObject(s)
            var memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val jaList = jo.getJSONArray("list")
                for (i in 0..<jaList.length()) {
                    val joItem = jaList.getJSONObject(i)
                    if (joItem.has("taskStatus")
                        && TaskStatus.FINISHED.name == joItem.getString("taskStatus")
                    ) {
                        val bizInfo = JSONObject(joItem.getString("bizInfo"))
                        val awardType = bizInfo.getString("awardType")
                        val toolType = ToolType.valueOf(awardType)
                        var isFull = false
                        for (farmTool in farmTools) {
                            if (farmTool.toolType == toolType) {
                                if (farmTool.toolCount == farmTool.toolHoldLimit) {
                                    isFull = true
                                }
                                break
                            }
                        }
                        if (isFull) {
                            Log.record(TAG, "领取道具[" + toolType.nickName() + "]#已满，暂不领取")
                            continue
                        }
                        val awardCount = bizInfo.getInt("awardCount")
                        val taskType = joItem.getString("taskType")
                        val taskTitle = bizInfo.getString("taskTitle")
                        s = AntFarmRpcCall.receiveToolTaskReward(awardType, awardCount, taskType)
                        jo = JSONObject(s)
                        memo = jo.getString("memo")
                        if (ResChecker.checkRes(TAG, jo)) {
                            Log.farm("领取道具🎖️[" + taskTitle + "-" + toolType.nickName() + "]#" + awardCount + "张")
                        } else {
                            memo = memo.replace("道具", toolType.nickName().toString())
                            Log.record(memo)
                            Log.runtime(s)
                        }
                    }
                }
            } else {
                Log.record(memo)
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveToolTaskReward err:",t)
        }
    }

    private fun harvestProduce(farmId: String?) {
        try {
            val s = AntFarmRpcCall.harvestProduce(farmId)
            val jo = JSONObject(s)
            val memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val harvest = jo.getDouble("harvestBenevolenceScore")
                harvestBenevolenceScore = jo.getDouble("finalBenevolenceScore")
                Log.farm("收取鸡蛋🥚[" + harvest + "颗]#剩余" + harvestBenevolenceScore + "颗")
            } else {
                Log.record(memo)
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "harvestProduce err:",t)
        }
    }

    /* 捐赠爱心鸡蛋 */
    private fun handleDonation(donationType: Int) {
        try {
            val s = AntFarmRpcCall.listActivityInfo()
            var jo = JSONObject(s)
            val memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val jaActivityInfos = jo.getJSONArray("activityInfos")
                var activityId: String? = null
                var activityName: String?
                var isDonation = false
                for (i in 0..<jaActivityInfos.length()) {
                    jo = jaActivityInfos.getJSONObject(i)
                    if (jo.get("donationTotal") != jo.get("donationLimit")) {
                        activityId = jo.getString("activityId")
                        activityName = jo.optString("projectName", activityId)
                        if (performDonation(activityId, activityName)) {
                            isDonation = true
                            if (donationType == DonationCount.ONE) {
                                break
                            }
                        }
                    }
                }
                if (isDonation) {
                    val userId = UserMap.currentUid
                    Status.donationEgg(userId)
                }
                if (activityId == null) {
                    Log.record(TAG, "今日已无可捐赠的活动")
                }
            } else {
                Log.record(memo)
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "donation err:",t)
        }
    }

    private fun performDonation(activityId: String?, activityName: String?): Boolean {
        try {
            val s = AntFarmRpcCall.donation(activityId, 1)
            val donationResponse = JSONObject(s)
            val memo = donationResponse.getString("memo")
            if (ResChecker.checkRes(TAG, donationResponse)) {
                val donationDetails = donationResponse.getJSONObject("donation")
                harvestBenevolenceScore = donationDetails.getDouble("harvestBenevolenceScore")
                Log.farm("捐赠活动❤️[" + activityName + "]#累计捐赠" + donationDetails.getInt("donationTimesStat") + "次")
                return true
            } else {
                Log.record(memo)
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(t)
        }
        return false
    }

    @Suppress("SameParameterValue")
    private fun answerQuestion(activityId: String?) {
        try {
            val today = TimeUtil.getDateStr2()
            val tomorrow = TimeUtil.getDateStr2(1)
            val farmAnswerCache = DataStore.getOrCreate<MutableMap<String, String>>(FARM_ANSWER_CACHE_KEY) as MutableMap<String, String>
            cleanOldAnswers(farmAnswerCache, today)
            // 检查是否今天已经答过题
            if (Status.hasFlagToday(ANSWERED_FLAG)) {
                if (!Status.hasFlagToday(CACHED_FLAG)) {
                    val jo = JSONObject(DadaDailyRpcCall.home(activityId))
                    if (ResChecker.checkRes(TAG + "查询答题活动失败:", jo)) {
                        val operationConfigList = jo.getJSONArray("operationConfigList")
                        updateTomorrowAnswerCache(operationConfigList, tomorrow)
                        Status.setFlagToday(CACHED_FLAG)
                    }
                }
                return
            }

            // 获取题目信息
            val jo = JSONObject(DadaDailyRpcCall.home(activityId))
            if (!ResChecker.checkRes(TAG + "获取答题题目失败:", jo)) return

            val question = jo.getJSONObject("question")
            val questionId = question.getLong("questionId")
            val labels = question.getJSONArray("label")
            val title = question.getString("title")

            var answer: String? = null
            var cacheHit = false
            val cacheKey = "$title|$today"

            // 改进的缓存匹配逻辑
            if (farmAnswerCache.containsKey(cacheKey)) {
                val cachedAnswer = farmAnswerCache[cacheKey]
                Log.farm("🎉 缓存[$cachedAnswer] 🎯 题目：$cacheKey")

                // 1. 首先尝试精确匹配
                for (i in 0..<labels.length()) {
                    val option = labels.getString(i)
                    if (option == cachedAnswer) {
                        answer = option
                        cacheHit = true
                        break
                    }
                }

                // 2. 如果精确匹配失败，尝试模糊匹配
                if (!cacheHit && cachedAnswer != null) {
                    for (i in 0..<labels.length()) {
                        val option = labels.getString(i)
                        if (option.contains(cachedAnswer) || cachedAnswer.contains(option)) {
                            answer = option
                            cacheHit = true
                            Log.farm("⚠️ 缓存模糊匹配成功：$cachedAnswer → $option")
                            break
                        }
                    }
                }
            }

            // 缓存未命中时调用AI
            if (!cacheHit) {
                Log.record(TAG, "缓存未命中，尝试使用AI答题：$title")
                answer = AnswerAI.getAnswer(title, JsonUtil.jsonArrayToList(labels), "farm")
                if (answer == null || answer.isEmpty()) {
                    answer = labels.getString(0) // 默认选择第一个选项
                }
            }

            // 提交答案
            val joDailySubmit = JSONObject(DadaDailyRpcCall.submit(activityId, answer, questionId))
            Status.setFlagToday(ANSWERED_FLAG)
            if (ResChecker.checkRes(TAG + "提交答题答案失败:", joDailySubmit)) {
                val extInfo = joDailySubmit.getJSONObject("extInfo")
                val correct = joDailySubmit.getBoolean("correct")
                Log.farm("饲料任务答题：" + (if (correct) "正确" else "错误") + "领取饲料［" + extInfo.getString("award") + "g］")
                val operationConfigList = joDailySubmit.getJSONArray("operationConfigList")
                updateTomorrowAnswerCache(operationConfigList, tomorrow)
                Status.setFlagToday(CACHED_FLAG)
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "答题出错", e)
        }
    }

    /**
     * 更新明日答案缓存
     *
     * @param operationConfigList 操作配置列表
     * @param date                日期字符串，格式 "yyyy-MM-dd"
     */
    private fun updateTomorrowAnswerCache(operationConfigList: JSONArray, date: String?) {
        try {
            Log.runtime(TAG, "updateTomorrowAnswerCache 开始更新缓存")
            val farmAnswerCache = DataStore.getOrCreate<MutableMap<String, String>>(FARM_ANSWER_CACHE_KEY)
            for (j in 0..<operationConfigList.length()) {
                val operationConfig = operationConfigList.getJSONObject(j)
                val type = operationConfig.getString("type")
                if ("PREVIEW_QUESTION" == type) {
                    val previewTitle = operationConfig.getString("title") + "|" + date
                    val actionTitle = JSONArray(operationConfig.getString("actionTitle"))
                    for (k in 0..<actionTitle.length()) {
                        val joActionTitle = actionTitle.getJSONObject(k)
                        val isCorrect = joActionTitle.getBoolean("correct")
                        if (isCorrect) {
                            val nextAnswer = joActionTitle.getString("title")
                            farmAnswerCache[previewTitle] = nextAnswer // 缓存下一个问题的答案
                        }
                    }
                }
            }
            DataStore.put(FARM_ANSWER_CACHE_KEY, farmAnswerCache)
            Log.runtime(TAG, "updateTomorrowAnswerCache 缓存更新完毕")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "updateTomorrowAnswerCache 错误:", e)
        }
    }


    /**
     * 清理缓存超过7天的B答案
     */
    private fun cleanOldAnswers(farmAnswerCache: MutableMap<String, String>?, today: String?) {
        try {
            Log.runtime(TAG, "cleanOldAnswers 开始清理缓存")
            if (farmAnswerCache == null || farmAnswerCache.isEmpty()) return
            // 将今天日期转为数字格式：20250405
            val todayInt = convertDateToInt(today) // 如 "2025-04-05" → 20250405
            // 设置保留天数（例如7天）
            val daysToKeep = 7
            val cleanedMap: MutableMap<String?, String?> = HashMap()
            for (entry in farmAnswerCache.entries) {
                val key: String = entry.key
                if (key.contains("|")) {
                    val parts: Array<String?> = key.split("\\|".toRegex(), limit = 2).toTypedArray()
                    if (parts.size == 2) {
                        val dateStr = parts[1] //获取日期部分 20
                        val dateInt = convertDateToInt(dateStr)
                        if (dateInt == -1) continue
                        if (todayInt - dateInt <= daysToKeep) {
                            cleanedMap[entry.key] = entry.value //保存7天内的答案
                            Log.runtime(TAG, "保留 日期：" + todayInt + "缓存日期：" + dateInt + " 题目：" + parts[0])
                        }
                    }
                }
            }
            DataStore.put(FARM_ANSWER_CACHE_KEY, cleanedMap)
            Log.runtime(TAG, "cleanOldAnswers 清理缓存完毕")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "cleanOldAnswers error:", e)
        }
    }


    /**
     * 将日期字符串转为数字格式
     *
     * @param dateStr 日期字符串，格式 "yyyy-MM-dd"
     * @return 日期数字格式，如 "2025-04-05" → 20250405
     */
    private fun convertDateToInt(dateStr: String?): Int {
        Log.runtime(TAG, "convertDateToInt 开始转换日期：$dateStr")
        if (dateStr == null || dateStr.length != 10 || dateStr[4] != '-' || dateStr[7] != '-') {
            Log.error("日期格式错误：$dateStr")
            return -1 // 格式错误
        }
        try {
            val year = dateStr.take(4).toInt()
            val month = dateStr.substring(5, 7).toInt()
            val day = dateStr.substring(8, 10).toInt()
            if (month !in 1..12 || day < 1 || day > 31) {
                Log.error("日期无效：$dateStr")
                return -1 // 日期无效
            }
            return year * 10000 + month * 100 + day
        } catch (e: NumberFormatException) {
            Log.error(TAG, "日期转换失败：" + dateStr + e.message)
            return -1
        }
    }


    private suspend fun recordFarmGame(gameType: GameType) {
        try {
            do {
                try {
                    var jo = JSONObject(AntFarmRpcCall.initFarmGame(gameType.name))
                    if (ResChecker.checkRes(TAG, jo)) {
                        if (jo.getJSONObject("gameAward").getBoolean("level3Get")) {
                            return
                        }
                        if (jo.optInt("remainingGameCount", 1) == 0) {
                            return
                        }
                        jo = JSONObject(AntFarmRpcCall.recordFarmGame(gameType.name))
                        if (ResChecker.checkRes(TAG, jo)) {
                            val awardInfos = jo.getJSONArray("awardInfos")
                            val award = StringBuilder()
                            for (i in 0..<awardInfos.length()) {
                                val awardInfo = awardInfos.getJSONObject(i)
                                award.append(awardInfo.getString("awardName")).append("*")
                                    .append(awardInfo.getInt("awardCount"))
                            }
                            if (jo.has("receiveFoodCount")) {
                                award.append(";肥料*").append(jo.getString("receiveFoodCount"))
                            }
                            Log.farm("庄园游戏🎮[" + gameType.gameName() + "]#" + award)
                            if (jo.optInt("remainingGameCount", 0) > 0) {
                                continue
                            }
                        } else {
                            Log.runtime(TAG, "庄园游戏$jo")
                        }
                    } else {
                        Log.runtime(TAG, "进入庄园游戏失败$jo")
                    }
                    break
                } finally {
                    delay(2000)
                }
            } while (true)
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "recordFarmGame 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "recordFarmGame err:",t)
        }
    }

    /**
     * 庄园任务，目前支持i
     * 视频，杂货铺，抽抽乐，家庭，618会场，芭芭农场，小鸡厨房
     * 添加组件，雇佣，会员签到，逛咸鱼，今日头条极速版，UC浏览器
     * 一起拿饲料，到店付款，线上支付，鲸探
     */
    private suspend fun doFarmTasks() {
        try {
            val jo = JSONObject(AntFarmRpcCall.listFarmTask())
            if (!ResChecker.checkRes(TAG, jo)) return
            val farmTaskList = jo.getJSONArray("farmTaskList")
            for (i in 0 until farmTaskList.length()) {
                val task = farmTaskList.getJSONObject(i)
                val title = task.optString("title", "未知任务")
                val taskStatus = task.getString("taskStatus")
                val bizKey = task.getString("bizKey")

              //  val taskMode = task.optString("taskMode")
              //  if(taskMode=="TRIGGER")     continue                 //跳过事件任务

                // 1. 预检查：黑名单与每日上限
                // 检查任务标题和业务键是否在黑名单中
                val titleInBlacklist = TaskBlacklist.isTaskInBlacklist(title)
                val bizKeyInBlacklist = TaskBlacklist.isTaskInBlacklist(bizKey)
                Log.debug(TAG, "庄园任务检查 - 标题: $title, 业务键: $bizKey, 标题在黑名单: $titleInBlacklist, 业务键在黑名单: $bizKeyInBlacklist")
                if (titleInBlacklist || bizKeyInBlacklist) {
                    Log.debug(TAG, "跳过黑名单任务: $title ($bizKey)")
                    continue
                }

                if (Status.hasFlagToday("farm::task::limit::$bizKey")) continue
                // 2. 执行 TODO 任务
                if (TaskStatus.TODO.name == taskStatus) {
                    when (bizKey) {
                        "VIDEO_TASK" -> {
                            // --- 视频任务专项逻辑 ---
                            Log.record(TAG, "开始处理视频任务: $title ($bizKey)")
                            handleVideoTask(bizKey, title)
                        }
                        "ANSWER" -> {
                            // --- 答题任务专项逻辑 ---
                            if (!Status.hasFlagToday(CACHED_FLAG)) {
                                answerQuestion("100")
                            }
                        }
                        else -> {
                            // --- 普通任务通用逻辑 ---
                            Log.record(TAG, "开始处理庄园任务: $title ($bizKey)")
                            handleGeneralTask(bizKey, title)
                        }
                    }
                }else{
                    Log.debug(TAG, "跳过非TODO任务: $title ($bizKey) 状态: $taskStatus")
                }
                // 3. 额外处理某些即便不是 TODO 状态也可能需要检查的任务（如答题补漏）
                if ("ANSWER" == bizKey && !Status.hasFlagToday(CACHED_FLAG)) {
                    answerQuestion("100")
                }
                delay(2000) // 任务间间隔，防止频率过快
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doFarmTasks 错误:", t)
        }
    }

    // 抽取视频处理逻辑，避免嵌套过深
    private suspend fun handleVideoTask(bizKey: String, title: String) {
        val res = AntFarmRpcCall.queryTabVideoUrl()
        val jo = JSONObject(res)
        if (ResChecker.checkRes(TAG, jo)) {
            val videoUrl = jo.getString("videoUrl")
            // 建议增加 contentId 提取的安全性检查
            try {
                val contentId = videoUrl.substring(
                    videoUrl.indexOf("&contentId=") + 11,
                    videoUrl.indexOf("&refer")
                )
                if (ResChecker.checkRes(TAG, JSONObject(AntFarmRpcCall.videoDeliverModule(contentId)))) {
                    delay(15000L) // 模拟观看视频
                    if (ResChecker.checkRes(TAG, JSONObject(AntFarmRpcCall.videoTrigger(contentId)))) {
                        Log.farm("庄园视频任务确认成功🧾[$title]")
                    }
                }
            } catch (e: Exception) {
                Log.error(TAG, "解析视频ID失败: $title")
            }
        }
    }

    // 抽取通用任务处理逻辑
    private fun handleGeneralTask(bizKey: String, title: String) {
        val result = AntFarmRpcCall.doFarmTask(bizKey)
        if (result.isNullOrEmpty()) return

        val jo = JSONObject(result)
        if (ResChecker.checkRes(TAG, jo)) {
            Log.farm("庄园任务完成🧾[$title]")
        } else {
            val resultCode = jo.optString("resultCode", "")
            if (resultCode == "309") {
                Status.setFlagToday("farm::task::limit::$bizKey")
                Log.record(TAG, "庄园任务[$title]已达上限")
            } else {
                Log.error("庄园任务失败：$title code:$resultCode")
                TaskBlacklist.autoAddToBlacklist(bizKey, title, resultCode)
            }
        }
    }

    private suspend fun receiveFarmAwards() {
        try {
            var doubleCheck: Boolean
            var isFeedFull = false // 添加饲料槽已满的标志
            do {
                doubleCheck = false
                val response = AntFarmRpcCall.listFarmTask()
                // 检查空响应
                if (response.isNullOrEmpty()) {
                    Log.runtime(TAG, "receiveFarmAwards: 收到空响应，跳过本次执行")
                    return
                }
                val jo = JSONObject(response)
                if (ResChecker.checkRes(TAG + "查询庄园任务失败:", jo)) {
                    val farmTaskList = jo.getJSONArray("farmTaskList")
                    val signList = jo.getJSONObject("signList")
                    farmSign(signList)

                    for (i in 0..<farmTaskList.length()) {
                        // 如果饲料槽已满，跳过后续任务的领取
                        val task = farmTaskList.getJSONObject(i)
                        val taskStatus = task.getString("taskStatus")
                        val taskTitle = task.optString("title", "未知任务")
                        val awardCount = task.optInt("awardCount", 0)
                        val taskId = task.optString("taskId")

                        if (TaskStatus.FINISHED.name == taskStatus) {
                            // 领取前先同步一次食槽状态，避免边界误差
                            syncAnimalStatus(ownerFarmId)

                            val foodStockAfter = foodStock + awardCount
                            if ("ALLPURPOSE" == task.optString("awardType")) {
                                // 使用 ">=" 防止刚好到上限时仍然领取导致 331
                                if ((foodStock >= foodStockLimit) || ((awardCount + foodStock > foodStockLimit) && !TimeUtil.isNowAfterOrCompareTimeStr("2000")) ) {
                                    unreceiveTaskAward++
                                    Log.record(
                                        TAG,
                                        "领取任务：" + taskTitle + " 的 " + awardCount + "g饲料后将超过[" + foodStockLimit + "g]上限!终止领取。现有饲料" + foodStock +"g"
                                    )
                                    isFeedFull = true
                                    break
                                }
                            }
                            val receiveTaskAwardjo =
                                JSONObject(AntFarmRpcCall.receiveFarmTaskAward(taskId))
                            if (ResChecker.checkRes(TAG + "领取庄园任务奖励失败:", receiveTaskAwardjo)) {
                                add2FoodStock(awardCount)
                                Log.farm("收取庄园任务奖励[" + taskTitle + "]#" + awardCount + "g")
                                if(foodStock == foodStockLimit){
                                    Log.farm("领取饲料[等于]饲料上限" + foodStockLimit + "g，停止后续领取")
                                    break
                                }
                                if(foodStockAfter > foodStockLimit){
                                    Log.farm("时间超过20点，即使领取后将[超过]饲料上限仍将领取饲料奖励。饲料已到上限" + foodStockLimit + "g，停止后续领取")
                                    break
                                }
                                doubleCheck = true
                                if (unreceiveTaskAward > 0) unreceiveTaskAward--
                            }
                            else {
                                // 捕获饲料槽已满（331），设置满槽标记并停止后续领取
                                val resultCode = receiveTaskAwardjo.optString("resultCode", "")
                                val memo = receiveTaskAwardjo.optString("memo", "")
                                if ("331" == resultCode || memo.contains("饲料槽已满")) {
                                    Log.record(TAG, "领取失败：饲料槽已满，停止后续领取")
                                    isFeedFull = true
                                    break
                                } else {
                                    Log.error(TAG, "领取庄园任务奖励失败：$receiveTaskAwardjo")
                                }
                            }
                        }
                        delay(1000)
                    }
                }
            } while (doubleCheck && !isFeedFull) // 如果饲料槽已满，不再进行双重检查
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "receiveFarmAwards 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveFarmAwards 错误:", t)
        }
    }

    private fun farmSign(signList: JSONObject) {
        try {
            val flag = "farm::sign"
            if (Status.hasFlagToday(flag)) return
            val jaFarmSignList = signList.getJSONArray("signList")
            val currentSignKey = signList.getString("currentSignKey")
            for (i in 0..<jaFarmSignList.length()) {
                val jo = jaFarmSignList.getJSONObject(i)
                val signKey = jo.getString("signKey")
                val signed = jo.getBoolean("signed")
                val awardCount = jo.getString("awardCount")
                if (currentSignKey == signKey) {
                    if (!signed) {
                        val signResponse = AntFarmRpcCall.sign()
                        if (ResChecker.checkRes(TAG, signResponse)) {
                            Log.farm("庄园签到📅获得饲料" + awardCount + "g")
                            Status.setFlagToday(flag)
                        }
                    }
                    return
                }
            }
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "庄园签到 JSON解析错误:", e)
        }
    }

    /**
     * 喂鸡
     *
     * @param farmId 庄园ID
     * @return true: 喂鸡成功，false: 喂鸡失败
     */
    private fun feedAnimal(farmId: String?): Boolean {
        try {
            // 检查小鸡是否在睡觉，如果在睡觉则直接返回
            if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
                Log.record(TAG, "投喂小鸡🥣[小鸡正在睡觉中，跳过投喂]")
                return false
            }


            // 检查小鸡是否正在吃饭，如果在吃饭则直接返回
            // EATING: 小鸡正在进食状态，此时不能重复投喂，会返回"不要着急，还没吃完呢"错误
            if (AnimalFeedStatus.EATING.name == ownerAnimal.animalFeedStatus) {
                Log.record(TAG, "投喂小鸡🥣[小鸡正在吃饭中，跳过投喂]")
                return false
            }

            if (foodStock < 180) {
                Log.record(TAG, "喂鸡饲料不足，停止本次投喂尝试")
                return false // 明确返回 false
            } else {
                val jo = JSONObject(AntFarmRpcCall.feedAnimal(farmId))
                if (ResChecker.checkRes(TAG, jo)) {
                    // 安全获取foodStock字段，如果不存在则显示未知
                    val remainingFood = jo.optInt("foodStock", -1)
                    if (remainingFood >= 0) {
                        Log.farm("投喂小鸡🥣[180g]#剩余" + remainingFood + "g")
                    } else {
                        Log.farm("投喂小鸡🥣[180g]#投喂成功")
                    }

                    try {
                        val taskId = "KC|$ownerFarmId"
                        val maskName = UserMap.getCurrentMaskName()
                        val logIdentifier = "${maskName}蹲点赶鸡计划"
                        val KcTime = TimeUtil.getCommonDate(System.currentTimeMillis() + 30 * 60 * 1000L)
                        val task = ChildModelTask(
                            id = taskId,
                            group = "KC",
                            suspendRunnable = {
                                try {
                                    Log.record(TAG, "🔔 蹲点赶鸡任务触发")
                                    // 重新进入庄园，获取最新状态
                                    enterFarm()
                                    // 同步最新状态
                                    syncAnimalStatus(ownerFarmId)
                                    sendBackAnimal()
                                } catch (e: Exception) {
                                    Log.error(TAG, "蹲点赶鸡任务执行失败: ${e.message}")
                                    Log.printStackTrace(TAG, e)
                                }
                            },
                            execTime = System.currentTimeMillis() + 30 * 60 * 1000L  //30分钟
                        )
                        // 设置完成回调
                        task.onCompleted = { success ->
                            if (success) {
                                Log.animalStatus("$logIdentifier[${KcTime}] 已执行", 1)
                            } else {
                                // 如果任务被取消或报错
                                Log.animalStatus("$logIdentifier[${KcTime}] 已取消", 1)
                            }
                        }
                        addChildTask(task)


                        Log.farm(UserMap.getCurrentMaskName() + "30分钟后${KcTime}蹲点赶小鸡")
                        Log.animalStatus("${logIdentifier}[${KcTime}]执行",1)
                        setAlarm("KC", 30 * 60 * 1000L)

                    } catch (e: Exception) {
                        Log.printStackTrace(TAG, "创建蹲点赶鸡失败: ${e.message}",e)
                    }

                    return true
                } else {
                    // 检查特定的错误码
                    val resultCode = jo.optString("resultCode", "")
                    val memo = jo.optString("memo", "")
                    if ("311" == resultCode) {
                        Log.record(TAG, "投喂小鸡🥣[$memo]")
                    } else {
                        Log.runtime(TAG, "投喂小鸡失败: $jo")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "feedAnimal err:", t)
        }
        return false
    }

    /**
     * 加载持有道具信息
     */
    private fun listFarmTool() {
        try {
            var jo = JSONObject(AntFarmRpcCall.listFarmTool())
            if (ResChecker.checkRes(TAG, jo)) {
                val jaToolList = jo.getJSONArray("toolList")
                val tempList = mutableListOf<FarmTool>()
                for (i in 0..<jaToolList.length()) {
                    jo = jaToolList.getJSONObject(i)
                    val tool = FarmTool()
                    tool.toolId = jo.optString("toolId", "")
                    tool.toolType = ToolType.valueOf(jo.getString("toolType"))
                    tool.toolCount = jo.getInt("toolCount")
                    tool.toolHoldLimit = jo.optInt("toolHoldLimit", 20)
                    tempList.add(tool)
                }
                farmTools = tempList.toTypedArray()
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "listFarmTool err:", t)
        }
    }

    /**
     * 连续使用加速卡
     *
     * @return true: 使用成功，false: 使用失败
     */
    private suspend fun useAccelerateTool(): Boolean {
        // 1) 基础开关：外部配置或全局状态限制
        if (!Status.canUseAccelerateTool()) {
            return false
        }
        // 2) 业务上限：命中“今日已达加速上限”标记则直接返回
        if (Status.hasFlagToday("farm::accelerateLimit")) {
            return false
        }
        // 3) 单次/连续逻辑：当未开启“连续使用”且当前已有加速Buff，则不再使用
        if (!useAccelerateToolContinue!!.value && AnimalBuff.ACCELERATING.name == ownerAnimal.animalBuff) {
            return false
        }
        // 4) 同步最新状态，确保消耗速度、已吃量、食槽上限为最新
        syncAnimalStatus(ownerFarmId)

        val currentCountdown = countdown?.toDouble() ?: 0.0
        if (currentCountdown <= 0) return false

        var totalConsumeSpeed = 0.0
        var totalFoodHaveEatten = 0.0
//        val nowTime = System.currentTimeMillis() / 1000
        // 5) 计算“总已吃量（含时间增量）”与“总消耗速度”
        /* 小鸡已经吃的食物参数是foodHaveStolen
            使用加速卡后所吃的饲料算在foodHaveEatten里，foodHaveEatten即使不使用加速卡也会有个随机？的0.1左右的值，也就是非0
            小鸡起床后startEatTime（含日期参数的时间）会重新变更为起床的时间，比如6：00起床，而喂食时间实际是昨晚的20：00,startEatTime=20：00,然后小鸡睡觉
            6：00起床，再获取startEatTime则为6：00
            因此剩余饲料量应该使用countdown来进行计算，这是准确的。
         */
        for (animal in animals!!) {
            totalFoodHaveEatten += animal.foodHaveStolen!!
            totalFoodHaveEatten += animal.foodHaveEatten!!  // 将加速卡使用的饲料加入总消耗，totalFoodHaveEatten则为小鸡自己吃的加上使用加速卡消耗掉的
//            totalFoodHaveEatten += animal.consumeSpeed!! * (nowTime - animal.startEatTime!!.toDouble() / 1000)
            totalConsumeSpeed += animal.consumeSpeed!!
        }
        // 自己的小鸡每小时消耗的饲料g数
        val  foodConsumePerHour = ownerAnimal.consumeSpeed!! * 60 * 60
        Log.record(
            TAG,
            "加速卡内部计算⏩[totalConsumeSpeed=$totalConsumeSpeed, totalFoodHaveEatten=$totalFoodHaveEatten, limit=$foodInTroughLimitCurrent]"
        )
        if (totalConsumeSpeed <= 0) return false
        // 6) 判定条件：至少还能吃满 1 小时（3600 秒）的量才使用一次加速卡
        //    单位换算：consumeSpeed 单位为 g/s，因此 1 小时的消耗 = totalConsumeSpeed * 3600
        ////修改为剩余时间大于自定义remainingTime分钟则使用加速卡,仅考虑饲料剩余时间，多只鸡时存在误差,逻辑会出现问题
        var isUseAccelerateTool = false
        var remainingTimeValue = remainingTime.value
        if (remainingTimeValue !in 1..<60){
            remainingTimeValue = 60
            Log.farm("连续使用加速卡加速的剩余时间设置有误，正确值1-59,现不加速剩余时间为1个小时内的饲料")
        }
//        val remainingTimeValue = remainingTime.value.coerceIn(1, 60).toDouble()
        var remainingFood = currentCountdown * totalConsumeSpeed    //foodInTroughLimitCurrent - totalFoodHaveEatten
//        while (foodInTroughLimitCurrent - totalFoodHaveEatten >= totalConsumeSpeed * remainingTimeValue * 60) {
        /* 加速卡逻辑应该是消耗自己小鸡1个小时的食物消耗量，这个应该是自己小鸡foodConsumeSpeed * 3600 (g)
            因此对于不足一个小时/指定大于剩余时间的加速应该理解为剩余饲料大于这个指定时间的自己小鸡的食物消耗量，
            这种情况下即使有多只偷吃小鸡时也可以正确的把加速卡连续使用光。
         */
        while (remainingFood >= remainingTimeValue / 60.0 * foodConsumePerHour ) {
            // 检查本地计数器上限，防止无限使用
            if (!Status.canUseAccelerateTool()) {
                Log.record(TAG, "加速卡内部⏩已达到本地使用上限(8次)，停止使用")
                Status.setFlagToday("farm::accelerateLimit")
                break
            }
            // 可选条件：若勾选“仅心情满值时加速”，且当前心情不为 100，则跳出
            if ((useAccelerateToolWhenMaxEmotion!!.value && finalScore != 100.0)) {
                break
            }
            if (useFarmTool(ownerFarmId, ToolType.ACCELERATETOOL)) {
                // 使用成功后，等效地“吃掉”未来 1 小时的饲料
//                totalFoodHaveEatten += totalConsumeSpeed * 3600
//                totalFoodHaveEatten += foodConsumePerHour
                remainingFood -= foodConsumePerHour
                isUseAccelerateTool = true
                Status.useAccelerateTool()
                val timeLeft = remainingFood / totalConsumeSpeed
                if (timeLeft >= 0.0){
                    Log.farm("使用了1张加速卡⏩ 预估剩余时间: ${(timeLeft/60).toInt()} 分钟")
                    Log.farm("今日已使用${Status().useAccelerateToolCount}张加速卡")
                    delay(1000)
                } else{
                    // 如果加速后吃完了，尝试补喂并刷新倒计时
                    Log.farm("使用加速卡后小鸡饲料吃完，等待8秒后尝试喂鸡")
                    delay(8000)
                    syncAnimalStatus(ownerFarmId)
                    if (AnimalFeedStatus.HUNGRY.name == ownerAnimal.animalFeedStatus) {
                        if (feedAnimal(ownerFarmId)) {
                            syncAnimalStatus(ownerFarmId)
                            remainingFood = 180.0
//                            totalFoodHaveEatten = 0.0
                            Log.farm("加速卡后投喂小鸡成功！")
                            if (!Status.hasFlagToday("farm::farmGameFinished")){
                                // 测试飞行和打小鸡能否得到360g能量
                                if (foodStock < foodStockLimit - 360) {
                                    Log.farm("加速后已喂食，领取饲料奖励")
                                    receiveFarmAwards()
                                } else {
                                    Log.farm("今天游戏改分还没有完成，预留360g的饲料剩余空间，目前饲料${foodStock}g，还差${foodStockLimit - foodStock}g满饲料")
                                }
                            } else {
                                Log.farm("加速后已喂食，领取饲料奖励")
                                receiveFarmAwards()
                            }
                        } else {
                            remainingFood = (countdown?.toDouble() ?: 0.0) * totalConsumeSpeed
                            Log.farm("使用加速卡使饲料吃完，投喂小鸡失败！")
                        }
                    } else {
                        // 如果再次同步发现小鸡不是饥饿状态，重新开始计算remainingFood
                        remainingFood = (countdown?.toDouble() ?: 0.0) * totalConsumeSpeed
                    }
                }
            } else {
                Log.record(TAG, "加速卡内部⏩useFarmTool 返回失败，终止循环")
                break
            }
            // 若未开启“连续使用”，只使用 1 次后退出
            if (!useAccelerateToolContinue!!.value) {
                break
            }
        }
        Log.farm("剩余可加速的时间少于${remainingTimeValue}分钟")
        Log.record(TAG, "加速卡内部⏩最终 isUseAccelerateTool=$isUseAccelerateTool")
        return isUseAccelerateTool
    }

    private fun useFarmTool(targetFarmId: String?, toolType: ToolType): Boolean {
        try {
            var s = AntFarmRpcCall.listFarmTool()
            var jo = JSONObject(s)
            var memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val jaToolList = jo.getJSONArray("toolList")
                for (i in 0..<jaToolList.length()) {
                    jo = jaToolList.getJSONObject(i)
                    if (toolType.name == jo.getString("toolType")) {
                        val toolCount = jo.getInt("toolCount")
                        if (toolCount > 0) {
                            var toolId = ""
                            if (jo.has("toolId")) toolId = jo.getString("toolId")
                            s = AntFarmRpcCall.useFarmTool(targetFarmId, toolId, toolType.name)
                            jo = JSONObject(s)
                            memo = jo.getString("memo")
                            if (ResChecker.checkRes(TAG, jo)) {
                                Log.farm("使用道具🎭[" + toolType.nickName() + "]#剩余" + (toolCount - 1) + "张")
                                return true
                            } else {
                                // 针对加速卡：当日达到上限(resultCode=3D16)后，设置当日标记，避免后续重复尝试
                                val resultCode = jo.optString("resultCode")
                                if (toolType == ToolType.ACCELERATETOOL && resultCode == "3D16") {
                                    Status.setFlagToday("farm::accelerateLimit")
                                }
                                Log.record(memo)
                            }
                            Log.runtime(s)
                        }
                        break
                    }
                }
            } else {
                Log.record(memo)
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "useFarmTool err:",t)
        }
        return false
    }

    private suspend fun feedFriend() {
        try {
            val feedFriendAnimalMap: Map<String?, Int?> = feedFriendAnimalList!!.value
            for (entry in feedFriendAnimalMap.entries) {
                val userId: String = entry.key!!
                val maxDailyCount: Int = entry.value!!

                // 智能冲突避免：如果是自己的账号
                if (userId == UserMap.currentUid) {
                    if (feedAnimal!!.value) {
                        // 已开启"自动喂小鸡" → 优先使用蹲点机制（更精准），跳过好友列表喂食
                        Toast.show(
                            "⚠️ 配置冲突提醒\n" +
                                    "已开启「自动喂小鸡」，将使用蹲点机制（精准时间）\n" +
                                    "好友列表中的自己（配置${maxDailyCount}次）已被忽略\n" +
                                    "建议：无需在好友列表中添加自己"
                        )
                        continue
                    } else {
                        // 未开启"自动喂小鸡" → 使用好友列表机制（尊重次数限制）
                        // 继续执行后续逻辑
                    }
                }

                if (!Status.canFeedFriendToday(userId, maxDailyCount)) continue
                val jo = JSONObject(AntFarmRpcCall.enterFarm(userId, userId))
                delay(3 * 1000L) //延迟3秒
                if (ResChecker.checkRes(TAG, jo)) {
                    val subFarmVOjo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO")
                    val friendFarmId = subFarmVOjo.getString("farmId")
                    val jaAnimals = subFarmVOjo.getJSONArray("animals")
                    for (j in 0..<jaAnimals.length()) {
                        val animalsjo = jaAnimals.getJSONObject(j)

                        val masterFarmId = animalsjo.getString("masterFarmId")
                        if (masterFarmId == friendFarmId) { //遍历到的鸡 如果在自己的庄园
                            val animalStatusVO = animalsjo.getJSONObject("animalStatusVO")
                            val animalInteractStatus =
                                animalStatusVO.getString("animalInteractStatus") //动物互动状态
                            val animalFeedStatus =
                                animalStatusVO.getString("animalFeedStatus") //动物饲料状态
                            if (AnimalInteractStatus.HOME.name == animalInteractStatus && AnimalFeedStatus.HUNGRY.name == animalFeedStatus) { //状态是饥饿 并且在庄园
                                val user = UserMap.getMaskName(userId) //喂 给我喂
                                if (foodStock < 180) {
                                    if (unreceiveTaskAward > 0) {
                                        Log.record(TAG, "✨还有待领取的饲料")
                                        receiveFarmAwards() //先去领个饲料
                                    }
                                }
                                //第二次检查
                                if (foodStock >= 180) {
                                    if (Status.hasFlagToday("farm::feedFriendLimit")) {
                                        return
                                    }
                                    val feedFriendAnimaljo =
                                        JSONObject(AntFarmRpcCall.feedFriendAnimal(friendFarmId))
                                    if (ResChecker.checkRes(TAG, feedFriendAnimaljo)) {
                                        foodStock = feedFriendAnimaljo.getInt("foodStock")
                                        Log.farm("帮喂好友🥣[" + user + "]的小鸡[180g]#剩余" + foodStock + "g")
                                        Status.feedFriendToday(
                                            AntFarmRpcCall.farmId2UserId(
                                                friendFarmId
                                            )
                                        )
                                    } else {
                                        Log.error(
                                            TAG,
                                            "😞喂[$user]的鸡失败$feedFriendAnimaljo"
                                        )
                                        Status.setFlagToday("farm::feedFriendLimit")
                                        break
                                    }
                                } else {
                                    Log.record(TAG, "😞喂鸡[$user]饲料不足")
                                }
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "feedFriend 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "feedFriendAnimal err:", t)
        }
    }


    private fun notifyFriend() {
        if (foodStock >= foodStockLimit) return
        try {
            var hasNext = false
            var pageStartSum = 0
            var s: String?
            var jo: JSONObject
            do {
                s = AntFarmRpcCall.rankingList(pageStartSum)
                // 检查空响应
                if (s.isNullOrEmpty()) {
                    Log.runtime(TAG, "notifyFriend.rankingList: 收到空响应，终止通知")
                    break // 跳出do-while循环
                }
                jo = JSONObject(s)
                var memo = jo.getString("memo")
                if (ResChecker.checkRes(TAG, jo)) {
                    hasNext = jo.getBoolean("hasNext")
                    val jaRankingList = jo.getJSONArray("rankingList")
                    pageStartSum += jaRankingList.length()
                    for (i in 0..<jaRankingList.length()) {
                        jo = jaRankingList.getJSONObject(i)
                        val userId = jo.getString("userId")
                        val userName = UserMap.getMaskName(userId)
                        var isNotifyFriend = notifyFriendList!!.value.contains(userId)
                        if (notifyFriendType!!.value == NotifyFriendType.DONT_NOTIFY) {
                            isNotifyFriend = !isNotifyFriend
                        }
                        if (!isNotifyFriend || userId == UserMap.currentUid) {
                            continue
                        }
                        val starve =
                            jo.has("actionType") && "starve_action" == jo.getString("actionType")
                        if (jo.getBoolean("stealingAnimal") && !starve) {
                            s = AntFarmRpcCall.enterFarm(userId, userId)
                            // 循环内的空响应检查：静默跳过该好友，继续处理下一个
                            if (s.isNullOrEmpty()) {
                                continue // 跳过当前好友，处理下一个
                            }
                            jo = JSONObject(s)
                            memo = jo.getString("memo")
                            if (ResChecker.checkRes(TAG, jo)) {
                                jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO")
                                val friendFarmId = jo.getString("farmId")
                                val jaAnimals = jo.getJSONArray("animals")
                                var notified = (notifyFriend!!.value)
                                for (j in 0..<jaAnimals.length()) {
                                    jo = jaAnimals.getJSONObject(j)
                                    val animalId = jo.getString("animalId")
                                    val masterFarmId = jo.getString("masterFarmId")
                                    if (masterFarmId != friendFarmId && masterFarmId != ownerFarmId) {
                                        if (notified) continue
                                        jo = jo.getJSONObject("animalStatusVO")
                                        notified =
                                            notifyFriend(jo, friendFarmId, animalId, userName)
                                    }
                                }
                            } else {
                                Log.record(memo)
                                Log.runtime(s)
                            }
                        }
                    }
                } else {
                    Log.record(memo)
                    Log.runtime(s)
                }
            } while (hasNext)
            Log.record(TAG, "饲料剩余[" + foodStock + "g]")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "notifyFriend err:",t)
        }
    }

    private fun notifyFriend(
        joAnimalStatusVO: JSONObject,
        friendFarmId: String?,
        animalId: String?,
        user: String?
    ): Boolean {
        try {
            if (AnimalInteractStatus.STEALING.name == joAnimalStatusVO.getString("animalInteractStatus") && AnimalFeedStatus.EATING.name == joAnimalStatusVO.getString(
                    "animalFeedStatus"
                )
            ) {
                val jo = JSONObject(AntFarmRpcCall.notifyFriend(animalId, friendFarmId))
                if (ResChecker.checkRes(TAG, jo)) {
                    val rewardCount = jo.getDouble("rewardCount")
                    if (jo.getBoolean("refreshFoodStock")) foodStock =
                        jo.getDouble("finalFoodStock").toInt()
                    else add2FoodStock(rewardCount.toInt())
                    Log.farm("通知好友📧[" + user + "]被偷吃#奖励" + rewardCount + "g")
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "notifyFriend err:", t)
        }
        return false
    }

    /**
     * 解析同步响应状态
     *
     * @param jo 同步响应状态
     */
    @SuppressLint("DefaultLocale")
    private fun parseSyncAnimalStatusResponse(jo: JSONObject) {
        try {
            if (!jo.has("subFarmVO")) {
                return
            }
            if (jo.has("emotionInfo")) { //小鸡心情
                finalScore = jo.getJSONObject("emotionInfo").getDouble("finalScore")
            }
            val subFarmVO = jo.getJSONObject("subFarmVO")
            // 解析服务端返回的“是否已使用加饭卡”状态
            serverUseBigEaterTool = subFarmVO.optBoolean("useBigEaterTool", false)
            if (subFarmVO.has("foodStock")) {
                foodStock = subFarmVO.getInt("foodStock")
            }
            // 同步当前食槽上限（子字段 foodInTroughLimit 优先，其次 foodStockLimit）
            foodInTroughLimitCurrent = when {
                subFarmVO.has("foodInTroughLimit") -> subFarmVO.getInt("foodInTroughLimit")
                subFarmVO.has("foodStockLimit") -> subFarmVO.getInt("foodStockLimit")
                jo.has("foodStockLimit") -> jo.getInt("foodStockLimit")
                else -> 180
            }
            // 同步当前仓库上限，防止后续判断出现上限为0的情况（提取失败则默认 1800）
            foodStockLimit = if (subFarmVO.has("foodStockLimit")) {
                subFarmVO.getInt("foodStockLimit")
            } else if (jo.has("foodStockLimit")) {
                // enterFarm 的 farmVO 层也可能携带该字段
                jo.getInt("foodStockLimit")
            } else {
                1800
            }
            if (subFarmVO.has("manureVO")) { //粪肥 鸡屎
                val manurePotList =
                    subFarmVO.getJSONObject("manureVO").getJSONArray("manurePotList")
                for (i in 0..<manurePotList.length()) {
                    val manurePot = manurePotList.getJSONObject(i)
                    if (manurePot.getInt("manurePotNum") >= 100) { //粪肥数量
                        val joManurePot =
                            JSONObject(AntFarmRpcCall.collectManurePot(manurePot.getString("manurePotNO")))
                        if (ResChecker.checkRes(TAG, joManurePot)) {
                            val collectManurePotNum = joManurePot.getInt("collectManurePotNum")
                            Log.farm("打扫鸡屎🧹[" + collectManurePotNum + "g]" + i + 1 + "次")
                        } else {
                            Log.runtime(TAG, "打扫鸡屎失败: 第" + i + 1 + "次" + joManurePot)
                        }
                    }
                }
            }


            ownerFarmId = subFarmVO.getString("farmId")
            //倒计时
            countdown = subFarmVO.getLong("countdown")
            val farmProduce = subFarmVO.getJSONObject("farmProduce") //产物 -🥚
            benevolenceScore = farmProduce.getDouble("benevolenceScore") //慈善评分

            if (subFarmVO.has("rewardList")) {
                val jaRewardList = subFarmVO.getJSONArray("rewardList")
                if (jaRewardList.length() > 0) {
                    val tempList = mutableListOf<RewardFriend>()
                    for (i in 0..<jaRewardList.length()) {
                        val joRewardList = jaRewardList.getJSONObject(i)
                        val reward = RewardFriend()
                        reward.consistencyKey = joRewardList.getString("consistencyKey")
                        reward.friendId = joRewardList.getString("friendId")
                        reward.time = joRewardList.getString("time")
                        tempList.add(reward)
                    }
                    rewardList = tempList.toTypedArray()
                }
            }

            val jaAnimals = subFarmVO.getJSONArray("animals") //小鸡们
            val animalList: MutableList<Animal> = ArrayList()
            for (i in 0..<jaAnimals.length()) {
                val animalJson = jaAnimals.getJSONObject(i)
                val animal: Animal =
                    objectMapper.readValue(animalJson.toString(), Animal::class.java)
                animalList.add(animal)
                Log.other("animalID:" + animal.animalId + ",startEatTime=" + TimeUtil.getCommonDate(animal.startEatTime) + ",consumeSpeed=" + animal.consumeSpeed
                + ",foodHaveEatten=" + animal.foodHaveEatten + ",foodHaveStolen=" + animal.foodHaveStolen + ",animalFeedStatus=" + animal.animalFeedStatus
                    + ",animalInteractStatus=" + animal.animalInteractStatus
                )
                if (animal.masterFarmId == ownerFarmId) {
                    ownerAnimal = animal
                    Log.other("Countdown=" + String.format(
                        "%02d:%02d:%02d",
                        countdown!! / 3600,
                        (countdown!! % 3600) / 60,
                        countdown!! % 60
                    ))
                }
                //                Log.record(TAG, "当前动物：" + animal.toString());
            }
            animals = animalList.toTypedArray()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "parseSyncAnimalStatusResponse err:",t)
        }
    }

    private fun add2FoodStock(i: Int) {
        foodStock += i
        if (foodStock > foodStockLimit) {
            foodStock = foodStockLimit
        }
        if (foodStock < 0) {
            foodStock = 0
        }
    }


    /**
     * 收集每日食材
     */
    private fun collectDailyFoodMaterial() {
        try {
            val userId = UserMap.currentUid
            var jo = JSONObject(AntFarmRpcCall.enterKitchen(userId))
            if (ResChecker.checkRes(TAG, jo)) {
                val canCollectDailyFoodMaterial = jo.getBoolean("canCollectDailyFoodMaterial")
                val dailyFoodMaterialAmount = jo.getInt("dailyFoodMaterialAmount")
                val garbageAmount = jo.optInt("garbageAmount", 0)
                if (jo.has("orchardFoodMaterialStatus")) {
                    val orchardFoodMaterialStatus = jo.getJSONObject("orchardFoodMaterialStatus")
                    if ("FINISHED" == orchardFoodMaterialStatus.optString("foodStatus")) {
                        jo = JSONObject(AntFarmRpcCall.farmFoodMaterialCollect())
                        if (ResChecker.checkRes(TAG, jo)) {
                            Log.farm("小鸡厨房👨🏻‍🍳[领取农场食材]#" + jo.getInt("foodMaterialAddCount") + "g")
                        }
                    }
                }
                if (canCollectDailyFoodMaterial) {
                    jo =
                        JSONObject(AntFarmRpcCall.collectDailyFoodMaterial(dailyFoodMaterialAmount))
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡厨房👨🏻‍🍳[领取今日食材]#" + dailyFoodMaterialAmount + "g")
                    }
                }
                if (garbageAmount > 0) {
                    jo = JSONObject(AntFarmRpcCall.collectKitchenGarbage())
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡厨房👨🏻‍🍳[领取肥料]#" + jo.getInt("recievedKitchenGarbageAmount") + "g")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "收集每日食材", t)
        }
    }

    /**
     * 领取爱心食材店食材
     */
    private fun collectDailyLimitedFoodMaterial() {
        try {
            var jo = JSONObject(AntFarmRpcCall.queryFoodMaterialPack())
            if (ResChecker.checkRes(TAG, jo)) {
                val canCollectDailyLimitedFoodMaterial =
                    jo.getBoolean("canCollectDailyLimitedFoodMaterial")
                if (canCollectDailyLimitedFoodMaterial) {
                    val dailyLimitedFoodMaterialAmount = jo.getInt("dailyLimitedFoodMaterialAmount")
                    jo = JSONObject(
                        AntFarmRpcCall.collectDailyLimitedFoodMaterial(
                            dailyLimitedFoodMaterialAmount
                        )
                    )
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡厨房👨🏻‍🍳[领取爱心食材店食材]#" + dailyLimitedFoodMaterialAmount + "g")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "领取爱心食材店食材", t)
        }
    }

    private suspend fun cook() {
        try {
            val userId = UserMap.currentUid
            var jo = JSONObject(AntFarmRpcCall.enterKitchen(userId))
            Log.runtime(TAG, "cook userid :$userId")
            if (ResChecker.checkRes(TAG, jo)) {
                val cookTimesAllowed = jo.getInt("cookTimesAllowed")
                if (cookTimesAllowed > 0) {
                    for (i in 0..<cookTimesAllowed) {
                        jo = JSONObject(AntFarmRpcCall.cook(userId, "VILLA"))
                        if (ResChecker.checkRes(TAG, jo)) {
                            val cuisineVO = jo.getJSONObject("cuisineVO")
                            Log.farm("小鸡厨房👨🏻‍🍳[" + cuisineVO.getString("name") + "]制作成功")
                        } else {
                            Log.runtime(TAG, "小鸡厨房制作$jo")
                        }
                        delay(RandomUtil.delay().toLong())
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "cook 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "cook err:",t)
        }
    }

    private fun useSpecialFood(cuisineList: JSONArray) {
        try {
            var jo: JSONObject
            var cookbookId: String?
            var cuisineId: String?
            var name: String?
            for (i in 0..<cuisineList.length()) {
                jo = cuisineList.getJSONObject(i)
                if (jo.getInt("count") <= 0) continue
                cookbookId = jo.getString("cookbookId")
                cuisineId = jo.getString("cuisineId")
                name = jo.getString("name")
                jo = JSONObject(AntFarmRpcCall.useFarmFood(cookbookId, cuisineId))
                if (ResChecker.checkRes(TAG, jo)) {
                    val deltaProduce = jo.getJSONObject("foodEffect").getDouble("deltaProduce")
                    Log.farm("使用美食🍱[" + name + "]#加速" + deltaProduce + "颗爱心鸡蛋")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "useFarmFood err:",t)
        }
    }

    private fun drawLotteryPlus(lotteryPlusInfo: JSONObject) {
        try {
            if (!lotteryPlusInfo.has("userSevenDaysGiftsItem")) return
            val itemId = lotteryPlusInfo.getString("itemId")
            var userSevenDaysGiftsItem = lotteryPlusInfo.getJSONObject("userSevenDaysGiftsItem")
            val userEverydayGiftItems = userSevenDaysGiftsItem.getJSONArray("userEverydayGiftItems")
            for (i in 0..<userEverydayGiftItems.length()) {
                userSevenDaysGiftsItem = userEverydayGiftItems.getJSONObject(i)
                if (userSevenDaysGiftsItem.getString("itemId") == itemId) {
                    if (!userSevenDaysGiftsItem.getBoolean("received")) {
                        val singleDesc = userSevenDaysGiftsItem.getString("singleDesc")
                        val awardCount = userSevenDaysGiftsItem.getInt("awardCount")
                        if (singleDesc.contains("饲料") && awardCount + foodStock > foodStockLimit) {
                            Log.record(
                                TAG,
                                "暂停领取[$awardCount]g饲料，上限为[$foodStockLimit]g"
                            )
                            break
                        }
                        userSevenDaysGiftsItem = JSONObject(AntFarmRpcCall.drawLotteryPlus())
                        if ("SUCCESS" == userSevenDaysGiftsItem.getString("memo")) {
                            Log.farm("惊喜礼包🎁[$singleDesc*$awardCount]")
                        }
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "drawLotteryPlus err:",t)
        }
    }

    /**
     * 送麦子
     */
    private suspend fun visit() {
        try {
            val map: Map<String?, Int?> = visitFriendList!!.value
            if (map.isEmpty()) return
            val currentUid = UserMap.currentUid
            for (entry in map.entries) {
                val userId: String = entry.key!!
                val count: Int = entry.value!!
                // 跳过自己和非法数量
                if (userId == currentUid || count <= 0) continue
                // 限制最大访问次数
                val visitCount = min(count, 3)
                // 如果今天还可以访问
                if (Status.canVisitFriendToday(userId, visitCount)) {
                    val remaining = visitFriend(userId, visitCount)
                    if (remaining > 0) {
                        Status.visitFriendToday(userId, remaining)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "visit 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "visit err:",t)
        }
    }


    private suspend fun visitFriend(userId: String?, count: Int): Int {
        var visitedTimes = 0
        try {
            var jo = JSONObject(AntFarmRpcCall.enterFarm(userId, userId))
            if (ResChecker.checkRes(TAG, jo)) {
                val farmVO = jo.getJSONObject("farmVO")
                foodStock = farmVO.getInt("foodStock")
                val subFarmVO = farmVO.getJSONObject("subFarmVO")
                if (subFarmVO.optBoolean("visitedToday", true)) return 3
                val farmId = subFarmVO.getString("farmId")
                for (i in 0..<count) {
                    if (foodStock < 10) break
                    jo = JSONObject(AntFarmRpcCall.visitFriend(farmId))
                    if (ResChecker.checkRes(TAG, jo)) {
                        foodStock = jo.getInt("foodStock")
                        Log.farm("赠送麦子🌾[" + UserMap.getMaskName(userId) + "]#" + jo.getInt("giveFoodNum") + "g")
                        visitedTimes++
                        if (jo.optBoolean("isReachLimit")) {
                            Log.record(
                                TAG,
                                "今日给[" + UserMap.getMaskName(userId) + "]送麦子已达上限"
                            )
                            visitedTimes = 3
                            break
                        }
                    }
                    delay(800L)
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "visitFriend 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "visitFriend err:",t)
        }
        return visitedTimes
    }

    private fun acceptGift() {
        try {
            val jo = JSONObject(AntFarmRpcCall.acceptGift())
            if (ResChecker.checkRes(TAG, jo)) {
                val receiveFoodNum = jo.getInt("receiveFoodNum")
                Log.farm("收取麦子🌾[" + receiveFoodNum + "g]")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "acceptGift err:",t)
        }
    }

    /**
     * 贴贴小鸡
     *
     * @param queryDayStr 日期，格式：yyyy-MM-dd
     */
    private fun diaryTietze(@Suppress("SameParameterValue") queryDayStr: String?) {
        val diaryDateStr: String?
        try {
            var jo = JSONObject(AntFarmRpcCall.queryChickenDiary(queryDayStr))
            if (ResChecker.checkRes(TAG, jo)) {
                val data = jo.getJSONObject("data")
                val chickenDiary = data.getJSONObject("chickenDiary")
                diaryDateStr = chickenDiary.getString("diaryDateStr")
                if (data.has("hasTietie")) {
                    if (!data.optBoolean("hasTietie", true)) {
                        jo = JSONObject(AntFarmRpcCall.diaryTietie(diaryDateStr, "NEW"))
                        if (ResChecker.checkRes(TAG, jo)) {
                            val prizeType = jo.getString("prizeType")
                            val prizeNum = jo.optInt("prizeNum", 0)
                            Log.farm("[$diaryDateStr]贴贴小鸡💞[$prizeType*$prizeNum]")
                        } else {
                            Log.runtime(TAG, "贴贴小鸡失败:")
                            Log.runtime(jo.getString("memo"), jo.toString())
                        }
                        if (!chickenDiary.has("statisticsList")) return
                        val statisticsList = chickenDiary.getJSONArray("statisticsList")
                        if (statisticsList.length() > 0) {
                            for (i in 0..<statisticsList.length()) {
                                val tietieStatus = statisticsList.getJSONObject(i)
                                val tietieRoleId = tietieStatus.getString("tietieRoleId")
                                jo = JSONObject(
                                    AntFarmRpcCall.diaryTietie(
                                        diaryDateStr,
                                        tietieRoleId
                                    )
                                )
                                if (ResChecker.checkRes(TAG, jo)) {
                                    val prizeType = jo.getString("prizeType")
                                    val prizeNum = jo.optInt("prizeNum", 0)
                                    Log.farm("[$diaryDateStr]贴贴小鸡💞[$prizeType*$prizeNum]")
                                } else {
                                    Log.runtime(TAG, "贴贴小鸡失败:")
                                    Log.runtime(jo.getString("memo"), jo.toString())
                                }
                            }
                        }
                    }
                }
            } else {
                Log.runtime(TAG, "贴贴小鸡-获取小鸡日记详情 err:")
                Log.runtime(jo.getString("resultDesc"), jo.toString())
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryChickenDiary err:",t)
        }
    }

    /**
     * 点赞小鸡日记
     *
     */
    private fun collectChickenDiary(queryDayStr: String?): String? {
        var diaryDateStr: String? = null
        try {
            var jo = JSONObject(AntFarmRpcCall.queryChickenDiary(queryDayStr))
            if (ResChecker.checkRes(TAG, jo)) {
                val data = jo.getJSONObject("data")
                val chickenDiary = data.getJSONObject("chickenDiary")
                diaryDateStr = chickenDiary.getString("diaryDateStr")
                // 点赞小鸡日记
                if (!chickenDiary.optBoolean("collectStatus", true)) {
                    val diaryId = chickenDiary.getString("diaryId")
                    jo = JSONObject(AntFarmRpcCall.collectChickenDiary(diaryId))
                    if (jo.optBoolean("success", true)) {
                        Log.farm("[$diaryDateStr]点赞小鸡日记💞成功")
                    }
                }
            } else {
                Log.runtime(TAG, "日记点赞-获取小鸡日记详情 err:")
                Log.runtime(jo.getString("resultDesc"), jo.toString())
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryChickenDiary err:",t)
        }
        return diaryDateStr
    }

    private suspend fun queryChickenDiaryList(
        queryMonthStr: String?,
        `fun`: (String?) -> String?
    ): Boolean {
        var hasPreviousMore = false
        try {
            var jo: JSONObject?
            jo = if (StringUtil.isEmpty(queryMonthStr)) {
                JSONObject(AntFarmRpcCall.queryChickenDiaryList())
            } else {
                JSONObject(AntFarmRpcCall.queryChickenDiaryList(queryMonthStr))
            }
            if (ResChecker.checkRes(TAG, jo)) {
                jo = jo.getJSONObject("data")
                hasPreviousMore = jo.optBoolean("hasPreviousMore", false)
                val chickenDiaryBriefList = jo.optJSONArray("chickenDiaryBriefList")
                if (chickenDiaryBriefList != null && chickenDiaryBriefList.length() > 0) {
                    for (i in chickenDiaryBriefList.length() - 1 downTo 0) {
                        jo = chickenDiaryBriefList.getJSONObject(i)
                        if (!jo.optBoolean("read", true) ||
                            !jo.optBoolean("collectStatus")
                        ) {
                            val dateStr = jo.getString("dateStr")
                            `fun`(dateStr)
                            delay(300)
                        }
                    }
                }
            } else {
                Log.runtime(jo.getString("resultDesc"), jo.toString())
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "queryChickenDiaryList 协程被取消")
            throw e
        } catch (t: Throwable) {
            hasPreviousMore = false
            Log.printStackTrace(TAG, "queryChickenDiaryList err:",t)
        }
        return hasPreviousMore
    }

    private suspend fun doChickenDiary() {
        if (diaryTietie!!.value) { // 贴贴小鸡
            diaryTietze("")
        }

        // 小鸡日记点赞
        var dateStr: String? = null
        var yearMonth = YearMonth.now()
        var previous = false
        try {
            if (collectChickenDiary!!.value >= collectChickenDiaryType.ONCE) {
                delay(300)
                dateStr = collectChickenDiary("")
            }
            if (collectChickenDiary!!.value >= collectChickenDiaryType.MONTH) {
                if (dateStr == null) {
                    Log.error(TAG, "小鸡日记点赞-dateStr为空，使用当前日期")
                } else {
                    yearMonth = YearMonth.from(LocalDate.parse(dateStr))
                }
                delay(300)
                previous = queryChickenDiaryList(
                    yearMonth.toString()
                ) { queryDayStr ->
                    this.collectChickenDiary(queryDayStr)
                }
            }
            if (collectChickenDiary!!.value >= collectChickenDiaryType.ALL) {
                while (previous) {
                    delay(300)
                    yearMonth = yearMonth.minusMonths(1)
                    previous = queryChickenDiaryList(
                        yearMonth.toString()
                    ) { queryDayStr ->
                        this.collectChickenDiary(queryDayStr)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "doChickenDiary 协程被取消")
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "doChickenDiary err:",e)
        }
    }

    private fun visitAnimal() {
        try {
            val response = AntFarmRpcCall.visitAnimal()
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "visitAnimal: 收到空响应")
                return
            }
            var jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                if (!jo.has("talkConfigs")) return
                val talkConfigs = jo.getJSONArray("talkConfigs")
                val talkNodes = jo.getJSONArray("talkNodes")
                val data = talkConfigs.getJSONObject(0)
                val farmId = data.getString("farmId")

                val response2 = AntFarmRpcCall.feedFriendAnimalVisit(farmId)
                if (response2.isNullOrEmpty()) {
                    Log.runtime(TAG, "feedFriendAnimalVisit: 收到空响应")
                    return
                }
                jo = JSONObject(response2)
                if (ResChecker.checkRes(TAG, jo)) {
                    for (i in 0..<talkNodes.length()) {
                        jo = talkNodes.getJSONObject(i)
                        if ("FEED" != jo.getString("type")) continue
                        val consistencyKey = jo.getString("consistencyKey")

                        val response3 = AntFarmRpcCall.visitAnimalSendPrize(consistencyKey)
                        if (response3.isNullOrEmpty()) continue // 静默跳过，继续处理下一个
                        jo = JSONObject(response3)
                        if (ResChecker.checkRes(TAG, jo)) {
                            val prizeName = jo.getString("prizeName")
                            Log.farm("小鸡到访💞[$prizeName]")
                        } else {
                            Log.runtime(jo.getString("memo"), jo.toString())
                        }
                    }
                } else {
                    Log.runtime(jo.getString("memo"), jo.toString())
                }
            } else {
                Log.runtime(jo.getString("resultDesc"), jo.toString())
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "visitAnimal err:",t)
        }
    }

    /* 雇佣好友小鸡 */
    private  fun hireAnimal() {
        // 重置农场已满标志
        isFarmFull = false
        var animals: JSONArray? = null
        try {
            val jsonObject = enterFarm() ?: return
            if ("SUCCESS" == jsonObject.getString("memo")) {
                val farmVO = jsonObject.getJSONObject("farmVO")
                val subFarmVO = farmVO.getJSONObject("subFarmVO")
                animals = subFarmVO.getJSONArray("animals")
            } else {
                Log.record(jsonObject.getString("memo"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "getAnimalCount err:",t)
            return
        }
        if (animals == null) {
            return
        }
        try {
            var i = 0
            val len = animals.length()
            while (i < len) {
                val joo = animals.getJSONObject(i)
                if (joo.getString("subAnimalType") == "WORK") {
                    val taskId = "HIRE|" + joo.getString("animalId")
                    val beHiredEndTime = joo.getLong("beHiredEndTime")
                    if (!hasChildTask(taskId)) {
                        addChildTask(
                            ChildModelTask(
                                taskId,
                                "HIRE",
                                suspendRunnable = { this.hireAnimal() },
                                beHiredEndTime
                            )
                        )
                        Log.record(
                            TAG,
                            "添加蹲点雇佣👷在[" + TimeUtil.getCommonDate(beHiredEndTime) + "]执行"
                        )
                    } else {
                        addChildTask(
                            ChildModelTask(
                                taskId,
                                "HIRE",
                                suspendRunnable = { this.hireAnimal() },
                                beHiredEndTime
                            )
                        )
                    }
                }
                i++
            }
            var animalCount = animals.length()
            if (animalCount >= 3) {
                return
            }
            val needHireCount = 3 - animalCount
            Log.farm("雇佣小鸡👷[当前可雇佣小鸡数量:${needHireCount}只]")

            // 前置检查：饲料是否足够
            if (foodStock < 50) {
                Log.record(TAG, "❌ 雇佣失败：饲料不足（当前${foodStock}g，至少需要50g）")
                return
            }

            // 前置检查：是否配置了雇佣好友列表
            val hireAnimalSet = hireAnimalList!!.value
            if (hireAnimalSet.isEmpty()) {
                Log.record(TAG, "❌ 雇佣失败：未配置雇佣好友列表")
                Toast.show(
                    "⚠️ 雇佣小鸡配置错误\n" +
                            "已开启「雇佣小鸡」但未配置好友列表\n" +
                            "请在「雇佣小鸡 | 好友列表」中勾选好友"
                )
                return
            }

            var hasNext: Boolean
            var pageStartSum = 0
            var s: String?
            var jo: JSONObject?
            var checkedCount = 0  // 检查过的好友数量
            var availableCount = 0  // 可雇佣状态的好友数量
            val initialAnimalCount = animalCount  // 记录初始数量

            do {
                s = AntFarmRpcCall.rankingList(pageStartSum)
                jo = JSONObject(s)
                val memo = jo.getString("memo")
                if (ResChecker.checkRes(TAG, jo)) {
                    hasNext = jo.getBoolean("hasNext")
                    val jaRankingList = jo.getJSONArray("rankingList")
                    pageStartSum += jaRankingList.length()
                    for (i in 0..<jaRankingList.length()) {
                        val joo = jaRankingList.getJSONObject(i)
                        val userId = joo.getString("userId")
                        var isHireAnimal = hireAnimalSet.contains(userId)
                        if (hireAnimalType!!.value == HireAnimalType.DONT_HIRE) {
                            isHireAnimal = !isHireAnimal
                        }
                        if (!isHireAnimal || userId == UserMap.currentUid) {
                            continue
                        }

                        checkedCount++
                        val actionTypeListStr = joo.getJSONArray("actionTypeList").toString()
                        if (actionTypeListStr.contains("can_hire_action")) {
                            availableCount++
                            if (hireAnimalAction(userId)) {
                                animalCount++
                                break
                            }
                            // 检查农场是否已满
                            if (isFarmFull) {
                                animalCount = 3  // 标记庄园已满，避免下次循环继续尝试
                                break  // 跳出for循环
                            }
                        }
                    }
                } else {
                    Log.record(memo)
                    Log.runtime(s)
                    break
                }
            } while (hasNext && animalCount < 3)

            // 详细的结果报告
            val hiredCount = animalCount - initialAnimalCount
            if (animalCount < 3) {
                val stillNeed = 3 - animalCount
                Log.record(TAG, "雇佣小鸡结果统计：")
                Log.record(TAG, "  • 成功雇佣：${hiredCount}只")
                Log.record(TAG, "  • 还需雇佣：${stillNeed}只")
                Log.record(TAG, "  • 已检查好友：${checkedCount}人")
                Log.record(TAG, "  • 可雇佣状态：${availableCount}人")

                if (availableCount == 0) {
                    Log.record(TAG, "❌ 失败原因：好友列表中没有可雇佣的小鸡")
                    Log.record(TAG, "   建议：等待好友的小鸡回家或添加更多好友")
                } else if (hiredCount < availableCount) {
                    Log.record(TAG, "⚠️ 部分雇佣失败：好友的小鸡可能不在家")
                } else {
                    Log.record(TAG, "❌ 失败原因：可雇佣的小鸡数量不足")
                }
            } else if (hiredCount > 0) {
                Log.record(TAG, "✅ 雇佣成功：共雇佣${hiredCount}只小鸡")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "hireAnimal err:",t)
        }
    }

    private fun hireAnimalAction(userId: String?): Boolean {
        try {
            val s = AntFarmRpcCall.enterFarm(userId, userId)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val farmVO = jo.getJSONObject("farmVO")
                val subFarmVO = farmVO.getJSONObject("subFarmVO")
                val farmId = subFarmVO.getString("farmId")
                val animals = subFarmVO.getJSONArray("animals")
                var i = 0
                val len = animals.length()
                while (i < len) {
                    val animal = animals.getJSONObject(i)
                    if (animal.getJSONObject("masterUserInfoVO").getString("userId") == userId) {
                        val animalStatusVo = animal.getJSONObject("animalStatusVO")
                        if (AnimalInteractStatus.HOME.name != animalStatusVo.getString("animalInteractStatus")) {
                            Log.record(UserMap.getMaskName(userId) + "的小鸡不在家")
                            return false
                        }
                        val animalId = animal.getString("animalId")
                        jo = JSONObject(AntFarmRpcCall.hireAnimal(farmId, animalId))
                        if (ResChecker.checkRes(TAG, jo)) {
                            Log.farm("雇佣小鸡👷[" + UserMap.getMaskName(userId) + "] 成功")
                            val newAnimals = jo.getJSONArray("animals")
                            var ii = 0
                            val newLen = newAnimals.length()
                            while (ii < newLen) {
                                val joo = newAnimals.getJSONObject(ii)
                                if (joo.getString("animalId") == animalId) {
                                    val beHiredEndTime = joo.getLong("beHiredEndTime")
                                    addChildTask(
                                        ChildModelTask(
                                            "HIRE|$animalId",
                                            "HIRE",
                                            suspendRunnable = { this.hireAnimal() },
                                            beHiredEndTime
                                        )
                                    )
                                    Log.record(
                                        TAG,
                                        "添加蹲点雇佣👷在[" + TimeUtil.getCommonDate(beHiredEndTime) + "]执行"
                                    )
                                    break
                                }
                                ii++
                            }
                            return true
                        } else {
                            val resultCode = jo.optString("resultCode", "")
                            val memo = jo.optString("memo", "")
                            // 如果庄园已满，设置标志并返回false
                            if (resultCode == "I07" || memo.contains("庄园的小鸡太多了")) {
                                isFarmFull = true
                                Log.record(TAG, "庄园小鸡已满，停止雇佣")
                                return false
                            }
                            Log.record(memo)
                            Log.runtime(s)
                        }
                        return false
                    }
                    i++
                }
            } else {
                Log.record(jo.getString("memo"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "hireAnimal err:",t)
        }
        return false
    }

    private suspend fun drawGameCenterAward() {
        try {
            var jo = JSONObject(AntFarmRpcCall.queryGameList())
            // GlobalThreadPools.delay(3000);
            if (jo.optBoolean("success")) {
                val gameDrawAwardActivity = jo.getJSONObject("gameDrawAwardActivity")
                var canUseTimes = gameDrawAwardActivity.getInt("canUseTimes")
                while (canUseTimes > 0) {
                    try {
                        jo = JSONObject(AntFarmRpcCall.drawGameCenterAward())
                        delay(3000)
                        if (jo.optBoolean("success")) {
                            canUseTimes = jo.getInt("drawRightsTimes")
                            val gameCenterDrawAwardList = jo.getJSONArray("gameCenterDrawAwardList")
                            val awards = ArrayList<String?>()
                            for (i in 0..<gameCenterDrawAwardList.length()) {
                                val gameCenterDrawAward = gameCenterDrawAwardList.getJSONObject(i)
                                val awardCount = gameCenterDrawAward.getInt("awardCount")
                                val awardName = gameCenterDrawAward.getString("awardName")
                                awards.add("$awardName*$awardCount")
                            }
                            Log.farm(
                                "庄园小鸡🎁[开宝箱:获得" + StringUtil.collectionJoinString(
                                    ",",
                                    awards
                                ) + "]"
                            )
                        } else {
                            Log.runtime(TAG, "drawGameCenterAward falsed result: $jo")
                        }
                    } catch (e: CancellationException) {
                        // 协程取消异常必须重新抛出，不能吞掉
                        throw e
                    } catch (t: Throwable) {
                        Log.printStackTrace(TAG, t)
                    }
                }
            } else {
                Log.runtime(TAG, "queryGameList falsed result: $jo")
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "drawGameCenterAward 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryChickenDiaryList err:",t)
        }
    }

    // 小鸡换装
    private fun listOrnaments() {
        try {
            val s = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            val jsonObject = JSONObject(s)
            if ("SUCCESS" == jsonObject.getString("memo")) {
                val ownAnimal = jsonObject.getJSONObject("ownAnimal")
                val animalId = ownAnimal.getString("animalId")
                val farmId = ownAnimal.getString("farmId")
                val listResult = AntFarmRpcCall.listOrnaments()
                val jolistOrnaments = JSONObject(listResult)
                // 检查是否有 achievementOrnaments 数组
                if (!jolistOrnaments.has("achievementOrnaments")) {
                    return  // 数组为空，直接返回
                }
                val achievementOrnaments = jolistOrnaments.getJSONArray("achievementOrnaments")
                val random = Random()
                val possibleOrnaments: MutableList<String> = ArrayList() // 收集所有可保存的套装组合
                for (i in 0..<achievementOrnaments.length()) {
                    val ornament = achievementOrnaments.getJSONObject(i)
                    if (ornament.getBoolean("acquired")) {
                        val sets = ornament.getJSONArray("sets")
                        val availableSets: MutableList<JSONObject> = ArrayList()
                        // 收集所有带有 cap 和 coat 的套装组合
                        for (j in 0..<sets.length()) {
                            val set = sets.getJSONObject(j)
                            if ("cap" == set.getString("subType") || "coat" == set.getString("subType")) {
                                availableSets.add(set)
                            }
                        }
                        // 如果有可用的帽子和外套套装组合
                        if (availableSets.size >= 2) {
                            // 将所有可保存的套装组合添加到 possibleOrnaments 列表中
                            for (j in 0..<availableSets.size - 1) {
                                val selectedCoat = availableSets[j]
                                val selectedCap = availableSets[j + 1]
                                val id1 = selectedCoat.getString("id") // 外套 ID
                                val id2 = selectedCap.getString("id") // 帽子 ID
                                val ornaments = "$id1,$id2"
                                possibleOrnaments.add(ornaments)
                            }
                        }
                    }
                }
                // 如果有可保存的套装组合，则随机选择一个进行保存
                if (!possibleOrnaments.isEmpty()) {
                    val ornamentsToSave =
                        possibleOrnaments[random.nextInt(possibleOrnaments.size)]
                    val saveResult = AntFarmRpcCall.saveOrnaments(animalId, farmId, ornamentsToSave)
                    val saveResultJson = JSONObject(saveResult)
                    // 判断保存是否成功并输出日志
                    if (saveResultJson.optBoolean("success")) {
                        // 获取保存的整套服装名称
                        val ornamentIds: Array<String?> =
                            ornamentsToSave.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()
                        var wholeSetName = "" // 整套服装名称
                        // 遍历 achievementOrnaments 查找对应的套装名称
                        for (i in 0..<achievementOrnaments.length()) {
                            val ornament = achievementOrnaments.getJSONObject(i)
                            val sets = ornament.getJSONArray("sets")
                            // 找到对应的整套服装名称
                            if (sets.length() == 2 && sets.getJSONObject(0)
                                    .getString("id") == ornamentIds[0]
                                && sets.getJSONObject(1).getString("id") == ornamentIds[1]
                            ) {
                                wholeSetName = ornament.getString("name")
                                break
                            }
                        }
                        // 输出日志
                        Log.farm("庄园小鸡💞[换装:$wholeSetName]")
                        Status.setOrnamentToday()
                    } else {
                        Log.runtime(TAG, "保存时装失败，错误码： $saveResultJson")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "listOrnaments err: " + t.message,t)
        }
    }

    // 一起拿小鸡饲料
    private fun letsGetChickenFeedTogether() {
        try {
            var jo = JSONObject(AntFarmRpcCall.letsGetChickenFeedTogether())
            if (jo.optBoolean("success")) {
                val bizTraceId = jo.getString("bizTraceId")
                val p2pCanInvitePersonDetailList = jo.getJSONArray("p2pCanInvitePersonDetailList")
                var canInviteCount = 0
                var hasInvitedCount = 0
                val userIdList: MutableList<String?> = ArrayList() // 保存 userId
                for (i in 0..<p2pCanInvitePersonDetailList.length()) {
                    val personDetail = p2pCanInvitePersonDetailList.getJSONObject(i)
                    val inviteStatus = personDetail.getString("inviteStatus")
                    val userId = personDetail.getString("userId")
                    if (inviteStatus == "CAN_INVITE") {
                        userIdList.add(userId)
                        canInviteCount++
                    } else if (inviteStatus == "HAS_INVITED") {
                        hasInvitedCount++
                    }
                }
                val invitedToday = hasInvitedCount
                val remainingInvites = 5 - invitedToday
                var invitesToSend = min(canInviteCount, remainingInvites)
                if (invitesToSend == 0) {
                    return
                }
                val getFeedSet = getFeedlList!!.value
                if (getFeedType!!.value == GetFeedType.GIVE) {
                    for (userId in userIdList) {
                        if (invitesToSend <= 0) {
//                            Log.record(TAG,"已达到最大邀请次数限制，停止发送邀请。");
                            break
                        }
                        if (getFeedSet.contains(userId)) {
                            jo = JSONObject(AntFarmRpcCall.giftOfFeed(bizTraceId, userId))
                            if (jo.optBoolean("success")) {
                                Log.farm("一起拿小鸡饲料🥡 [送饲料：" + UserMap.getMaskName(userId) + "]")
                                invitesToSend-- // 每成功发送一次邀请，减少一次邀请次数
                            } else {
                                Log.record(TAG, "邀请失败：$jo")
                                break
                            }
                        }
                    }
                } else {
                    val random = Random()
                    for (j in 0..<invitesToSend) {
                        val randomIndex = random.nextInt(userIdList.size)
                        val userId = userIdList[randomIndex]
                        jo = JSONObject(AntFarmRpcCall.giftOfFeed(bizTraceId, userId))
                        if (jo.optBoolean("success")) {
                            Log.farm("一起拿小鸡饲料🥡 [送饲料：" + UserMap.getMaskName(userId) + "]")
                        } else {
                            Log.record(TAG, "邀请失败：$jo")
                            break
                        }
                        userIdList.removeAt(randomIndex)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "letsGetChickenFeedTogether err:",e)
        }
    }

    interface DonationCount {
        companion object {
            const val ONE: Int = 0
            const val ALL: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("随机一次", "随机多次")
        }
    }

    interface RecallAnimalType {
        companion object {
            const val ALWAYS: Int = 0
            const val WHEN_THIEF: Int = 1
            const val WHEN_HUNGRY: Int = 2
            const val NEVER: Int = 3
            val nickNames: Array<String?> =
                arrayOf<String?>("始终召回", "偷吃召回", "饥饿召回", "暂不召回")
        }
    }

    interface SendBackAnimalWay {
        companion object {
            const val HIT: Int = 0
            const val NORMAL: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("攻击", "常规")
        }
    }

    interface SendBackAnimalType {
        companion object {
            const val BACK: Int = 0
            const val NOT_BACK: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中遣返", "选中不遣返")
        }
    }

    @Suppress("ClassName")
    interface collectChickenDiaryType {
        companion object {
            const val CLOSE: Int = 0
            const val ONCE: Int = 0
            const val MONTH: Int = 1
            const val ALL: Int = 2
            val nickNames: Array<String?> = arrayOf<String?>("不开启", "一次", "当月", "所有")
        }
    }

    enum class AnimalBuff {
        //小鸡buff
        ACCELERATING, INJURED, NONE
    }

    /**
     * 小鸡喂食状态枚举
     */
    enum class AnimalFeedStatus {
        HUNGRY,  // 饥饿状态：小鸡需要投喂，可以正常喂食
        EATING,  // 进食状态：小鸡正在吃饭，此时不能重复投喂，会返回"不要着急，还没吃完呢"
        SLEEPY,  // 睡觉状态：小鸡正在睡觉，不能投喂，需要等待醒来
        NONE // 无状态：未知或其他状态
    }

    /**
     * 小鸡互动状态枚举
     */
    enum class AnimalInteractStatus {
        HOME,  // 在家：小鸡在自己的庄园里，正常状态
        GOTOSTEAL,  // 去偷吃：小鸡离开庄园，准备去别的庄园偷吃
        STEALING // 偷吃中：小鸡正在别人的庄园里偷吃饲料
    }

    /**
     * 小鸡子类型枚举
     */
    enum class SubAnimalType {
        NORMAL,  // 普通：正常的小鸡状态
        GUEST,  // 客人：小鸡去好友家做客
        PIRATE,  // 海盗：小鸡外出探险
        WORK // 工作：小鸡被雇佣去工作
    }

    /**
     * 道具类型枚举
     * STEALTOOL：蹭饭卡
     * ACCELERATETOOL：加速卡
     * SHARETOOL：救济卡
     * FENCETOOL：篱笆卡
     * NEWEGGTOOL：新蛋卡
     * DOLLTOOL：公仔补签卡
     * ORDINARY_ORNAMENT_TOOL：普通装扮补签卡
     * ADVANCE_ORNAMENT_TOOL：高级装扮补签卡
     * BIG_EATER_TOOL：加饭卡
     * RARE_ORNAMENT_TOOL：稀有装扮补签卡
     */
    enum class ToolType {
        STEALTOOL,  // 蹭饭卡
        ACCELERATETOOL,  // 加速卡
        SHARETOOL,  // 救济卡
        FENCETOOL,  // 篱笆卡
        NEWEGGTOOL,  // 新蛋卡
        DOLLTOOL,  // 公仔补签卡
        ORDINARY_ORNAMENT_TOOL,  // 普通装扮补签卡
        ADVANCE_ORNAMENT_TOOL,  // 高级装扮补签卡
        BIG_EATER_TOOL,  // 加饭卡
        RARE_ORNAMENT_TOOL; // 稀有装扮补签卡

        /**
         * 获取道具类型的中文名称
         * @return 对应的中文名称
         */
        fun nickName(): CharSequence? {
            return nickNames[ordinal]
        }

        companion object {
            // 道具类型对应的中文名称
            val nickNames: Array<CharSequence?> = arrayOf<CharSequence?>(
                "蹭饭卡",
                "加速卡",
                "救济卡",
                "篱笆卡",
                "新蛋卡",
                "公仔补签卡",
                "普通装扮补签卡",
                "高级装扮补签卡",
                "加饭卡",
                "稀有装扮补签卡"
            )
        }
    }

    enum class GameType {
        starGame, jumpGame, flyGame, hitGame;

        fun gameName(): CharSequence? {
            return gameNames[ordinal]
        }

        companion object {
            val gameNames: Array<CharSequence?> =
                arrayOf<CharSequence?>("星星球", "登山赛", "飞行赛", "欢乐揍小鸡")
        }
    }


    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    private class Animal {
        @JsonProperty("animalId")
        var animalId: String? = null

        @JsonProperty("currentFarmId")
        var currentFarmId: String? = null

        @JsonProperty("masterFarmId")
        var masterFarmId: String? = null

        @JsonProperty("animalBuff")
        var animalBuff: String? = null

        @JsonProperty("subAnimalType")
        var subAnimalType: String? = null

        @JsonProperty("currentFarmMasterUserId")
        var currentFarmMasterUserId: String? = null

        var animalFeedStatus: String? = null

        var animalInteractStatus: String? = null

        @JsonProperty("locationType")
        var locationType: String? = null

        @JsonProperty("startEatTime")
        var startEatTime: Long? = null

        @JsonProperty("consumeSpeed")
        var consumeSpeed: Double? = null

        @JsonProperty("foodHaveEatten")
        var foodHaveEatten: Double? = null

        // 2025-12-24 小鸡已经吃的食物量名称是 foodHaveStolen,而不是foodHaveEatten
        @JsonProperty("foodHaveStolen")
        var foodHaveStolen: Double? = null

        @JsonProperty("countdown")
        var animalCountdown: Int? = null

        @JsonProperty("animalStatusVO")
        fun unmarshalAnimalStatusVO(map: MutableMap<String?, Any?>?) {
            if (map != null) {
                this.animalFeedStatus = map["animalFeedStatus"] as String?
                this.animalInteractStatus = map["animalInteractStatus"] as String?
            }
        }
    }

    private class RewardFriend {
        var consistencyKey: String? = null
        var friendId: String? = null
        var time: String? = null
    }

    private class FarmTool {
        var toolType: ToolType? = null
        var toolId: String? = null
        var toolCount: Int = 0
        var toolHoldLimit: Int = 0
    }

    @Suppress("unused")
    interface HireAnimalType {
        companion object {
            const val HIRE: Int = 0
            const val DONT_HIRE: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中雇佣", "选中不雇佣")
        }
    }

    @Suppress("unused")
    interface GetFeedType {
        companion object {
            const val GIVE: Int = 0
            const val RANDOM: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中赠送", "随机赠送")
        }
    }

    interface NotifyFriendType {
        companion object {
            const val NOTIFY: Int = 0
            const val DONT_NOTIFY: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中通知", "选中不通知")
        }
    }

    enum class PropStatus {
        REACH_USER_HOLD_LIMIT, NO_ENOUGH_POINT, REACH_LIMIT;

        fun nickName(): CharSequence? {
            return nickNames[ordinal]
        }

        companion object {
            val nickNames: Array<CharSequence?> =
                arrayOf<CharSequence?>("达到用户持有上限", "乐园币不足", "兑换达到上限")
        }
    }

    suspend fun family() {
        if (StringUtil.isEmpty(familyGroupId)) {
            return
        }
        try {
            var jo = JSONObject(AntFarmRpcCall.enterFamily())
            if (!ResChecker.checkRes(TAG, jo)) return
            familyGroupId = jo.getString("groupId")
            val familyAwardNum = jo.getInt("familyAwardNum")
            val familySignTips = jo.getBoolean("familySignTips")
            //顶梁柱
            jo.getJSONObject("assignFamilyMemberInfo")
            //美食配置
            val eatTogetherConfig = jo.getJSONObject("eatTogetherConfig")
            //扭蛋
            val familyDrawInfo = jo.getJSONObject("familyDrawInfo")
            val familyInteractActions = jo.getJSONArray("familyInteractActions")
            val animals = jo.getJSONArray("animals")
            val familyUserIds: MutableList<String?> = ArrayList()

            for (i in 0..<animals.length()) {
                jo = animals.getJSONObject(i)
                val userId = jo.getString("userId")
                familyUserIds.add(userId)
            }
            if (familySignTips && familyOptions!!.value.contains("familySign")) {
                familySign()
            }
            if (familyAwardNum > 0 && familyOptions!!.value.contains("familyClaimReward")) {
                familyClaimRewardList()
            }

            //帮喂成员
            if (familyOptions!!.value.contains("feedFriendAnimal")) {
                familyFeedFriendAnimal(animals)
            }
            //请吃美食
            if (familyOptions!!.value.contains("eatTogetherConfig")) {
                familyEatTogether(eatTogetherConfig, familyInteractActions, familyUserIds)
            }

            //好友分享
            if (familyOptions!!.value.contains("inviteFriendVisitFamily")) {
                inviteFriendVisitFamily(familyUserIds)
            }
            val drawActivitySwitch = familyDrawInfo.getBoolean("drawActivitySwitch")
            //扭蛋
            if (drawActivitySwitch && familyOptions!!.value.contains("familyDrawInfo")) {
                familyDrawTask(familyUserIds, familyDrawInfo)
            }


        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "family err:",t)
        }
    }

    /**
     * 同步家庭亲密度状态
     * @param groupId 家庭组ID
     */
    private fun syncFamilyStatusIntimacy(groupId: String?) {
        try {
            val userId = UserMap.currentUid
            val jo = JSONObject(AntFarmRpcCall.syncFamilyStatus(groupId, "INTIMACY_VALUE", userId))
            ResChecker.checkRes(TAG, jo)
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "syncFamilyStatusIntimacy 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncFamilyStatus err:",t)
        }
    }

    /**
     * 邀请好友访问家庭
     * @param friendUserIds 好友用户ID列表
     */
    private suspend fun inviteFriendVisitFamily(friendUserIds: MutableList<String?>) {
        try {
            if (Status.hasFlagToday("antFarm::inviteFriendVisitFamily")) {
                return
            }
            val familyValue: Set<String?> = notInviteList!!.value
            if (familyValue.isEmpty()) {
                return
            }
            if (Objects.isNull(friendUserIds) || friendUserIds.isEmpty()) {
                return
            }
            val userIdArray = JSONArray()
            for (u in familyValue) {
                if (!friendUserIds.contains(u) && userIdArray.length() < 6) {
                    userIdArray.put(u)
                }
                if (userIdArray.length() >= 6) {
                    break
                }
            }
            val jo = JSONObject(AntFarmRpcCall.inviteFriendVisitFamily(userIdArray))
            if ("SUCCESS" == jo.getString("memo")) {
                Log.farm("亲密家庭🏠提交任务[分享好友]")
                Status.setFlagToday("antFarm::inviteFriendVisitFamily")
                delay(500)
                syncFamilyStatusIntimacy(familyGroupId)
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "inviteFriendVisitFamily 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "inviteFriendVisitFamily err:",t)
        }
    }

    /**
     * 家庭批量邀请P2P任务
     * @param friendUserIds 好友用户ID列表
     * @param familyDrawInfo 家庭扭蛋信息
     */
    private suspend fun familyBatchInviteP2PTask(
        friendUserIds: MutableList<String?>,
        familyDrawInfo: JSONObject
    ) {
        try {
            if (Status.hasFlagToday("antFarm::familyBatchInviteP2P")) {
                return
            }
            if (Objects.isNull(friendUserIds) || friendUserIds.isEmpty()) {
                return
            }
            val activityId = familyDrawInfo.optString("activityId")
            val sceneCode = "ANTFARM_FD_VISIT_$activityId"
            var jo = JSONObject(AntFarmRpcCall.familyShareP2PPanelInfo(sceneCode))
            if (ResChecker.checkRes(TAG, jo)) {
                val p2PFriendVOList = jo.getJSONArray("p2PFriendVOList")
                if (Objects.isNull(p2PFriendVOList) || p2PFriendVOList.length() <= 0) {
                    return
                }
                val inviteP2PVOList = JSONArray()
                for (i in 0..<p2PFriendVOList.length()) {
                    if (inviteP2PVOList.length() < 6) {
                        val `object` = JSONObject()
                        `object`.put(
                            "beInvitedUserId",
                            p2PFriendVOList.getJSONObject(i).getString("userId")
                        )
                        `object`.put("bizTraceId", "")
                        inviteP2PVOList.put(`object`)
                    }
                    if (inviteP2PVOList.length() >= 6) {
                        break
                    }
                }
                jo = JSONObject(AntFarmRpcCall.familyBatchInviteP2P(inviteP2PVOList, sceneCode))
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.farm("亲密家庭🏠提交任务[好友串门送扭蛋]")
                    Status.setFlagToday("antFarm::familyBatchInviteP2P")
                    delay(500)
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "familyBatchInviteP2PTask 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyBatchInviteP2PTask err:",t)
        }
    }

    /**
     * 家庭扭蛋任务
     * @param friendUserIds 好友用户ID列表
     * @param familyDrawInfo 家庭扭蛋信息
     */
    private suspend fun familyDrawTask(friendUserIds: MutableList<String?>, familyDrawInfo: JSONObject) {
        try {
            val listFarmTask = familyDrawListFarmTask() ?: return
            for (i in 0..<listFarmTask.length()) {
                val jo = listFarmTask.getJSONObject(i)
                val taskStatus = TaskStatus.valueOf(jo.getString("taskStatus"))
                val taskId = jo.optString("taskId")
                val title = jo.optString("title")
                if (taskStatus == TaskStatus.RECEIVED) {
                    continue
                }
                if (taskStatus == TaskStatus.TODO && taskId == "FAMILY_DRAW_VISIT_TASK" && familyOptions!!.value
                        .contains("batchInviteP2P")
                ) {
                    //分享
                    familyBatchInviteP2PTask(friendUserIds, familyDrawInfo)
                    continue
                }
                if (taskStatus == TaskStatus.FINISHED && taskId == "FAMILY_DRAW_FREE_TASK") {
                    //签到
                    familyDrawSignReceiveFarmTaskAward(taskId, title)
                    continue
                }
                delay(1000)
            }
            val jo = JSONObject(AntFarmRpcCall.queryFamilyDrawActivity())
            if (ResChecker.checkRes(TAG, jo)) {
                delay(1000)
                val drawTimes = jo.optInt("familyDrawTimes")
                //碎片个数
                val giftNum = jo.optInt("mengliFragmentCount")
                if (giftNum >= 20 && !Objects.isNull(giftFamilyDrawFragment!!.value)) {
                    giftFamilyDrawFragment(giftFamilyDrawFragment.value, giftNum)
                }
                for (i in 0..<drawTimes) {
                    if (!familyDraw()) {
                        return
                    }
                    delay(1500)
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "familyDrawTask 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDrawTask err:",t)
        }
    }

    private fun giftFamilyDrawFragment(giftUserId: String?, giftNum: Int) {
        try {
            val jo = JSONObject(AntFarmRpcCall.giftFamilyDrawFragment(giftUserId, giftNum))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("亲密家庭🏠赠送扭蛋碎片#" + giftNum + "个#" + giftUserId)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "giftFamilyDrawFragment err:",t)
        }
    }

    private fun familyDrawListFarmTask(): JSONArray? {
        try {
            val jo = JSONObject(AntFarmRpcCall.familyDrawListFarmTask())
            if (ResChecker.checkRes(TAG, jo)) {
                return jo.getJSONArray("farmTaskList")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDrawListFarmTask err:",t)
        }
        return null
    }

    /**
     * 家庭扭蛋抽奖
     * @return 是否还有剩余抽奖次数
     */
    private fun familyDraw(): Boolean {
        try {
            val jo = JSONObject(AntFarmRpcCall.familyDraw())
            if (ResChecker.checkRes(TAG, jo)) {
                val familyDrawPrize = jo.getJSONObject("familyDrawPrize")
                val title = familyDrawPrize.optString("title")
                val awardCount = familyDrawPrize.getString("awardCount")
                val familyDrawTimes = jo.optInt("familyDrawTimes")
                Log.farm("开扭蛋🎟️抽中[$title]#[$awardCount]")
                return familyDrawTimes != 0
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "familyDraw 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDraw err:",t)
        }
        return false
    }

    private suspend fun familyEatTogether(
        eatTogetherConfig: JSONObject,
        familyInteractActions: JSONArray,
        friendUserIds: MutableList<String?>
    ) {
        try {
            var isEat = false
            val periodItemList = eatTogetherConfig.getJSONArray("periodItemList")
            if (Objects.isNull(periodItemList) || periodItemList.length() <= 0) {
                return
            }
            if (!Objects.isNull(familyInteractActions) && familyInteractActions.length() > 0) {
                for (i in 0..<familyInteractActions.length()) {
                    val familyInteractAction = familyInteractActions.getJSONObject(i)
                    if ("EatTogether" == familyInteractAction.optString("familyInteractType")) {
                        return
                    }
                }
            }
            var periodName = ""
            val currentTime = Calendar.getInstance()
            for (i in 0..<periodItemList.length()) {
                val periodItem = periodItemList.getJSONObject(i)
                val startHour = periodItem.optInt("startHour")
                val startMinute = periodItem.optInt("startMinute")
                val endHour = periodItem.optInt("endHour")
                val endMinute = periodItem.optInt("endMinute")
                val startTime = Calendar.getInstance()
                startTime.set(Calendar.HOUR_OF_DAY, startHour)
                startTime.set(Calendar.MINUTE, startMinute)
                val endTime = Calendar.getInstance()
                endTime.set(Calendar.HOUR_OF_DAY, endHour)
                endTime.set(Calendar.MINUTE, endMinute)
                if (currentTime.after(startTime) && currentTime.before(endTime)) {
                    periodName = periodItem.optString("periodName")
                    isEat = true
                    break
                }
            }
            if (!isEat) {
                return
            }
            if (Objects.isNull(friendUserIds) || friendUserIds.isEmpty()) {
                return
            }
            val array = queryRecentFarmFood(friendUserIds.size) ?: return
            val friendUserIdList = JSONArray()
            for (userId in friendUserIds) {
                friendUserIdList.put(userId)
            }
            val jo =
                JSONObject(AntFarmRpcCall.familyEatTogether(familyGroupId, friendUserIdList, array))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("庄园家庭🏠" + periodName + "请客#消耗美食" + friendUserIdList.length() + "份")
                delay(500)
                syncFamilyStatusIntimacy(familyGroupId)
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "familyEatTogether 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyEatTogether err:",t)
        }
    }

    private fun familyDrawSignReceiveFarmTaskAward(taskId: String?, title: String?) {
        try {
            val jo = JSONObject(AntFarmRpcCall.familyDrawSignReceiveFarmTaskAward(taskId))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("亲密家庭🏠扭蛋任务#$title#奖励领取成功")
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "familyDrawSignReceiveFarmTaskAward 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDrawSignReceiveFarmTaskAward err:",t)
        }
    }

    private fun queryRecentFarmFood(queryNum: Int): JSONArray? {
        try {
            val jo = JSONObject(AntFarmRpcCall.queryRecentFarmFood(queryNum))
            if (!ResChecker.checkRes(TAG, jo)) {
                return null
            }
            val cuisines = jo.getJSONArray("cuisines")
            if (Objects.isNull(cuisines) || cuisines.length() == 0) {
                return null
            }
            var count = 0
            for (i in 0..<cuisines.length()) {
                count += cuisines.getJSONObject(i).optInt("count")
            }
            if (count >= queryNum) {
                return cuisines
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "queryRecentFarmFood 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryRecentFarmFood err:",t)
        }
        return null
    }

    private fun familyFeedFriendAnimal(animals: JSONArray) {
        try {
            for (i in 0..<animals.length()) {
                val animal = animals.getJSONObject(i)
                val animalStatusVo = animal.getJSONObject("animalStatusVO")
                if (AnimalInteractStatus.HOME.name == animalStatusVo.getString("animalInteractStatus") && AnimalFeedStatus.HUNGRY.name == animalStatusVo.getString(
                        "animalFeedStatus"
                    )
                ) {
                    val groupId = animal.getString("groupId")
                    val farmId = animal.getString("farmId")
                    val userId = animal.getString("userId")
                    if (!UserMap.getUserIdSet().contains(userId)) {
                        //非好友
                        continue
                    }
                    if (Status.hasFlagToday("farm::feedFriendLimit")) {
                        Log.runtime("今日喂鸡次数已达上限🥣")
                        return
                    }
                    val jo = JSONObject(AntFarmRpcCall.feedFriendAnimal(farmId, groupId))
                    if (ResChecker.checkRes(TAG, jo)) {
                        val feedFood: Int = foodStock - jo.getInt("foodStock")
                        if (feedFood > 0) {
                            add2FoodStock(-feedFood)
                        }
                        Log.farm("庄园家庭🏠帮喂好友🥣[" + UserMap.getMaskName(userId) + "]的小鸡[" + feedFood + "g]#剩余" + foodStock + "g")
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.debug(TAG, "familyFeedFriendAnimal 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyFeedFriendAnimal err:",t)
        }
    }

    /**
     * 点击领取活动食物
     * @param gift 礼物信息对象
     */
    private  fun clickForGiftV2(gift: JSONObject?) {
        if (gift == null) return
        try {
            val resultJson = JSONObject(
                AntFarmRpcCall.clickForGiftV2(
                    gift.getString("foodType"),
                    gift.getInt("giftIndex")
                )
            )
            if (ResChecker.checkRes(TAG, resultJson)) {
                Log.farm("领取活动食物成功," + "已领取" + resultJson.optInt("foodCount"))
            }
        }  catch (e: Exception) {
            Log.printStackTrace(TAG, "clickForGiftV2 err:",e)
        }
    }

    internal class AntFarmFamilyOption(i: String, n: String) : MapperEntity() {
        init {
            id = i
            name = n
        }

        companion object {
            val antFarmFamilyOptions: MutableList<AntFarmFamilyOption?>
                get() {
                    val list: MutableList<AntFarmFamilyOption?> =
                        ArrayList()
                    list.add(AntFarmFamilyOption("familySign", "每日签到"))
                    list.add(AntFarmFamilyOption("eatTogetherConfig", "请吃美食"))
                    list.add(AntFarmFamilyOption("feedFamilyAnimal", "帮喂小鸡"))
                    list.add(AntFarmFamilyOption("deliverMsgSend", "道早安"))
                    list.add(AntFarmFamilyOption("familyClaimReward", "领取奖励"))
                    list.add(AntFarmFamilyOption("inviteFriendVisitFamily", "好友分享"))
                    list.add(AntFarmFamilyOption("assignRights", "使用顶梁柱特权"))
                    list.add(AntFarmFamilyOption("familyDrawInfo", "开扭蛋"))
                    list.add(AntFarmFamilyOption("batchInviteP2P", "串门送扭蛋"))
                    list.add(AntFarmFamilyOption("ExchangeFamilyDecoration", "兑换装修物品"))
                    return list
                }
        }
    }

    companion object {
        private val TAG: String = AntFarm::class.java.getSimpleName()
        private val objectMapper = ObjectMapper()

        /**
         * 小鸡饲料g
         */
        @JvmField
        var foodStock: Int = 0

        @JvmField
        var foodStockLimit: Int = 0

        // 抽抽乐 / 广告任务使用的 referToken（从 VipDataIdMap 读取并缓存）
        private var antFarmReferToken: String? = null

        /**
         * 加载农场抽抽乐广告 referToken
         *
         * AntFarmReferToken：
         *  - 如果本地已有缓存，直接返回
         *  - 否则从 VipDataIdMap 加载当前账号下保存的 AntFarmReferToken
         */
        @JvmStatic
        fun loadAntFarmReferToken(): String? {
            if (!antFarmReferToken.isNullOrEmpty()) return antFarmReferToken

            val uid = UserMap.currentUid
            val vipData = IdMapManager.getInstance(VipDataIdMap::class.java)
            vipData.load(uid)
            antFarmReferToken = vipData.get("AntFarmReferToken")
            return antFarmReferToken
        }

        init {
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private const val FARM_ANSWER_CACHE_KEY = "farmAnswerQuestionCache"
        private const val ANSWERED_FLAG = "farmQuestion::answered" // 今日是否已答题
        private const val CACHED_FLAG = "farmQuestion::cache" // 是否已缓存明日答案
    }

    /**
     * 通用闹钟设置方法
     * @param type 任务类型，如 "KC" (赶鸡), "AW" (起床), "AS" (睡觉)
     * @param delayMillis 延迟毫秒数
     */
    private fun setAlarm(type: String, delayMillis: Long) {
        try {
            val context = fansirsqi.xposed.sesame.SesameApplication.getContext()
                ?: fansirsqi.xposed.sesame.hook.ApplicationHook.getAppContext()
                ?: return

            // 使用框架统一的调度器
            val scheduler = CoroutineScheduler(context)
            val triggerAtMillis = System.currentTimeMillis() + delayMillis

            // requestCode 依然保持唯一性
            val requestCode = (type + ownerFarmId).hashCode()

            // 注意：CoroutineScheduler.scheduleWakeupAlarm 发送的是固定 Action
            // 如果想保留 AntFarmReceiver 的独立逻辑，我们需要对 CoroutineScheduler 做一点点扩展
            // 或者简单点，直接在 AntFarm 中复用它的逻辑：

            scheduler.scheduleWakeupAlarm(
                triggerAtMillis = triggerAtMillis,
                requestCode = requestCode,
                isMainAlarm = false,
                customAction = "ACTION_ALARM_FARM"
            )

        } catch (e: Exception) {
            Log.error(TAG, "设置闹钟[${type}]失败: ${e.message}")
        }
    }
}