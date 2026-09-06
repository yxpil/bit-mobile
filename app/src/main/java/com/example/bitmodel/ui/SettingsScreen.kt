// yxpil · BIT Mobile
// 设置页：连接信息与桌面端版本、会话管理、主题切换、隐私说明、忘记设备。
package com.example.bitmodel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.example.bitmodel.bit.BitClient
import com.example.bitmodel.bit.BitStore
import com.example.bitmodel.bit.Connection

@Composable
fun SettingsScreen(
    store: BitStore,
    conn: Connection?,
    client: BitClient,
    connectError: String? = null,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onForget: () -> Unit,
    goScan: () -> Unit,
) {
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conn) {
        if (conn != null) version = client.fetchVersion(conn)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("设置", style = MaterialTheme.typography.titleLarge)

        SectionTitle("连接")
        BitCard {
            if (conn == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ok = false)
                    Spacer(Modifier.size(8.dp))
                    Text("未连接", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    connectError ?: "尚未配对桌面端",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connectError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillButton("去扫码配对") { goScan() }
            } else {
                val payload = conn.payload
                InfoRow("方式", conn.method)
                InfoRow("地址", conn.baseUrl.removePrefix("http://"))
                InfoRow("识别码", payload.rid.take(8) + "…")
                InfoRow("访问密码", if (payload.password != null) "随连接码下发" else "未启用")
                InfoRow("桌面端版本", version ?: "未知")
                InfoRow("留存策略", if (payload.retention == "device-only") "对话仅存本机与桌面端" else payload.retention)
            }
        }

        SectionTitle("会话")
        BitCard {
            Text(
                "会话 ${store.sessionId()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "每台设备在桌面端拥有独立会话（sidPolicy=device），不与桌面激活会话或其它设备串线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillOutline("新建会话") { store.newSession() }
                PillOutline("清空本地记录") { store.clearChat() }
            }
        }

        SectionTitle("外观")
        BitCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("light" to "浅色", "dark" to "深色", "auto" to "跟随系统").forEach { (v, label) ->
                    if (themeMode == v) {
                        PillButton(label) { onThemeChange(v) }
                    } else {
                        PillOutline(label) { onThemeChange(v) }
                    }
                }
            }
        }

        SectionTitle("云中继")
        BitCard {
            Text(
                "局域网与 IPv6 直连在 v0.5.14 全部可用。云中继通道（对称 NAT 场景）需要桌面端配套升级：" +
                    "桌面端当前不向手机端披露信道签名材料（bitsign-v2 设备凭证），经中继的请求会被桌面端拒绝。升级桌面端后无需改动本应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("设备")
        BitCard {
            Text(
                "忘记设备会清除本机保存的连接凭据、会话与对话缓存。桌面端不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PillButton("忘记此设备", danger = true) { onForget() }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
