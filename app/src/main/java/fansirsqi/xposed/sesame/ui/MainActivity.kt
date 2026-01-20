package fansirsqi.xposed.sesame.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.SesameApplication.Companion.hasPermissions
import fansirsqi.xposed.sesame.ui.extension.WatermarkLayer
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.ui.extension.openUrl
import fansirsqi.xposed.sesame.ui.extension.performNavigationToSettings
import fansirsqi.xposed.sesame.ui.screen.MainScreen
import fansirsqi.xposed.sesame.ui.theme.AppTheme
import fansirsqi.xposed.sesame.ui.theme.ThemeManager
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel
import fansirsqi.xposed.sesame.util.CommandUtil
import fansirsqi.xposed.sesame.util.Detector
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.IconManager
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.PermissionUtil
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var animalStatusObserver: FileObserver? = null

    // Shizuku 监听器
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ToastUtil.showToast(this, "Shizuku 授权成功！")

                // 关键修改：
                lifecycleScope.launch {
                    CommandUtil.executeCommand(this@MainActivity, "echo init_shizuku")
                    delay(200)
                    viewModel.refreshDeviceInfo(this@MainActivity)
                }
            } else {
                ToastUtil.showToast(this, "Shizuku 授权被拒绝")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // 2. 检查权限并初始化逻辑
        hasPermissions = PermissionUtil.checkOrRequestFilePermissions(this)
        if (hasPermissions) {
            viewModel.initAppLogic()
            initNativeDetector()
        } else {
            PermissionUtil.checkOrRequestFilePermissions(this)
        }

        // 3. 初始化 Shizuku
        setupShizuku()

        if (hasPermissions) {
            startObservingAnimalStatus()
        }

        // 4. 同步图标状态
        val prefs = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE)
        IconManager.syncIconState(this, prefs.getBoolean("is_icon_hidden", false))


        // 5. 设置 Compose 内容
        setContent {
// 收集 ViewModel 状态
//            val oneWord by viewModel.oneWord.collectAsStateWithLifecycle()
            val activeUser by viewModel.activeUser.collectAsStateWithLifecycle()
            val animalStatus by viewModel.animalStatus.collectAsStateWithLifecycle()
            val onlyOnceDaily by viewModel.onlyOnceDaily.collectAsStateWithLifecycle()
            val autoHandleOnceDaily by viewModel.autoHandleOnceDaily.collectAsStateWithLifecycle()
            val isFinishedToday by viewModel.isFinishedToday.collectAsStateWithLifecycle()
            val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
            //  获取实时的 UserEntity 列表
            val userList by viewModel.userList.collectAsStateWithLifecycle()
            // 使用 derivedStateOf 优化性能，只在 userList 变化时重新映射
            val uidList by remember {
                derivedStateOf { userList.map { it.userId } }
            }
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()

            // AppTheme 会处理状态栏颜色
            AppTheme(dynamicColor = isDynamicColor) {
                WatermarkLayer(
                    uidList = uidList
                ) {
                    MainScreen(
                        animalStatus = animalStatus,
                        activeUserName = activeUser?.showName ?: "未载入",
                        moduleStatus = moduleStatus,
                        viewModel = viewModel,
                        isDynamicColor = isDynamicColor, // 传给 MainScreen
                        // 传入回调
                        onlyOnceDaily = onlyOnceDaily,
                        autoHandleOnceDaily = autoHandleOnceDaily,
                        isFinishedToday = isFinishedToday,
                        userList = userList, // 传入列表
                        // 🔥 处理跳转逻辑
                        onNavigateToSettings = { selectedUser ->
                            performNavigationToSettings(selectedUser)
                        },
                        onEvent = { event -> handleEvent(event, userList, activeUser) } // 处理点击事件
                    )
                }
            }
        }
    }

    companion object {
        private var isDetectorInitialized = false
    }
    // 🔥 新增：在 Activity 中执行 Native 检测
    private fun initNativeDetector() {
        if (isDetectorInitialized) return // 防止重复初始化
        try {
            if (Detector.loadLibrary("checker")) {
                Detector.initDetector(this)
                isDetectorInitialized = true
            }
        } catch (e: Exception) {
            Log.error("MainActivity", "Native detector init failed: ${e.message}")
        }
    }

    /**
     * 定义 UI 事件
     */
    sealed class MainUiEvent {
        //        data object RefreshOneWord : MainUiEvent()
        data object OpenForestLog : MainUiEvent()
        data object OpenFarmLog : MainUiEvent()
        data object OpenGithub : MainUiEvent()
        data object OpenErrorLog : MainUiEvent()
        data object OpenOtherLog : MainUiEvent()
        data object OpenAllLog : MainUiEvent()
        data object OpenDebugLog : MainUiEvent()
//        data object OpenSettings : MainUiEvent()
        data object ManualRun : MainUiEvent()
        data object ManualStop : MainUiEvent()
        data object AnimanStatus : MainUiEvent()
        data object ToggleOnlyOnceDaily : MainUiEvent()

        // 🔥 新增菜单相关事件
        data class ToggleIconHidden(val isHidden: Boolean) : MainUiEvent()
        data object OpenCaptureLog : MainUiEvent()
        data object ToggleDebugMode : MainUiEvent() // 🔥 新增抓包开关事件
        data object OpenExtend : MainUiEvent()
        data object ClearConfig : MainUiEvent()
        data object OpenDataStore : MainUiEvent()
    }

    /**
     * 统一处理事件
     */
    private fun handleEvent(event: MainUiEvent, userList: List<UserEntity>, activeUser: UserEntity?) {
        when (event) {
//            MainUiEvent.RefreshOneWord -> {viewModel.fetchOneWord()}
            MainUiEvent.OpenForestLog -> openLogFile(Files.getForestLogFile())
            MainUiEvent.OpenFarmLog -> openLogFile(Files.getFarmLogFile())
            MainUiEvent.OpenOtherLog -> openLogFile(Files.getOtherLogFile())
            MainUiEvent.OpenGithub -> openUrl("https://github.com/Fansirsqi/Sesame-TK")
            MainUiEvent.OpenErrorLog -> openLogFile(Files.getErrorLogFile())
            MainUiEvent.ManualRun -> {
                // 上游标准：发送 restart 广播来重新初始化并启动任务
                val intent = Intent("com.eg.android.AlipayGphone.sesame.restart")
                sendBroadcast(intent)
                Toast.makeText(this, "🚀 已尝试启动/重载模块", Toast.LENGTH_SHORT).show()
            }
            MainUiEvent.ManualStop -> {
                val stopIntent = Intent("com.eg.android.AlipayGphone.sesame.stop")
                sendBroadcast(stopIntent)
                Toast.makeText(this, "🛑 已发送打断指令", Toast.LENGTH_SHORT).show()
            }
            MainUiEvent.AnimanStatus -> {
                viewModel.loadAnimalStatus()
                startObservingAnimalStatus()
            }
            MainUiEvent.ToggleOnlyOnceDaily -> {
                viewModel.toggleOnlyOnceDaily()
            }
            MainUiEvent.OpenAllLog -> openLogFile(Files.getRecordLogFile())
            MainUiEvent.OpenDebugLog -> openLogFile(Files.getDebugLogFile())
            is MainUiEvent.ToggleIconHidden -> {
                val shouldHide = event.isHidden
                getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE).edit { putBoolean("is_icon_hidden", shouldHide) }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
            }

            MainUiEvent.OpenCaptureLog -> openLogFile(Files.getCaptureLogFile())
            MainUiEvent.ToggleDebugMode -> { // 🔥 处理抓包开关
                BaseModel.debugMode.value = !BaseModel.debugMode.value
                val uid = activeUser?.userId
                if (!uid.isNullOrEmpty()) {
                    Config.save(uid, true)
                    // 🔥 发送广播通知模块重载配置，解决开启抓包无效果的问题
                    val intent = Intent("com.eg.android.AlipayGphone.sesame.restart")
                    intent.putExtra("userId", uid)
                    sendBroadcast(intent)
                }
                val status = if (BaseModel.debugMode.value) "已开启" else "已关闭"
                Toast.makeText(this, "抓包功能$status", Toast.LENGTH_SHORT).show()
            }
            MainUiEvent.OpenExtend -> startActivity(Intent(this, _root_ide_package_.fansirsqi.xposed.sesame.ui.ExtendActivity::class.java))
            MainUiEvent.ClearConfig -> {
                // 🔥 这里只负责执行逻辑，不再负责弹窗
                if (Files.delFile(Files.CONFIG_DIR)) {
                    ToastUtil.showToast(this, "🙂 清空配置成功")
                    // 可选：重载配置或刷新 UI
                    viewModel.reloadUserConfigs()
                } else {
                    ToastUtil.showToast(this, "😭 清空配置失败")
                }
            }

            MainUiEvent.OpenDataStore -> openLogFile(Files.getDataStoreFile())

        }
    }

    // --- 辅助方法 (替代 BaseActivity) ---

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (Shizuku.pingBinder() && checkSelfPermission(ShizukuProvider.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1234)
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermissions = PermissionUtil.checkFilePermissions(this)
        if (hasPermissions) {
            startObservingAnimalStatus()
            viewModel.reloadUserConfigs()

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        animalStatusObserver?.stopWatching()
        animalStatusObserver = null
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }

    private fun openLogFile(logFile: File) {
        if (!logFile.exists()) {
            ToastUtil.showToast(this, "日志文件不存在: ${logFile.name}")
            return
        }
        val intent = Intent(this, LogViewerActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }

    private fun executeWithVerification(block: () -> Unit) {
        // 如果需要生物识别验证，可以在这里添加逻辑
        // 目前直接执行
        block()
    }

    /**
     * 核心逻辑：启动文件监听并更新 TextView*/
    fun startObservingAnimalStatus() {
        val logFile = Files.getAnimalStatusLogFile() ?: return

        // 如果已经有观察者了，直接触发一次加载即可，不要重复创建观察者
        if (animalStatusObserver != null) {
            viewModel.loadAnimalStatus()
            return
        }

        // 初始加载
//        viewModel.loadAnimalStatus()

        val parentDir = logFile.parentFile ?: return
        val mask = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.MOVED_TO

        animalStatusObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(parentDir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == logFile.name) {
                        viewModel.loadAnimalStatus()
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(logFile.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    viewModel.loadAnimalStatus()
                }
            }
        }
        animalStatusObserver?.startWatching()
    }
}
