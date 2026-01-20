package fansirsqi.xposed.sesame.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.model.CustomSettings
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.Detector
import fansirsqi.xposed.sesame.util.FansirsqiUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

// 定义菜单项数据类
data class MenuItem(
    val title: String,
    val onClick: () -> Unit
)

// 定义各种弹窗类型
sealed class ExtendDialog {
    data object None : ExtendDialog()

    // 清空图片确认框
    data class ClearPhotoConfirm(val count: Int) : ExtendDialog()

    // 写入光盘测试框
    data class WritePhotoTest(val message: String) : ExtendDialog()

    // 通用输入框 (用于获取DataStore / BaseUrl)
    data class InputDialog(
        val title: String,
        val initialValue: String = "",
        val onConfirm: (String) -> Unit
    ) : ExtendDialog()

    // 等待对话框
    data class WaitingDialog(val message: String) : ExtendDialog()
}

class ExtendViewModel : ViewModel() {

    // 列表项数据
    val menuItems = mutableStateListOf<MenuItem>()

    // 当前显示的弹窗状态
    var currentDialog by mutableStateOf<ExtendDialog>(ExtendDialog.None)
        private set

    // 初始化数据
    fun loadData(context: Context) {
        menuItems.clear()

        // 1. 广播类功能
        val debugTips = context.getString(R.string.debug_tips)

        fun addBroadcastItem(titleResId: Int, type: String) {
            menuItems.add(MenuItem(context.getString(titleResId)) {
                sendItemsBroadcast(context, type)
                ToastUtil.makeText(context, debugTips, 0).show()
            })
        }

        addBroadcastItem(R.string.query_the_remaining_amount_of_saplings, "getTreeItems")
        addBroadcastItem(R.string.search_for_new_items_on_saplings, "getNewTreeItems")
        addBroadcastItem(R.string.search_for_unlocked_regions, "queryAreaTrees")
        addBroadcastItem(R.string.search_for_unlocked_items, "getUnlockTreeItems")

        // 2. 清空图片
        menuItems.add(MenuItem(context.getString(R.string.clear_photo)) {
            val currentCount = DataStore
                .getOrCreate("plate", object : TypeReference<List<Map<String, String>>>() {})
                .size
            currentDialog = ExtendDialog.ClearPhotoConfirm(currentCount)
        })

        // 4. 获取 ReferToken
        menuItems.add(MenuItem("获取 ReferToken") {
            startCaptureToken(context)
        })

        // 5. Debug 功能
        if (BuildConfig.DEBUG) {

            menuItems.add(MenuItem("写入光盘") {
                currentDialog = ExtendDialog.WritePhotoTest("xxxx")
            })

            menuItems.add(MenuItem("获取DataStore字段") {
                currentDialog = ExtendDialog.InputDialog("输入字段Key") { key ->
                    handleGetDataStore(context, key)
                }
            })

            menuItems.add(MenuItem("获取BaseUrl") {
                currentDialog = ExtendDialog.InputDialog("请输入Key") { input ->
                    handleGetBaseUrl(context, input)
                }
            })

            menuItems.add(MenuItem("TestShow") {
                ToastUtil.showToast(context, "shizuku:"+isShizukuReady().toString())
            })
        }
    }

    private fun startCaptureToken(context: Context) {
        // 1. 清除信号
        DataStore.remove("AntFarmReferToken_Captured_Signal")
        // 2. 显示等待框
        currentDialog = ExtendDialog.WaitingDialog("正在等待捕获...\n\n请打开支付宝 -> 蚂蚁庄园 -> 任务列表 -> 点击抽抽乐或相应广告")
        // 3. 开启轮询
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var captured = false
            while (System.currentTimeMillis() - startTime < 60000) { // 等待1分钟
                val signal = DataStore.get("AntFarmReferToken_Captured_Signal", Long::class.java)
                if (signal != null && signal >= startTime) {
                    captured = true
                    break
                }
                delay(1000)
            }
            dismissDialog()
            if (captured) {
                ToastUtil.showToast(context, "✅ ReferToken 捕获成功并已保存！")
            } else {
                ToastUtil.showToast(context, "❌ 捕获超时，请重试")
            }
        }
    }

    // --- 业务逻辑 ---

    fun dismissDialog() {
        currentDialog = ExtendDialog.None
    }

    fun clearPhotos(context: Context) {
        DataStore.remove("plate")
        ToastUtil.showToast(context, "光盘行动图片清空成功")
        dismissDialog()
    }

    fun writePhotoTest(context: Context) {
        val newPhotoEntry = mapOf(
            "before" to "before${FansirsqiUtil.getRandomString(10)}",
            "after" to "after${FansirsqiUtil.getRandomString(10)}"
        )
        val existingPhotos = DataStore.getOrCreate(
            "plate",
            object : TypeReference<MutableList<Map<String, String>>>() {})
        existingPhotos.add(newPhotoEntry)
        DataStore.put("plate", existingPhotos)
        ToastUtil.showToast(context, "写入成功$newPhotoEntry")
        dismissDialog()
    }

    private fun handleGetDataStore(context: Context, key: String) {
        val value: Any = try {
            DataStore.getOrCreate(key, object : TypeReference<Map<*, *>>() {})
        } catch (_: Exception) {
            DataStore.getOrCreate(key, object : TypeReference<String>() {})
        }
        ToastUtil.showToast(context, "$value \n输入内容: $key")
        dismissDialog()
    }

    private fun handleGetBaseUrl(context: Context, input: String) {
        val key = input.toIntOrNull(16)
        if (key != null) {
            val output = Detector.getApiUrl(key)
            ToastUtil.showToast(context, "$output \n输入内容: $input")
        } else {
            ToastUtil.showToast(context, "输入内容: $input , 请输入正确的十六进制数字")
        }
        dismissDialog()
    }

    private fun sendItemsBroadcast(context: Context, type: String) {
        val intent = Intent("com.eg.android.AlipayGphone.sesame.rpctest").apply {
            putExtra("method", "")
            putExtra("data", "")
            putExtra("type", type)
        }
        context.sendBroadcast(intent)
        Log.debug("ExtendViewModel", "扩展工具主动调用广播查询📢：$type")
    }

    private fun isShizukuReady(): Boolean {
        return try {
            val isBinderAlive = Shizuku.pingBinder()
            val hasPermission = if (isBinderAlive) Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED else false
            isBinderAlive && hasPermission
        } catch (_: Exception) {
            false
        }
    }
}