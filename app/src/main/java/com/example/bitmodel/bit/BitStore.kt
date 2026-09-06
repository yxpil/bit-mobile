// yxpil · BIT Mobile
// 本地持久化（SharedPreferences）：配对设备 payload、设备独立会话 id、对话缓存、主题。
// 对话只存设备本地（与二维码 retention=device-only 声明一致），中继与云服务器不留存。
package com.example.bitmodel.bit

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ChatEntry(val role: String, val text: String, val ts: Long)

class BitStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("bit_mobile", Context.MODE_PRIVATE)

    // ── 配对设备 ──

    var payloadJson: String?
        get() = sp.getString(KEY_PAYLOAD, null)
        set(v) = sp.edit().putString(KEY_PAYLOAD, v).apply()

    val payload: QrPayload?
        get() = payloadJson?.let {
            try {
                QrPayload.parse(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    fun forgetDevice() {
        sp.edit().remove(KEY_PAYLOAD).remove(KEY_SESSION).remove(KEY_CHAT).apply()
    }

    // ── 设备独立会话（sidPolicy=device）──

    fun sessionId(): String {
        val cur = sp.getString(KEY_SESSION, null)
        if (cur != null) return cur
        val id = BitClient.newSessionId()
        sp.edit().putString(KEY_SESSION, id).apply()
        return id
    }

    fun newSession() {
        sp.edit().putString(KEY_SESSION, BitClient.newSessionId()).remove(KEY_CHAT).apply()
    }

    // ── 对话缓存（本设备视角：用户消息 + 桌面端回复）──

    fun chatHistory(): List<ChatEntry> {
        val s = sp.getString(KEY_CHAT, null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ChatEntry(o.optString("role"), o.optString("text"), o.optLong("ts"))
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    fun appendChat(entry: ChatEntry) {
        val list = chatHistory().toMutableList()
        list.add(entry)
        // 上限 200 条：防长对话无限膨胀本地存储
        while (list.size > 200) list.removeAt(0)
        val a = JSONArray()
        list.forEach { e -> a.put(JSONObject().put("role", e.role).put("text", e.text).put("ts", e.ts)) }
        sp.edit().putString(KEY_CHAT, a.toString()).apply()
    }

    fun clearChat() {
        sp.edit().remove(KEY_CHAT).apply()
    }

    // ── 主题：light / dark / auto ──

    var themeMode: String
        get() = sp.getString(KEY_THEME, "auto") ?: "auto"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    companion object {
        private const val KEY_PAYLOAD = "payload.v2"
        private const val KEY_SESSION = "session.id"
        private const val KEY_CHAT = "chat.cache"
        private const val KEY_THEME = "theme.mode"
    }
}
