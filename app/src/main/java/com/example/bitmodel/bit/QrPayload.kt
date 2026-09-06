// yxpil · BIT Mobile
// 连接二维码 payload v2 模型（桌面端 commands.rs::qr_payload 同构）。
// 字段：v=2 / app="bit" / port / key(client_key) / rid(128位识别码) /
// sidPolicy=device（每台设备自建 remote-<随机> 会话）/ retention=device-only /
// methods{lan[],direct6[],relay} 三种连接方式 / pwd(可选访问密码) / alg(签名算法)。
package com.example.bitmodel.bit

import org.json.JSONException
import org.json.JSONObject

data class QrPayload(
    val version: Int,
    val app: String,
    val port: Int,
    val clientKey: String,
    val rid: String,
    val sidPolicy: String,
    val retention: String,
    val lan: List<String>,
    val direct6: List<String>,
    val relay: String,
    val password: String?,
    val alg: String,
    val enc: String,
) {
    /// 直连候选按优先级排列（局域网 → IPv6）
    val directCandidates: List<String> get() = lan + direct6

    companion object {
        /// 解析 payload JSON（明文）。结构不合预期抛 IllegalArgumentException（message 面向用户）
        fun parse(json: String): QrPayload {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                throw IllegalArgumentException("二维码内容不是有效的 BIT 连接数据")
            }
            val app = o.optString("app")
            if (app != "bit") throw IllegalArgumentException("这不是 BIT 连接码（app=$app）")
            val v = o.optInt("v", 0)
            if (v < 2) throw IllegalArgumentException("BIT 连接码版本过旧（v=$v），请将桌面端升级到 v0.5.14+")
            val methods = o.optJSONObject("methods")
            fun list(name: String): List<String> =
                methods?.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null) } } ?: emptyList()
            val key = o.optString("key")
            if (key.isEmpty()) throw IllegalArgumentException("连接码缺少访问密钥（key），请在桌面端重新生成二维码")
            val rid = o.optString("rid")
            if (rid.isEmpty()) throw IllegalArgumentException("连接码缺少识别码（rid）")
            return QrPayload(
                version = v,
                app = app,
                port = o.optInt("port", 0),
                clientKey = key,
                rid = rid,
                sidPolicy = o.optString("sidPolicy", "device"),
                retention = o.optString("retention", "device-only"),
                lan = list("lan"),
                direct6 = list("direct6"),
                relay = methods?.optString("relay", "").orEmpty(),
                password = o.optString("pwd", "").ifEmpty { null },
                alg = o.optString("alg", "bitsign-v2"),
                enc = o.optString("enc", ""),
            )
        }
    }
}
