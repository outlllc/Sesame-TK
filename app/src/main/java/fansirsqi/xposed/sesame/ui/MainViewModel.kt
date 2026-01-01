package fansirsqi.xposed.sesame.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fansirsqi.xposed.sesame.data.ConnectionState
import fansirsqi.xposed.sesame.data.LsposedServiceManager
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.CustomSettings
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.newutil.DataStore
import fansirsqi.xposed.sesame.newutil.IconManager
import fansirsqi.xposed.sesame.util.AssetUtil
import fansirsqi.xposed.sesame.util.FansirsqiUtil
import fansirsqi.xposed.sesame.util.FansirsqiUtil.getFolderList
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 ViewModel
 * 负责所有非 UI 逻辑：数据加载、文件操作、后台任务、状态管理
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {


    // 一言 (初始状态)
//    private val _oneWord = MutableStateFlow("正在获取句子...")
//    val oneWord: StateFlow<String> = _oneWord.asStateFlow()

    // 模块运行状态 (未激活/已激活/已加载)

    // 当前激活的用户 (LSPosed 注入的那个)
    private val _activeUser = MutableStateFlow<UserEntity?>(null)
    val activeUser: StateFlow<UserEntity?> = _activeUser.asStateFlow()

    // 用户配置列表 (核心修正：类型为 List<UserEntity>)
    private val _userList = MutableStateFlow<List<UserEntity>>(emptyList())
    val userList: StateFlow<List<UserEntity>> = _userList.asStateFlow()

//    // 1. 新增一个 Loading 状态
//    private val _isOneWordLoading = MutableStateFlow(false)
//    val isOneWordLoading = _isOneWordLoading.asStateFlow()

    // ✨ 1. 新增 StateFlow 暴露模块状态
    private val _moduleStatus = MutableStateFlow<ModuleStatus>(ModuleStatus.Loading)
    val moduleStatus: StateFlow<ModuleStatus> = _moduleStatus.asStateFlow()

    // 🔥 1. 将监听器提取为成员变量
    private val serviceListener: (ConnectionState) -> Unit = { _ ->
        checkServiceState()
    }


    // 初始化标志位
    private var isInitialized = false

    private val _animalStatus = MutableStateFlow("正在加载动物状态日志...")
    val animalStatus: StateFlow<String> = _animalStatus.asStateFlow()

    // 每日单次运行状态
    private val _onlyOnceDaily = MutableStateFlow(CustomSettings.onlyOnceDaily.value)
    val onlyOnceDaily: StateFlow<Boolean> = _onlyOnceDaily.asStateFlow()

    // 每日单次运行执行标志
    private val _isFinishedToday = MutableStateFlow(false)
    val isFinishedToday: StateFlow<Boolean> = _isFinishedToday.asStateFlow()


    /**
     * 核心初始化入口
     * 注意：必须在 MainActivity 确认获取到文件权限后调用
     */
    fun initAppLogic() {
        if (isInitialized) return
        isInitialized = true

        viewModelScope.launch(Dispatchers.IO) {
            // 1. 基础环境初始化 (Context, Config路径等)
            initEnvironment()

            // 2. 拷贝资源文件 (耗时 IO 操作)
            copyAssets()
            // 4. 加载业务数据
            reloadUserConfigs() // 加载用户列表
//            fetchOneWord()      // 获取一言

            // 🔥 2. 使用成员变量注册
            LsposedServiceManager.addConnectionListener(serviceListener)

        }
    }

    // 🔥 3. 在 ViewModel 销毁时移除监听器
    override fun onCleared() {
        super.onCleared()
        LsposedServiceManager.removeConnectionListener(serviceListener)
        Log.d(TAG, "ViewModel cleared, listener removed.")
    }

    /**
     * 初始化基础环境组件
     */
    private fun initEnvironment() {
        try {
            LsposedServiceManager.init()
            DataStore.init(Files.CONFIG_DIR)
            // 🔥 核心修复 1: 在 UI 进程初始化模型系统。Config.load 依赖它来正确识别字段，避免因为“未知字段”导致重置配置。
            Model.initAllModel()
        } catch (e: Exception) {
            Log.e(TAG, "Environment init failed", e)
        }
    }

    /**
     * 拷贝 assets 中的 so 文件和 jar 文件到私有目录
     */
    private fun copyAssets() {
        try {
            val ctx = getApplication<Application>()
            // 这里使用了简化的逻辑，如果文件已存在且未更新，AssetUtil 内部应自行判断
            if (!AssetUtil.copySoFileToStorage(ctx, AssetUtil.checkerDestFile)) {
                Log.e(TAG, "checker file copy failed")
            }
            if (!AssetUtil.copySoFileToStorage(ctx, AssetUtil.dexkitDestFile)) {
                Log.e(TAG, "dexkit file copy failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Asset copy error", e)
        }
    }




    /**
     * 获取一言
     */
//    fun fetchOneWord() {
//        viewModelScope.launch {
//            // 2. 开始加载：设置状态为 true
//            _isOneWordLoading.value = true
//
//            // 模拟一点延迟，防止请求太快导致 loading 闪烁（可选优化）
//            val startTime = System.currentTimeMillis()
//
//            val result = withContext(Dispatchers.IO) {
//                FansirsqiUtil.getOneWord()
//            }
//
//            val elapsedTime = System.currentTimeMillis() - startTime
//            if (elapsedTime < 2500) {
//                delay(500 - elapsedTime)
//            }
//
//            // 3. 加载结束：更新文本并关闭 Loading
//            _oneWord.value = result
//            _isOneWordLoading.value = false
//        }
//    }

    fun loadAnimalStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logFile = Files.getAnimalStatusLogFile()
                val result = if (logFile != null && logFile.exists()) {
                    val content = Files.readFromFile(logFile)
                    content.lines().filter { it.isNotBlank() }
                        .takeLast(25)
                        .joinToString("\n")
                        .ifEmpty { "日志文件为空" }
                } else {
                    "日志文件不存在"
                }
                _animalStatus.value = result
            } catch (e: Exception) {
                _animalStatus.value = "加载失败: ${e.localizedMessage}"
            }
        }
    }

    /**
     * 重新加载用户配置列表
     * 通常在 onResume 时调用，以刷新列表
     */
    fun reloadUserConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 加载全局 UI 配置
                try {
                    fansirsqi.xposed.sesame.data.UIConfig.load()
                } catch (e: Exception) {
                    Log.e(TAG, "UIConfig load failed", e)
                }

                // 2. 获取配置文件夹列表
                val configFiles = verifuids
                val newList = mutableListOf<UserEntity>()

                // 记录当前活跃用户 ID，以便后续恢复 UserMap 状态
                val activeUserId = _activeUser.value?.userId

                for (userId in configFiles) {
                    // 加载该用户的配置到内存 Map (注意：这会 clear 之前的 map)
                    UserMap.loadSelf(userId)

                    // 尝试从 Map 获取实体
                    val mapEntity = UserMap.get(userId)
                    if (mapEntity != null) {
                        newList.add(mapEntity)
                    }
                }

                // 🔥 核心修复 2: 恢复活跃用户的 UserMap 状态，防止后续逻辑（如 checkServiceState）因 map 为空而失败
                if (!activeUserId.isNullOrEmpty()) {
                    UserMap.setCurrentUserId(activeUserId)
                    UserMap.loadSelf(activeUserId)
                }
                // 更新状态流
                _userList.value = newList
                // 顺便刷新一下服务状态，确保激活用户显示正确
                checkServiceState()

                // 刷新每日单次标志位
                _onlyOnceDaily.value = CustomSettings.onlyOnceDaily.value

            } catch (e: Exception) {
                Log.e(TAG, "Error reloading user configs", e)
            }
        }
    }

    /**
     * 检查 LSPosed 服务连接状态并更新 UI
     */
    fun checkServiceState() {
        val newStatus = when (val connectionState = LsposedServiceManager.connectionState) {
            is ConnectionState.Connected -> ModuleStatus.Activated(
                frameworkName = connectionState.service.frameworkName,
                frameworkVersion = connectionState.service.frameworkVersion,
                apiVersion = connectionState.service.apiVersion
            )

            else -> ModuleStatus.NotActivated
        }
        _moduleStatus.value = newStatus

        val activeUserEntity = try {
            DataStore.get("activedUser", UserEntity::class.java)
        } catch (_: Exception) {
            null
        }

        if (activeUserEntity != null) {
            val userId = activeUserEntity.userId
            if (!userId.isNullOrEmpty()) {
                // 🔥 核心修复 3: 增加判断，避免每次 onResume 导致的重复 Config.load 日志和潜在冲突。
                // 只有当用户真的切换了，或者 Config 尚未初始化时才加载。
                if (UserMap.currentUid != userId || !Config.isLoaded()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            UserMap.setCurrentUserId(userId)
                            UserMap.loadSelf(userId)
                            Config.load(userId)
                            Status.load(userId)

                            _onlyOnceDaily.value = CustomSettings.onlyOnceDaily.value
                            _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")

                            Log.i(TAG, "已切换/初始化用户: $userId, 仅运行一次: ${_onlyOnceDaily.value}, 今日状态: ${_isFinishedToday.value}")
                        } catch (e: Exception) {
                            Log.e(TAG, "加载用户 $userId 状态异常: ${e.message}")
                            _isFinishedToday.value = false // 异常时重置状态
                        }
                    }
                } else {
                    // 如果 UID 没变，仅刷新状态位（可能 status 文件在后台被更新了）
                    _onlyOnceDaily.value = CustomSettings.onlyOnceDaily.value
                    _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")
                }
            }
        } else {
            _isFinishedToday.value = false // 无活跃用户时重置
        }

        _activeUser.value = activeUserEntity
    }

    /**
     * 同步应用图标状态 (隐藏/显示)
     */
    fun syncIconState(isHidden: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            IconManager.syncIconState(getApplication(), isHidden)
        }
    }

    // ✨ 4. 定义 ViewModel 的状态和事件
    sealed class ModuleStatus {
        data object Loading : ModuleStatus()
        data class Activated(
            val frameworkName: String,
            val frameworkVersion: String,
            val apiVersion: Int
        ) : ModuleStatus()

        data object NotActivated : ModuleStatus()
    }



    companion object {
        val TAG = "MainViewModel"
        val verifuids: List<String> = getFolderList(Files.CONFIG_DIR.absolutePath)
        var verifyId: String = "待施工🚧..."

        var lspService = LsposedServiceManager.service

    }
    /**
     * 切换每日单次运行设置
     */
    fun toggleOnlyOnceDaily() {
        viewModelScope.launch(Dispatchers.IO) {
            val newValue = !CustomSettings.onlyOnceDaily.value
            CustomSettings.onlyOnceDaily.value = newValue
            _onlyOnceDaily.value = newValue

            // 保存配置
            val uid = UserMap.currentUid
            if (!uid.isNullOrEmpty()) {
                Config.save(uid, true)
                // 切换后同步今日完成标志位，确保状态准确
                _isFinishedToday.value = Status.hasFlagToday("OnceDaily::Finished")
            }
        }
    }
}