package fansirsqi.xposed.sesame.task.antFarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.task.CoroutineTaskRunner

class AntFarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if ("ACTION_ALARM_FARM" == intent.action) {
            val type = intent.getStringExtra("type") ?: "UNKNOWN"
            Log.record("AntFarmReceiver", "⏰ 收到系统闹钟[${type}]唤醒")

            try {
                val antFarm = Model.getModel(AntFarm::class.java)
                if (antFarm != null && antFarm.isEnable) {
                    // 统一启动庄园任务，AntFarm.run() 内部会处理所有子任务
                    antFarm.startTask(force = true, rounds = 1)
                } else {
                    Log.record("AntFarmReceiver", "庄园任务未启用，跳过闹钟触发")
                }
            } catch (e: Exception) {
                Log.printStackTrace("AntFarmReceiver", e)
            }
        }
    }
}