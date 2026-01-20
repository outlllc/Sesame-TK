package fansirsqi.xposed.sesame.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.CustomSettings
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.service.ConnectionState
import fansirsqi.xposed.sesame.service.LsposedServiceManager
import fansirsqi.xposed.sesame.ui.screen.DeviceInfoUtil
import fansirsqi.xposed.sesame.util.AssetUtil
import fansirsqi.xposed.sesame.util.CommandUtil
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.DirectoryWatcher
import fansirsqi.xposed.sesame.util.FansirsqiUtil
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.IconManager
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.StatusManager
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 ViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {



    // --- 内部状态定义 ---
    sealed class ModuleStatus {
        data object Loading : ModuleStatus()
        data object NotActivated : ModuleStatus()
        data class Activated(
            val frameworkName: String,     // 框架名称 (LSPosed, LSPatch...)
            val frameworkVersion: String,  // 版本号 (LSPosed才有，其他可能为空)
            val apiVersion: Int            // API版本
        ) : ModuleStatus()
    }

    // 1. 定义服务状态 (Root/Shizuku/None)
    sealed class ServiceStatus {
        data object Loading : ServiceStatus()
        data class Active(val type: String) : ServiceStatus() // type = "Root" or "Shizuku"
        data object Inactive : ServiceStatus()
    }

    companion object {
        const val TAG = "MainViewModel"
        var verifuids = FansirsqiUtil.getFolderList(Files.CONFIG_DIR.absolutePath)
    }

    // 1. 定义状态
    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)


    private val _serviceStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Loading)
    val serviceStatus = _serviceStatus.asStateFlow()

    // --- StateFlows ---

//    private val _oneWord = MutableStateFlow("正在获取句子...")
//    val oneWord: StateFlow<String> = _oneWord.asStateFlow()

//    private val _isOneWordLoading = MutableStateFlow(false)
//    val isOneWordLoading = _isOneWordLoading.asStateFlow()

    private val _moduleStatus = MutableStateFlow<ModuleStatus>(ModuleStatus.Loading)
    val moduleStatus: StateFlow<ModuleStatus> = _moduleStatus.asStateFlow()

    private val _activeUser = MutableStateFlow<UserEntity?>(null)
    val activeUser: StateFlow<UserEntity?> = _activeUser.asStateFlow()

    private val _userList = MutableStateFlow<List<UserEntity>>(emptyList())
    val userList: StateFlow<List<UserEntity>> = _userList.asStateFlow()

    private val _deviceInfo = MutableStateFlow<Map<String, String>?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    // --- 监听器 ---

    // 监听 LSPosed 服务连接 (仅用于更新详细版本信息)
    private val serviceListener: (ConnectionState) -> Unit = { _ ->
        checkServiceState()
    }

    private var isInitialized = false

    private val _animalStatus = MutableStateFlow("正在加载动物状态日志...")
    val animalStatus: StateFlow<String> = _animalStatus.asStateFlow()

    // 每日单次运行状态
    private val _onlyOnceDaily = MutableStateFlow(CustomSettings.onlyOnceDaily.value)
    val onlyOnceDaily: StateFlow<Boolean> = _onlyOnceDaily.asStateFlow()

    // 每日单次自动处理状态
    private val _autoHandleOnceDaily = MutableStateFlow(CustomSettings.autoHandleOnceDaily.value)
    val autoHandleOnceDaily: StateFlow<Boolean> = _autoHandleOnceDaily.asStateFlow()

    // 每日单次运行执行标志
    private val _isFinishedToday = MutableStateFlow(false)
    val isFinishedToday: StateFlow<Boolean> = _isFinishedToday.asStateFlow()

    fun initAppLogic() {
        if (isInitialized) return
        isInitialized = true

        viewModelScope.launch(Dispatchers.IO) {
            initEnvironment()
            copyAssets()

            // 加载初始数据
            reloadUserConfigs()
//            fetchOneWord()

            // 初始检查状态
            checkServiceState()

            // 注册监听
            LsposedServiceManager.addConnectionListener(serviceListener)
            startConfigDirectoryObserver()
        }
    }

    override fun onCleared() {
        super.onCleared()
        LsposedServiceManager.removeConnectionListener(serviceListener)
    }



    /**
     * 刷新模块框架激活状态
     */
    private fun refreshModuleFrameworkStatus() {
        // 1. 尝试从文件读取状态 (兼容 LSPatch)
        val fileStatus = StatusManager.readStatus()

        // 2. 尝试从 Service 读取状态 (兼容 LSPosed)
        val lspState = LsposedServiceManager.connectionState

        if (lspState is ConnectionState.Connected) {
            // 优先信赖 Service，因为它是实时的且信息全
            _moduleStatus.value = ModuleStatus.Activated(
                frameworkName = lspState.service.frameworkName,
                frameworkVersion = lspState.service.frameworkVersion,
                apiVersion = lspState.service.apiVersion
            )
        } else if (fileStatus != null) {
            // 如果 Service 没连上，但文件里有状态（说明 LSPatch 生效并写入了）
            // 可选：检查时间戳，如果太久远可能意味着目标应用没在运行
            _moduleStatus.value = ModuleStatus.Activated(
                frameworkName = fileStatus.framework,
                frameworkVersion = "",
                apiVersion = -1
            )
        } else {
            // 啥都没有
            _moduleStatus.value = ModuleStatus.NotActivated
        }
    }

    /**
     * ✨ 核心逻辑 2：刷新当前激活用户
     * 从 DataStore (文件) 读取
     */
    private fun refreshActiveUser() {
        try {
            val activeUserEntity = DataStore.get("activedUser", UserEntity::class.java)
            _activeUser.value = activeUserEntity
        } catch (e: Exception) {
            Log.e(TAG, "Read active user failed", e)
            _activeUser.value = null
        }
    }

    @OptIn(FlowPreview::class)
    private fun startConfigDirectoryObserver() {
        viewModelScope.launch(Dispatchers.IO) {
            DirectoryWatcher.observeDirectoryChanges(Files.CONFIG_DIR)
                .debounce(100)
                .collectLatest {
                    reloadUserConfigs()
                    refreshActiveUser()
                }
        }
    }

    fun reloadUserConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {

                val latestUserIds = FansirsqiUtil.getFolderList(Files.CONFIG_DIR.absolutePath)
                val newList = mutableListOf<UserEntity>()
                for (userId in latestUserIds) {
                    UserMap.loadSelf(userId)
                    UserMap.get(userId)?.let { newList.add(it) }
                }
                _userList.value = newList
                checkServiceState()

            } catch (e: Exception) {
                Log.e(TAG, "Error reloading user configs", e)
            }
        }
    }

    // --- 其他常规逻辑 ---

    fun refreshDeviceInfo(context: Context) {
        viewModelScope.launch {
            val info = DeviceInfoUtil.showInfo(context)
            _deviceInfo.value = info
            // 独立获取服务状态
            _serviceStatus.value = ServiceStatus.Loading
            val shellType = withContext(Dispatchers.IO) { CommandUtil.getShellType(context) }

            _serviceStatus.value = when (shellType) {
                "RootShell" -> ServiceStatus.Active("Root")
                "ShizukuShell" -> ServiceStatus.Active("Shizuku")
                else -> ServiceStatus.Inactive
            }
        }
    }

    private fun initEnvironment() {
        try {
            LsposedServiceManager.init()
            DataStore.init(Files.CONFIG_DIR)
            // 🔥 核心修复 1: 在 UI 进程初始化模型 system。Config.load 依赖它来正确识别字段，避免因为“未知字段”导致重置配置。
            Model.initAllModel()
        } catch (e: Exception) {
            Log.e(TAG, "Environment init failed", e)
        }
    }

    private fun copyAssets() {
        try {
            val ctx = getApplication<Application>()
            AssetUtil.copySoFileToStorage(ctx, AssetUtil.checkerDestFile)
            AssetUtil.copySoFileToStorage(ctx, AssetUtil.dexkitDestFile)
        } catch (e: Exception) {
            Log.e(TAG, "Asset copy error", e)
        }
    }

//    fun fetchOneWord() {
//        viewModelScope.launch {
//            _isOneWordLoading.value = true
//            val startTime = System.currentTimeMillis()
//            val result = withContext(Dispatchers.IO) { FansirsqiUtil.getOneWord() }
//            val elapsedTime = System.currentTimeMillis() - startTime
//            if (elapsedTime < 2500) delay(500 - elapsedTime)
//            _oneWord.value = result
//            _isOneWordLoading.value = false
//        }
//    }

    fun loadAnimalStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logFile = Files.getAnimalStatusLogFile()
                val result = if (logFile.exists()) {
                    val content = Files.readFromFile(logFile)
                    content.lines().filter { it.isNotBlank() }
                        .takeLast(25)
                        .map { line ->
                            // 如果 Logback 自动加了时间戳，可以在这里处理显示逻辑
                            // 例如：去除 SLF4J 的时间前缀，仅显示 msg
                            line.replaceFirst(Regex("""^\d{2}日 (\d{2}:\d{2}):\d{2}\.\d+ """), "$1 ")
                        }
                        .joinToString("\n")
                        .ifEmpty { "日志文件为空" }
                } else {
                    "日志文件不存在"
                }
                _animalStatus.value = result
                // 🔥 刷新状态：日志变动通常意味着任务进度有更新，此时静默加载 status.json 以同步今日完成状态
                val userId = UserMap.currentUid
                if (!userId.isNullOrEmpty()) {
                    Status.load(userId, false) // 设为 false，静默加载，不打印日志
                    _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")
                }
            } catch (e: Exception) {
                _animalStatus.value = "加载失败: ${e.localizedMessage}"
            }
        }
    }

    /**
     * 检查服务状态并同步用户信息
     */
    fun checkServiceState() {
        refreshModuleFrameworkStatus()
        refreshActiveUser()

        val activeUserEntity = _activeUser.value

        if (activeUserEntity != null) {
            val userId = activeUserEntity.userId
            if (!userId.isNullOrEmpty()) {
                // 🔥 核心修复 3: 增加判断，避免每次 onResume 导致的重复 Config.load 日志 and 潜在冲突。
                // 只有当用户真的切换了，或者 Config 尚未初始化时才加载。
                if (UserMap.currentUid != userId || !Config.isLoaded()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            UserMap.setCurrentUserId(userId)
                            UserMap.loadSelf(userId)
                            Config.load(userId)
                            Status.load(userId) // 切换用户时允许打印一次日志

                            _onlyOnceDaily.value = CustomSettings.onlyOnceDaily.value
                            _autoHandleOnceDaily.value = CustomSettings.autoHandleOnceDaily.value
                            _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")

                            Log.i(TAG, "已切换/初始化用户: $userId, 仅运行一次: ${_onlyOnceDaily.value}, 今日状态: ${_isFinishedToday.value}")
                        } catch (e: Exception) {
                            Log.e(TAG, "加载用户 $userId 状态异常: ${e.message}")
                            _isFinishedToday.value = false // 异常时重置状态
                        }
                    }
                } else {
                    // 如果 UID 没变，也要刷新状态位，因为 status.json 可能被 Xposed 模块在后台更新了
                    viewModelScope.launch(Dispatchers.IO) {
                        Status.load(userId, false) // 设为 false，静默同步，不打印日志
                        _onlyOnceDaily.value = CustomSettings.onlyOnceDaily.value
                        _autoHandleOnceDaily.value = CustomSettings.autoHandleOnceDaily.value
                        _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")
                    }
                }
            }
        } else {
            _isFinishedToday.value = false // 无活跃用户时重置
        }
    }

    /**
     * 同步应用图标状态 (隐藏/显示)
     */
    fun syncIconState(isHidden: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            IconManager.syncIconState(getApplication(), isHidden)
        }
    }

    /**
     * 切换每日单次运行设置
     */
    fun toggleOnlyOnceDaily() {
        viewModelScope.launch(Dispatchers.IO) {
            // 调用三段式逻辑层
            CustomSettings.toggleOnceDailyMode()

            // 实时同步状态流，触发 Compose 重组
            _onlyOnceDaily.value = CustomSettings.onlyOnceDaily.value
            _autoHandleOnceDaily.value = CustomSettings.autoHandleOnceDaily.value
            _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")

            // 保存配置
            val uid = UserMap.currentUid
            if (!uid.isNullOrEmpty()) {
                Config.save(uid, true)
            }
        }
    }
}