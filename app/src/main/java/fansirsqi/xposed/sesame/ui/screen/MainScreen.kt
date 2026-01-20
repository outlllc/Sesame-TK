package fansirsqi.xposed.sesame.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.ui.MainActivity
import fansirsqi.xposed.sesame.ui.navigation.BottomNavItem
import fansirsqi.xposed.sesame.ui.screen.components.HomeContent
import fansirsqi.xposed.sesame.ui.screen.components.LogsContent
import fansirsqi.xposed.sesame.ui.screen.components.SettingsContent
import fansirsqi.xposed.sesame.ui.theme.ThemeManager
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    animalStatus: String,
    onlyOnceDaily: Boolean,
    autoHandleOnceDaily: Boolean,
    isFinishedToday: Boolean,
    activeUserName: String,
    moduleStatus: MainViewModel.ModuleStatus,
    viewModel: MainViewModel,
    isDynamicColor: Boolean,
    userList: List<UserEntity>,
    onNavigateToSettings: (UserEntity) -> Unit,
    onEvent: (MainActivity.MainUiEvent) -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceInfo(context)
    }

    var currentScreen by remember { mutableStateOf<BottomNavItem>(BottomNavItem.Home) }

    val serviceStatus by viewModel.serviceStatus.collectAsStateWithLifecycle()

    val prefs = context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    var isIconHidden by remember { mutableStateOf(prefs.getBoolean("is_icon_hidden", false)) }
    var showMenu by remember { mutableStateOf(false) }
//    var showUserDialog by remember { mutableStateOf(false) }

    val deviceInfoMap by viewModel.deviceInfo.collectAsStateWithLifecycle()



    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (currentScreen) {
                            BottomNavItem.Home -> activeUserName
                            BottomNavItem.Logs -> "日志中心"
                            BottomNavItem.Settings -> "模块设置"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isIconHidden) "显示应用图标" else "隐藏应用图标") },
                            onClick = {
                                isIconHidden = !isIconHidden
                                onEvent(MainActivity.MainUiEvent.ToggleIconHidden(isIconHidden))
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (BaseModel.debugMode.value) "关闭抓包" else "开启抓包") },
                            onClick = {
                                onEvent(MainActivity.MainUiEvent.ToggleDebugMode)
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("查看 DataStore") },
                            onClick = {
                                onEvent(MainActivity.MainUiEvent.OpenDataStore)
                                showMenu = false
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val items = listOf(BottomNavItem.Logs, BottomNavItem.Home, BottomNavItem.Settings)
                items.forEach { item ->
                    val selected = currentScreen == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentScreen = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        alwaysShowLabel = false
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (currentScreen) {
                BottomNavItem.Home -> HomeContent(
                    moduleStatus = moduleStatus,
                    serviceStatus = serviceStatus,
                    deviceInfoMap = deviceInfoMap,
                    animalStatus = animalStatus,
                    onlyOnceDaily = onlyOnceDaily,
                    autoHandleOnceDaily = autoHandleOnceDaily,
                    isFinishedToday = isFinishedToday,
                    onEvent = onEvent
                )

                BottomNavItem.Logs -> LogsContent(
                    onEvent = onEvent
                )

                BottomNavItem.Settings -> SettingsContent(
                    userList = userList,
                    isDynamicColor = isDynamicColor, // 传给 MainScreen
                    onToggleDynamicColor = ThemeManager::setDynamicColor, // 传入回调
                    onNavigateToSettings = onNavigateToSettings,
                    onEvent = onEvent
                )
            }
        }
    }

}
