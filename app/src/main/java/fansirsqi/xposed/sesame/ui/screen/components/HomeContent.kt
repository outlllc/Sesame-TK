package fansirsqi.xposed.sesame.ui.screen.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fansirsqi.xposed.sesame.model.CustomSettings
import fansirsqi.xposed.sesame.ui.MainActivity
import fansirsqi.xposed.sesame.ui.screen.DeviceInfoCard
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel
import fansirsqi.xposed.sesame.util.ToastUtil

@Composable
fun HomeContent(
    moduleStatus: MainViewModel.ModuleStatus,
    serviceStatus: MainViewModel.ServiceStatus,
    deviceInfoMap: Map<String, String>?,
    animalStatus: String,
    onlyOnceDaily: Boolean,
    autoHandleOnceDaily: Boolean,
    isFinishedToday: Boolean,
    onEvent: (MainActivity.MainUiEvent) -> Unit
) {
    val context = LocalContext.current
    var isServiceCardExpanded by remember { mutableStateOf(false) }
    var isStatusCardExpanded by remember { mutableStateOf(false) }
    
    // 日志滚动状态
    val scrollState = rememberScrollState()
    LaunchedEffect(animalStatus) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp) // 底部边距 24dp
    ) {
        // 顶部可滚动区域 (模块状态、设备信息等)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
//                Text(
//                    "本应用开源免费,严禁倒卖!!\n如果你在闲鱼看到,欢迎给我们反馈",
//                    textAlign = TextAlign.Center,
//                    color = MaterialTheme.colorScheme.error,
//                    fontWeight = FontWeight.Bold,
//                    style = MaterialTheme.typography.titleSmall
//                )
                }
            }
            
            // 1. 模块状态
            item {
                ModuleStatusCard(
                    status = moduleStatus,
                    expanded = isStatusCardExpanded,
                    onClick = {
                        if (moduleStatus is MainViewModel.ModuleStatus.NotActivated) {
                            isStatusCardExpanded = !isStatusCardExpanded
                        }
                    }
                )
            }

            // 2. 服务权限 (当前已注释)
            /*
            item {
                ServicesStatusCard(
                    status = serviceStatus,
                    expanded = isServiceCardExpanded,
                    onClick = {
                        if (serviceStatus is MainViewModel.ServiceStatus.Inactive) {
                            isServiceCardExpanded = !isServiceCardExpanded
                        }
                    }
                )
            }
            */

            // 3. 设备信息
            item {
                if (deviceInfoMap != null) {
                    DeviceInfoCard(deviceInfoMap)
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        // 4. 动物状态日志 (移出 LazyColumn，固定在底部上方)
        Box(
            contentAlignment = Alignment.TopStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 255.dp)
                .verticalScroll(scrollState)
                .combinedClickable(
                    onClick = {
                        onEvent(MainActivity.MainUiEvent.AnimanStatus)
                    },
                    onLongClick = {
                        onEvent(MainActivity.MainUiEvent.OpenDebugLog)
                        ToastUtil.showToast(context, "准备起飞🛫")
                    }
                )
                .padding(32.dp)
        ) {
            Text(
                text = animalStatus,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 5. 手动控制按钮 (移出 LazyColumn，固定在最底部)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val buttonState = remember(onlyOnceDaily, autoHandleOnceDaily, isFinishedToday) {
                CustomSettings.getButtonState(
                    onlyOnceDaily,
                    autoHandleOnceDaily,
                    isFinishedToday
                )
            }
            Text(
                text = buttonState.text,
                color = buttonState.color,
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
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { onEvent(MainActivity.MainUiEvent.ManualRun) }
                    .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            )
        }
    }
}
