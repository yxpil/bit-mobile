// yxpil · BIT Mobile
// 单 Activity 入口：配对状态驱动（未配对 → 扫码页；已配对 → 对话/扫码/设置三页签）。
// 启动时按已存 payload 静默重建连接；主题 light/dark/auto 持久化（BitStore）。
package com.example.bitmodel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bitmodel.bit.BitClient
import com.example.bitmodel.bit.BitStore
import com.example.bitmodel.bit.Connection
import com.example.bitmodel.ui.ChatScreen
import com.example.bitmodel.ui.PillButton
import com.example.bitmodel.ui.PillOutline
import com.example.bitmodel.ui.ScanScreen
import com.example.bitmodel.ui.SettingsScreen
import com.example.bitmodel.ui.theme.BitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = BitStore(this)
        val client = BitClient()
        setContent {
            BitRoot(store, client)
        }
    }
}

private enum class Tab { CHAT, SCAN, SETTINGS }

@Composable
private fun BitRoot(store: BitStore, client: BitClient) {
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(store.themeMode) }
    var conn by remember { mutableStateOf<Connection?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(if (store.payload != null) Tab.CHAT else Tab.SCAN) }
    // 配对成功 / 手动重连时递增，触发 LaunchedEffect 重建连接
    var reconnectTick by remember { mutableStateOf(0) }

    // 静默重建连接（失败不阻塞进入：对话/设置页会呈现状态，可到扫码页重试）
    LaunchedEffect(Unit, reconnectTick) {
        val payload = store.payload ?: return@LaunchedEffect
        if (conn != null || connecting) return@LaunchedEffect
        connecting = true
        connectError = null
        scope.launch {
            try {
                conn = client.connect(payload)
            } catch (e: Exception) {
                connectError = e.message
            }
            connecting = false
        }
    }

    BitTheme(mode = themeMode) {
        Scaffold(
            bottomBar = {
                if (store.payload != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TabPill("对话", tab == Tab.CHAT) { tab = Tab.CHAT }
                        TabPill("扫码", tab == Tab.SCAN) { tab = Tab.SCAN }
                        TabPill("设置", tab == Tab.SETTINGS) { tab = Tab.SETTINGS }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when {
                    store.payload == null -> ScanScreen(
                        store = store,
                        onPaired = {
                            reconnectTick++
                            tab = Tab.CHAT
                        },
                        goChat = { tab = Tab.CHAT },
                    )
                    tab == Tab.CHAT -> ChatScreen(store, conn, client, goScan = { tab = Tab.SCAN })
                    tab == Tab.SCAN -> ScanScreen(
                        store = store,
                        onPaired = {
                            reconnectTick++
                            tab = Tab.CHAT
                        },
                        goChat = { tab = Tab.CHAT },
                    )
                    else -> SettingsScreen(
                        store = store,
                        conn = conn,
                        client = client,
                        connectError = connectError,
                        themeMode = themeMode,
                        onThemeChange = { v ->
                            themeMode = v
                            store.themeMode = v
                        },
                        onForget = {
                            store.forgetDevice()
                            conn = null
                            connectError = null
                            tab = Tab.SCAN
                        },
                        goScan = { tab = Tab.SCAN },
                    )
                }
            }
        }
    }
}

/// 页签胶囊：选中=实心强调色，未选=描边
@Composable
private fun RowScope.TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f)) {
        if (selected) {
            PillButton(label, modifier = Modifier.fillMaxWidth(), onClick = onClick)
        } else {
            PillOutline(label, modifier = Modifier.fillMaxWidth(), onClick = onClick)
        }
    }
}
