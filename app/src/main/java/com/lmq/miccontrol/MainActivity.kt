package com.lmq.miccontrol

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求通知权限：通知栏那个开关需要它才能显示（新版安卓才需要）
        requestNotificationPermissionIfNeeded()

        val store = SettingsStore(this)
        val candidates = AppScanner.listMusicCandidates(this)

        // 打开界面时若开关是开着的，把常驻通知也补上——避免出现
        // "开关开着却找不到通知栏入口"的情况
        if (store.enabled) {
            ControlNotification.show(this)
        }

        setContent {
            MicControlTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlScreen(store, candidates)
                }
            }
        }
    }

    /**
     * 请求通知权限。
     *
     * 只有较新的系统需要动态申请。用户拒绝也不影响应用本身使用，
     * 只是通知栏那个快捷开关没法用，所以这里不做任何强制。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            .launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun ControlScreen(store: SettingsStore, candidates: List<MusicApp>) {
    val context = LocalContext.current
    // 用 remember 让状态在重组之间保持；初值来自存储，保证重开界面时状态不丢
    var enabled by remember { mutableStateOf(store.enabled) }
    var selected by remember { mutableStateOf(store.musicPackage) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 让内容避开状态栏和屏幕圆角：不加这个的话，
            // 标题会被状态栏的图标压住（实机上确认过）。
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
    ) {
        Text(
            text = "麦克风控制",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "打开开关后，选中的音乐软件的声音会代替麦克风输入。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (enabled) "已打开：麦克风输入的是音乐" else "已关闭：麦克风正常工作",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        store.enabled = it
                        // 打开时挂出常驻通知（下拉即可随时关），关掉时撤掉
                        if (it) {
                            ControlNotification.show(context)
                        } else {
                            ControlNotification.cancel(context)
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("选择音乐软件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "必须选一个，否则游戏的声音也会被送回去，形成回音。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))

        if (candidates.isEmpty()) {
            Text(
                text = "没有读取到可用的应用列表，请确认已授予应用列表权限。",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(candidates, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName == selected,
                        onSelect = {
                            selected = app.packageName
                            store.musicPackage = app.packageName
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "提示：打开开关后请到游戏的语音里确认效果；用完记得关掉。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AppRow(app: MusicApp, checked: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = checked, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
