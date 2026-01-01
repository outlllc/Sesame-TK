package fansirsqi.xposed.sesame.model

import fansirsqi.xposed.sesame.task.AnswerAI.AnswerAI
import fansirsqi.xposed.sesame.task.EcoProtection.EcoProtection
import fansirsqi.xposed.sesame.task.antCooperate.AntCooperate
import fansirsqi.xposed.sesame.task.antDodo.AntDodo
import fansirsqi.xposed.sesame.task.antFarm.AntFarm
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.antMember.AntMember
import fansirsqi.xposed.sesame.task.antOcean.AntOcean
import fansirsqi.xposed.sesame.task.antOrchard.AntOrchard
import fansirsqi.xposed.sesame.task.antSports.AntSports
import fansirsqi.xposed.sesame.task.antStall.AntStall
import fansirsqi.xposed.sesame.task.reserve.Reserve

import fansirsqi.xposed.sesame.task.greenFinance.GreenFinance

object ModelOrder {
    private val array = arrayOf(
        BaseModel::class.java,       // 基础设置
        CustomSettings::class.java,  // 自定义设置
        AntForest::class.java,       // 森林
        AntFarm::class.java,         // 庄园
        AntOcean::class.java,        // 海洋
        AntOrchard::class.java,    // 农场
        AntStall::class.java,      // 蚂蚁新村
        AntDodo::class.java,       // 神奇物种
        AntCooperate::class.java,    // 合种
        AntSports::class.java,       // 运动
        AntMember::class.java,     // 会员
        EcoProtection::class.java,     // 古树
        GreenFinance::class.java,  // 绿色经营
        Reserve::class.java,       // 保护地
        AnswerAI::class.java         // AI答题
        //ConsumeGold::class.java,   // 消费金
        //OmegakoiTown::class.java,  // 小镇
    )

    val allConfig: List<Class<out Model>> = array.toList()
}