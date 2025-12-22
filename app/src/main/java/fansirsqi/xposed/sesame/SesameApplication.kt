package fansirsqi.xposed.sesame

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import fansirsqi.xposed.sesame.service.CommandService
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil

/**
 * 芝麻粒应用主类
 *
 * 负责应用初始化
 */
class SesameApplication : Application() {

    companion object {
        private const val TAG = "SesameApplication"
        var preferencesKey = "sesame-tk"
        var hasPermissions: Boolean = false

        @SuppressLint("StaticFieldLeak")
        private var instance: SesameApplication? = null

        /**
         * 获取应用全局 Context
         */
        @JvmStatic
        fun getContext(): Context? {
            return instance
        }
    }

    override fun onCreate() {
        super.onCreate()
        ToastUtil.init(this) // 初始化全局 Context

        Log.init(this)
        instance = this

        val processName = getCurrentProcessName()
        Log.runtime(TAG, "🚀 应用启动 | 进程: $processName | PID: ${Process.myPid()}")

        // 启动 CommandService
        startCommandService()
    }

    /**
     * 启动 CommandService
     */
    private fun startCommandService() {
        try {
            val intent = Intent(this, CommandService::class.java)
            startService(intent)
            Log.runtime(TAG, "✅ CommandService 已启动")
        } catch (e: Exception) {
            Log.runtime(TAG, "❌ CommandService 启动失败: ${e.message}")
        }
    }

    /**
     * 获取当前进程名
     */
    private fun getCurrentProcessName(): String {
        return try {
            // Android 9.0+ 可直接获取
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                getProcessName()
            } else {
                // 通过读取 /proc/self/cmdline 获取
                val pid = Process.myPid()
                val cmdlineFile = java.io.File("/proc/$pid/cmdline")
                if (cmdlineFile.exists()) {
                    cmdlineFile.readText().trim('\u0000')
                } else {
                    packageName
                }
            }
        } catch (e: Exception) {
            packageName
        }
    }
}