package fansirsqi.xposed.sesame.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Agriculture
import androidx.compose.material.icons.rounded.AlignVerticalTop
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.SesameApplication.Companion.hasPermissions
import fansirsqi.xposed.sesame.SesameApplication.Companion.preferencesKey
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.newui.DeviceInfoCard
import fansirsqi.xposed.sesame.newui.DeviceInfoUtil
import fansirsqi.xposed.sesame.newui.WatermarkLayer
import fansirsqi.xposed.sesame.newutil.IconManager
import fansirsqi.xposed.sesame.ui.MainViewModel.Companion.verifyId
import fansirsqi.xposed.sesame.ui.log.LogViewerComposeActivity
import fansirsqi.xposed.sesame.ui.theme.AppTheme
import fansirsqi.xposed.sesame.util.Detector
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.PermissionUtil
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

class MainActivity : BaseActivity() {

    private val viewModel: MainViewModel by viewModels()
//    private lateinit var watermarkView: WatermarkView

    private var animalStatusObserver: FileObserver? = null

    // Shizuku 监听器
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功！" else "Shizuku 授权被拒绝"
            ToastUtil.showToast(this, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 检查权限并初始化逻辑
        hasPermissions = PermissionUtil.checkOrRequestFilePermissions(this)
        if (hasPermissions) {
            viewModel.initAppLogic()
            // 🔥 修复：Native 检测必须在 Activity 中调用
            initNativeDetector()
        } else {
            PermissionUtil.checkOrRequestFilePermissions(this)
        }

        // 2. 初始化 Shizuku
        setupShizuku()

        // 3. 同步图标状态

        if (hasPermissions) {
            startObservingAnimalStatus()
        }

        val prefs = getSharedPreferences(preferencesKey, MODE_PRIVATE)
        IconManager.syncIconState(this, prefs.getBoolean("is_icon_hidden", false))


        // 5. 设置 Compose 内容 (替代 setContentView)
        setContent {
// 收集 ViewModel 状态
//            val oneWord by viewModel.oneWord.collectAsStateWithLifecycle()
            val activeUser by viewModel.activeUser.collectAsStateWithLifecycle()
            val userList by viewModel.userList.collectAsStateWithLifecycle()
            val animalStatus by viewModel.animalStatus.collectAsStateWithLifecycle()
            val onlyOnceDaily by viewModel.onlyOnceDaily.collectAsStateWithLifecycle()
            val isFinishedToday by viewModel.isFinishedToday.collectAsStateWithLifecycle()
            // ✨ 1. 从 ViewModel 收集模块状态
            val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()


            AppTheme {
                WatermarkLayer {
                    MainScreen(
                        animalStatus = animalStatus,
                        activeUserName = activeUser?.showName ?: "未载入^o^ 重启支付宝看看👀",
                        moduleStatus = moduleStatus, // ✨ 传递状态
                        viewModel = viewModel,
                        onlyOnceDaily = onlyOnceDaily,
                        isFinishedToday = isFinishedToday,
                        onEvent = { event -> handleEvent(event, userList, activeUser) } // 处理点击事件
                    )
                }
            }
        }

//        WatermarkView.install(activity = this)
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
     * 定义 UI 事件，解耦逻辑
     */
    sealed class MainUiEvent {
        //        data object RefreshOneWord : MainUiEvent()
        data object OpenForestLog : MainUiEvent()
        data object OpenFarmLog : MainUiEvent()
        data object OpenGithub : MainUiEvent()
        data object OpenErrorLog : MainUiEvent()
        data object OpenOtherLog : MainUiEvent()
        data object OpenAllLog : MainUiEvent()
        data object OpenSettings : MainUiEvent()
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
                val intent = Intent("com.eg.android.AlipayGphone.sesame.execute")
                intent.putExtra("manual_trigger", true) // 增加此标记
                sendBroadcast(intent)
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
            MainUiEvent.OpenSettings -> {
                showUserSelectionDialog(userList) { selectedUser ->
                    navigateToSettings(selectedUser)
                }
            }

            // 🔥 新增菜单逻辑处理
            is MainUiEvent.ToggleIconHidden -> {
                val shouldHide = event.isHidden
                getSharedPreferences(preferencesKey, MODE_PRIVATE).edit { putBoolean("is_icon_hidden", shouldHide) }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
            }

            MainUiEvent.OpenCaptureLog -> openLogFile(Files.getCaptureLogFile())

            MainUiEvent.ToggleDebugMode -> { // 🔥 处理抓包开关
                BaseModel.debugMode.value = !BaseModel.debugMode.value
                val uid = activeUser?.userId
                if (!uid.isNullOrEmpty()) {
                    Config.save(uid, true)
                }
                val status = if (BaseModel.debugMode.value) "已开启" else "已关闭"
                Toast.makeText(this, "抓包功能$status", Toast.LENGTH_SHORT).show()
            }

            MainUiEvent.OpenExtend -> startActivity(Intent(this, ExtendActivity::class.java))
            MainUiEvent.ClearConfig -> {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 警告")
                    .setMessage("🤔 确认清除所有模块配置？")
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (Files.delFile(Files.CONFIG_DIR)) Toast.makeText(this, "🙂 清空配置成功", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "😭 清空配置失败", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                    .show()
            }


        }
    }

    // --- 业务逻辑保留 ---

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


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
//        watermarkView.refresh()
    }

    private fun openLogFile(logFile: File) {
        if (!logFile.exists()) {
            ToastUtil.showToast(this, "日志文件不存在: ${logFile.name}")
            return
        }
        val intent = Intent(this, LogViewerComposeActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }

    // --- 菜单逻辑保留 (BaseActivity 依赖) ---
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        try {
            val pm = packageManager
            val defaultComp = ComponentName(this, IconManager.COMPONENT_DEFAULT)
            val christmasComp = ComponentName(this, IconManager.COMPONENT_CHRISTMAS)

            val isDefault = pm.getComponentEnabledSetting(defaultComp) in listOf(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            )
            val isChristmas = pm.getComponentEnabledSetting(christmasComp) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val isIconVisible = isDefault || isChristmas

            menu.add(0, 1, 1, R.string.hide_the_application_icon).setCheckable(true).isChecked = !isIconVisible
            menu.add(0, 2, 2, R.string.view_capture)
            menu.add(0, 3, 3, R.string.extend)
            if (BuildConfig.DEBUG) menu.add(0, 4, 4, "清除配置")
        } catch (e: Exception) {
            Log.printStackTrace(e)
            return false
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val shouldHide = !item.isChecked
                item.isChecked = shouldHide
                getSharedPreferences(preferencesKey, MODE_PRIVATE).edit { putBoolean("is_icon_hidden", shouldHide) }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
                return true
            }

            2 -> openLogFile(Files.getCaptureLogFile())
            3 -> {
                startActivity(Intent(this, ExtendActivity::class.java))
                return true
            }

            4 -> {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 警告")
                    .setMessage("🤔 确认清除所有模块配置？")
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (Files.delFile(Files.CONFIG_DIR)) Toast.makeText(this, "🙂 清空配置成功", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "😭 清空配置失败", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCard(
    status: MainViewModel.ModuleStatus,
    expanded: Boolean, // ✨ 接收展开状态
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor =
                when (status) {
                    is MainViewModel.ModuleStatus.Activated -> MaterialTheme.colorScheme.secondaryContainer
                    is MainViewModel.ModuleStatus.NotActivated -> MaterialTheme.colorScheme.errorContainer
                    is MainViewModel.ModuleStatus.Loading -> MaterialTheme.colorScheme.surfaceVariant
                }
        )
    ) {
        // 使用 Column 包裹所有内容，以便添加可展开部分
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // --- 顶部固定显示部分 ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (status) {
                    is MainViewModel.ModuleStatus.Activated -> {
                        Icon(Icons.Outlined.CheckCircle, "已激活")
                        Column(Modifier.padding(start = 20.dp)) {
                            Text(text = "${status.frameworkName} ${status.frameworkVersion}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(text = "Actived API ${status.apiVersion}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    is MainViewModel.ModuleStatus.NotActivated -> {
                        Icon(Icons.Outlined.Warning, "未激活")
                        Column(Modifier.padding(start = 20.dp)) {
                            Text(text = "如果你是免root用户,请忽略此状态", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(text = "点击展开帮助", style = MaterialTheme.typography.bodyMedium) // ✨ 提示语更新
                        }
                    }

                    is MainViewModel.ModuleStatus.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Column(Modifier.padding(start = 20.dp)) {
                            Text(text = "正在检查模块状态...", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            // --- ✨ 可展开的帮助信息部分 ---
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(300))
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = "故障排查指南",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "请确认您已在 LSPosed Manager (或类似框架) 中：\n1. 启用了本模块。\n2. 在作用域中勾选了支付宝。\n3. 重启了支付宝进程。",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

/**
 * 纯 Compose UI 实现
 * 不再依赖 XML，直接在这里构建界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
//    oneWord: String,
    activeUserName: String,
    animalStatus: String,
    moduleStatus: MainViewModel.ModuleStatus, // ✨ 接收状态
    viewModel: MainViewModel, // 建议直接传 VM 或者把 isLoading 传进来
    onlyOnceDaily: Boolean,
    isFinishedToday: Boolean,
    onEvent: (MainActivity.MainUiEvent) -> Unit,
) {
//    ✨ 3. 在 MainScreen 中管理 StatusCard 的展开状态
    var isStatusCardExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

//    val isOneWordLoading by viewModel.isOneWordLoading.collectAsStateWithLifecycle()//获取一言加载状态

    // 获取当前图标隐藏状态 (从 SP 读取，这里简单用 remember 读取一次，更严谨应该从 ViewModel 读)
    val prefs = context.getSharedPreferences(preferencesKey, Context.MODE_PRIVATE)
    var isIconHidden by remember { mutableStateOf(prefs.getBoolean("is_icon_hidden", false)) }

    // 控制下拉菜单显示
    var showMenu by remember { mutableStateOf(false) }

    // 🔥 新增：管理日志显示的滚动状态
    val scrollState = androidx.compose.foundation.rememberScrollState()

    // 🔥 新增：当日志内容更新时，自动滚动到底部
    LaunchedEffect(animalStatus) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // 异步加载设备信息，启动后自动更新3次
    val deviceInfoMap by produceState<Map<String, String>?>(initialValue = null) {
        value = DeviceInfoUtil.showInfo(verifyId, context)

        repeat(1) {
            delay(200)
            value = DeviceInfoUtil.showInfo(verifyId, context)
        }
    }

    Scaffold(
        // 标题栏
        topBar = {
            CenterAlignedTopAppBar(
                title = {
//                    Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    Text(
                        text = "当前载入: $activeUserName",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
//                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                // 🔥 添加右侧菜单按钮
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }

                    // 下拉菜单
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // 1. 隐藏/显示图标
                        DropdownMenuItem(
                            text = { Text(if (isIconHidden) "显示应用图标" else "隐藏应用图标") },
                            onClick = {
                                isIconHidden = !isIconHidden
                                onEvent(MainActivity.MainUiEvent.ToggleIconHidden(isIconHidden))
                                showMenu = false
                            }
                        )
                        // 2. 查看抓包
                        DropdownMenuItem(
                            text = { Text("查看抓包") },
                            onClick = {
                                onEvent(MainActivity.MainUiEvent.OpenCaptureLog)
                                showMenu = false
                            }
                        )
                        // 🔥 3. 开启/关闭抓包 (新增加)
                        DropdownMenuItem(
                            text = { Text(if (BaseModel.debugMode.value) "关闭抓包" else "开启抓包") },
                            onClick = {
                                onEvent(MainActivity.MainUiEvent.ToggleDebugMode)
                                showMenu = false
                            }
                        )
                        // 4. 扩展功能
                        DropdownMenuItem(
                            text = { Text("扩展功能") },
                            onClick = {
                                onEvent(MainActivity.MainUiEvent.OpenExtend)
                                showMenu = false
                            }
                        )
                        // 5. 清除配置 (仅 Debug 模式显示)
                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(
                                text = { Text("清除配置") },
                                onClick = {
                                    onEvent(MainActivity.MainUiEvent.ClearConfig)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            )
        },
    ) { innerPadding ->
        // ... (Body 内容保持不变) ...
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ... 设备信息卡片 + 一言 ...
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

//                StatusCard(
//                    status = moduleStatus,
//                    expanded = isStatusCardExpanded,
//                    onClick = {
//                        // ✨ 点击时，仅当未激活状态才切换展开状态
//                        if (moduleStatus is MainViewModel.ModuleStatus.NotActivated) {
//                            isStatusCardExpanded = !isStatusCardExpanded
//                        } else {
////                            ToastUtil.showToast(oneWord)
//                            // 对于已激活状态，可以考虑弹一个 Toast
//                            // (为了简单，这里暂时不做任何事)
//                        }
//                    }
//                )


                Box(modifier = Modifier.offset(y = (-10).dp)) {
                    if (deviceInfoMap != null) {
                        DeviceInfoCard(deviceInfoMap!!)
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp)
                    .fillMaxWidth()
                    .heightIn(max = 255.dp) // 🔥 限制最大显示高度（约 12-14 行视觉高度）
                    .verticalScroll(scrollState)
                    .clickable { onEvent(MainActivity.MainUiEvent.AnimanStatus) }
                    .padding(8.dp)
            ) {
                Text(
                    text = animalStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // 手动启停
            Row(
                modifier = Modifier
                    .fillMaxWidth() // 占满宽度以便靠右对齐
                    .padding(bottom = 0.dp), // 与下方控件距离设为 0dp
                horizontalArrangement = Arrangement.End, // 关键：子项向右对齐
                verticalAlignment = Alignment.CenterVertically // 垂直居中对齐
            ) {
                // 🔥 核心逻辑：根据 开启状态 和 执行状态 动态计算文案与颜色
                val (statusText, statusColor) = when {
                    !onlyOnceDaily -> "单次已关闭" to Color(0xFFF44336) // 红色
                    isFinishedToday -> "今日已完成" to Color(0xFF2196F3) // 蓝色 (表示由于单次运行设置而跳过)
                    else -> "单次已启用" to Color(0xFF4CAF50) // 绿色
                }
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { onEvent(MainActivity.MainUiEvent.ToggleOnlyOnceDaily) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    text = "手动停止",
                    color = Color(0xFFF44336),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { onEvent(MainActivity.MainUiEvent.ManualStop) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    text = "手动开始",
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.labelMedium, // 关键：与“森林日志”字体大小一致
                    modifier = Modifier
                        .clickable { onEvent(MainActivity.MainUiEvent.ManualRun) }
                        .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp) // 手动开始在最右
                )
            }

            // ... 底部按钮 ...
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ... 第一行按钮 ...
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MenuButton(
                        text = "森林日志",
                        icon = Icons.Rounded.Forest,
                        modifier = Modifier.weight(1f)
                    ) { onEvent(MainActivity.MainUiEvent.OpenForestLog) }

                    MenuButton(
                        text = "农场日志",
                        icon = Icons.Rounded.Agriculture,
                        modifier = Modifier.weight(1f)
                    ) { onEvent(MainActivity.MainUiEvent.OpenFarmLog) }

                    MenuButton(
                        text = "其他日志",
                        icon = Icons.Rounded.AlignVerticalTop,
                        modifier = Modifier.weight(1f)
                    ) { onEvent(MainActivity.MainUiEvent.OpenOtherLog) }
                }

                // ... 第二行按钮 ...
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MenuButton(
                        text = "错误日志",
                        icon = Icons.Rounded.BugReport,
                        modifier = Modifier.weight(1f)
                    ) { onEvent(MainActivity.MainUiEvent.OpenErrorLog) }

                    MenuButton(
                        text = "全部日志",
                        icon = Icons.Rounded.Description,
                        modifier = Modifier.weight(1f)
                    ) { onEvent(MainActivity.MainUiEvent.OpenAllLog) }

                    MenuButton(
                        text = "设置",
                        icon = Icons.Rounded.Settings,
                        modifier = Modifier.weight(1f)
                    ) { onEvent(MainActivity.MainUiEvent.OpenSettings) }
                }
            }
        }
    }
}


/**
 * 封装的 M3 风格按钮组件
 */
@Composable
fun MenuButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(80.dp), // 固定高度
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant, //background
            contentColor = MaterialTheme.colorScheme.primary

        ),
        elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 2.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(

                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}
