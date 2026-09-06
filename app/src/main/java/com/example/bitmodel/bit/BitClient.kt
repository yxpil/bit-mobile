// yxpil · BIT Mobile
// BIT 桌面端 HTTP API 客户端（对接 v0.5.14）：
// - 连接：GET /api/health 探活（免鉴权）→ GET /api/tools 双重认证校验（Bearer Client Key
//   + X-Access-Password）。候选按二维码 methods 优先级：局域网 → IPv6 直连 → 云中继。
// - 云中继：{relay}/relay/challenge/{rid} 取一次性挑战 → proof（HMAC-SHA256(S, c)）换许可
//   permit → 隧道请求 {relay}/relay/req/{rid}/{path} 携带 bitsign 三件套。注意 v0.5.14
//   桌面端 channel_guard 默认开启且设备签名材料不出桌面端，中继请求会被 BIT 端 403，
//   客户端如实向用户呈现原因（LAN / IPv6 直连不受影响）。
// - 对话：POST /api/chat {message, session_id}（设备独立会话 remote-<随机>，绝不落入
//   桌面激活会话）；等待期间轮询 GET /api/approvals 并以 POST /api/approvals/{id} 应答。
package com.example.bitmodel.bit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class Connection(
    val baseUrl: String,
    val method: String,
    val payload: QrPayload,
    /// 中继隧道附加头（challenge / proof / permit / bitsign 三件套），直连为空
    val extraHeaders: Map<String, String> = emptyMap(),
    /// 中继基址（https://osbt.space），直连为空
    val relayBase: String? = null,
)

data class Attempt(
    val url: String,
    val method: String,
    val ok: Boolean,
    val detail: String,
)

data class Approval(
    val id: String,
    val tool: String,
    val params: String,
    val ageSecs: Long,
) {
    companion object {
        fun from(o: JSONObject): Approval = Approval(
            id = o.optString("id"),
            tool = o.optString("tool"),
            params = o.optJSONObject("params")?.toString(2) ?: o.optString("params"),
            ageSecs = o.optLong("age_secs", 0),
        )
    }
}

data class ChatOutcome(
    val reply: String,
    val error: String? = null,
)

class BitClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS) // Agent 工具循环可能较长
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val shortHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    // ── 连接 ──

    /// 按 methods 优先级尝试全部候选，返回首个可用连接（认证双重校验通过）。
    /// 全部失败时抛 ConnectException(message 为各候选结果汇总，面向用户)
    suspend fun connect(payload: QrPayload): Connection = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<Attempt>()
        for (url in payload.directCandidates) {
            val a = tryDirect(payload, url)
            attempts += a
            if (a.ok) return@withContext Connection(url, "lan", payload)
        }
        if (payload.relay.isNotEmpty()) {
            val a = tryRelay(payload)
            attempts += a
            if (a.ok) return@withContext relayConnection(payload)
        }
        throw ConnectException(attempts)
    }

    class ConnectException(val attempts: List<Attempt>) :
        Exception(attempts.joinToString("\n") { "${it.method} ${it.url} — ${it.detail}" })

    /// 校验直连候选：探活 → 认证（Bearer + 可选 X-Access-Password）
    private fun tryDirect(payload: QrPayload, base: String): Attempt {
        val baseNorm = base.trimEnd('/')
        try {
            shortHttp.newCall(
                okhttp3.Request.Builder().url("$baseNorm/api/health").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return Attempt(baseNorm, "局域网", false, "HTTP ${resp.code}")
            }
            http.newCall(authRequest(payload, "$baseNorm/api/tools").build()).execute().use { resp ->
                return when {
                    resp.isSuccessful -> Attempt(baseNorm, "局域网", true, "双重认证通过")
                    else -> Attempt(baseNorm, "局域网", false, authError(resp.body?.string(), resp.code))
                }
            }
        } catch (e: Exception) {
            return Attempt(baseNorm, "局域网", false, "无法连接（${e.javaClass.simpleName}）")
        }
    }

    /// 云中继：握手 → 探活 → 认证，全程走隧道。返回 Attempt（成功时 ok=true，调用方需要
    /// 完整 Connection 则通过 connect() 的返回值拿不到——见 tryRelayOrConnection）。
    /// v0.5.14 预期结果：BIT 端 channel_guard 403（设备签名材料不出桌面端）
    private fun tryRelay(payload: QrPayload): Attempt {
        val relayBase = payload.relay.substringBefore("/relay/")
        val reqUrl = payload.relay.removePrefix(relayBase) // "/relay/{rid}"
        val rid = reqUrl.removePrefix("/relay/")
        return try {
            // 1. 挑战
            val challenge = shortHttp.newCall(
                okhttp3.Request.Builder().url("$relayBase/relay/challenge/$rid").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return Attempt(relayBase, "云中继", false, "握手失败 HTTP ${resp.code}")
                val body = resp.body?.string() ?: ""
                try {
                    JSONObject(body).optString("c", "").ifEmpty { null }
                } catch (_: org.json.JSONException) {
                    null
                } ?: return Attempt(relayBase, "云中继", false, "握手失败（挑战格式异常）")
            }
            // 2. 证明 → 隧道探活（签名 path 与 BIT 端实际路径一致，不含 query）
            val headers = tunnelHeaders(payload, rid, "GET", "/api/health", challenge)
            tunnelRequest(payload, relayBase, rid, "/api/health", "GET", null, headers)
                .execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> Attempt(relayBase, "云中继", true, "通道握手通过")
                        else -> Attempt(relayBase, "云中继", false, authError(body, resp.code))
                    }
                }
        } catch (e: Exception) {
            Attempt(relayBase, "云中继", false, "无法连接（${e.javaClass.simpleName}）")
        }
    }

    /// 中继成功建立后的完整 Connection（每条隧道请求独立走挑战-应答，许可由 Worker 签发续期）
    private fun relayConnection(payload: QrPayload): Connection {
        val relayBase = payload.relay.substringBefore("/relay/")
        return Connection(payload.relay, "relay", payload, relayBase = relayBase)
    }

    private fun relayRid(conn: Connection): String =
        conn.payload.relay.removePrefix(conn.relayBase!!).removePrefix("/relay/")

    private fun fetchChallenge(relayBase: String, rid: String): String =
        shortHttp.newCall(
            okhttp3.Request.Builder().url("$relayBase/relay/challenge/$rid").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("challenge HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "").optString("c", "").ifEmpty {
                throw java.io.IOException("challenge 格式异常")
            }
        }

    // ── 请求构造 ──

    private fun authBuilder(conn: Connection): okhttp3.Request.Builder {
        val b = okhttp3.Request.Builder()
        conn.payload.password?.let { b.header("X-Access-Password", it) }
        // 中继隧道：请求 URL 是 {relay}/relay/req/{rid}{path}，鉴权头原样透传给 BIT
        return b.header("Authorization", "Bearer ${conn.payload.clientKey}")
    }

    private fun authRequest(payload: QrPayload, url: String): okhttp3.Request.Builder {
        val b = okhttp3.Request.Builder().url(url).header("Authorization", "Bearer ${payload.clientKey}")
        payload.password?.let { b.header("X-Access-Password", it) }
        return b
    }

    /// 隧道请求头：鉴权 + 挑战/证明 + bitsign 三件套（首次）或许可（后续）
    private fun tunnelHeaders(
        payload: QrPayload,
        rid: String,
        method: String,
        path: String,
        challenge: String?,
        permit: String? = null,
    ): Map<String, String> {
        val m = mutableMapOf<String, String>()
        m["Authorization"] = "Bearer ${payload.clientKey}"
        payload.password?.let { m["X-Access-Password"] = it }
        if (permit != null) {
            m["x-bit-permit"] = permit
        } else if (challenge != null) {
            m["x-bit-challenge"] = challenge
            m["x-bit-proof"] = Bitsign.proofOf(Bitsign.workerBind(payload.clientKey, rid), challenge)
        }
        // bitsign 三件套：Worker 只做格式与时间窗门槛，密码学验证在 BIT 端。
        // v0.5.14 设备材料不出桌面端 → 使用空材料（BIT 端会 403，属桌面端预期行为）
        m.putAll(Bitsign.signHeaders(payload.clientKey, "", rid, method, path))
        return m
    }

    private fun tunnelRequest(
        payload: QrPayload,
        relayBase: String,
        rid: String,
        path: String,
        method: String,
        body: JSONObject?,
        headers: Map<String, String>,
    ): okhttp3.Call {
        val b = okhttp3.Request.Builder()
            .url("$relayBase/relay/req/$rid$path")
            .method(method, if (body != null) okBody(body) else if (method == "POST") okBody(JSONObject()) else null)
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.newCall(b.build())
    }

    private fun okBody(o: JSONObject) =
        okhttp3.RequestBody.create(json, o.toString())

    /// 认证失败的用户可读原因（BIT 端 401 会给出 JSON error 说明）
    private fun authError(body: String?, code: Int): String {
        val detail = try {
            val o = JSONObject(body ?: "")
            o.optString("error").ifEmpty {
                o.optJSONObject("error")?.optString("message").orEmpty()
            }
        } catch (_: org.json.JSONException) {
            ""
        }
        return when (code) {
            401 -> "认证失败${if (detail.isEmpty()) "" else "：$detail"}"
            403 -> "访问被拒绝${if (detail.isEmpty()) "" else "：$detail"}"
            503 -> "BIT 尚未配置 Client Key"
            else -> "HTTP $code${if (detail.isEmpty()) "" else "：$detail"}"
        }
    }

    // ── 对话 ──

    /// 发送一条消息（设备独立会话）。等待期间每 2s 轮询审批表，onApprovals 回调驱动 UI 卡片。
    suspend fun chat(
        conn: Connection,
        sessionId: String,
        message: String,
        onApprovals: (List<Approval>) -> Unit,
    ): ChatOutcome = withContext(Dispatchers.IO) {
        val pollJob = if (conn.relayBase == null) pollApprovals(conn, onApprovals) else null
        try {
            val body = JSONObject().put("message", message).put("session_id", sessionId)
            val req = if (conn.relayBase == null) {
                authBuilder(conn).url("${conn.baseUrl}/api/chat").post(okBody(body)).build()
            } else {
                null
            }
            val resp = if (req != null) {
                http.newCall(req).execute()
            } else {
                // 中继面：取挑战 → 签名隧道 POST /api/chat（v0.5.14 会 403，走统一错误呈现）
                val relayBase = conn.relayBase!!
                val rid = relayRid(conn)
                val challenge = fetchChallenge(relayBase, rid)
                val headers = tunnelHeaders(conn.payload, rid, "POST", "/api/chat", challenge)
                tunnelRequest(conn.payload, relayBase, rid, "/api/chat", "POST", body, headers).execute()
            }
            resp.use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@withContext ChatOutcome("", apiError(text, r.code))
                }
                val reply = try {
                    JSONObject(text).optString("reply", "")
                } catch (_: org.json.JSONException) {
                    ""
                }
                ChatOutcome(reply)
            }
        } catch (e: Exception) {
            ChatOutcome("", "连接中断（${e.javaClass.simpleName}），请检查网络后重试")
        } finally {
            pollJob?.cancel()
        }
    }

    /// 审批轮询：仅在直连面开启（中继面 403 收窄为 chat-only，审批表不可达）
    private fun pollApprovals(conn: Connection, onApprovals: (List<Approval>) -> Unit): Job =
        CoroutineScope(Dispatchers.IO + Job()).launch {
            while (true) {
                delay(2000)
                try {
                    val list = listApprovals(conn)
                    onApprovals(list)
                } catch (_: Exception) {
                    // 网络抖动不打断对话等待
                }
            }
        }

    suspend fun listApprovals(conn: Connection): List<Approval> = withContext(Dispatchers.IO) {
        val req = authBuilder(conn).url("${conn.baseUrl}/api/approvals").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val arr = try {
                JSONObject(resp.body?.string() ?: "").optJSONArray("approvals") ?: JSONArray()
            } catch (_: org.json.JSONException) {
                JSONArray()
            }
            (0 until arr.length()).map { Approval.from(arr.getJSONObject(it)) }
        }
    }

    /// 应答审批（allow=true 允许 / false 拒绝）。404 = 已被桌面端或其它设备应答
    suspend fun answerApproval(conn: Connection, id: String, allow: Boolean): String? =
        withContext(Dispatchers.IO) {
            val req = authBuilder(conn)
                .url("${conn.baseUrl}/api/approvals/$id")
                .post(okBody(JSONObject().put("allow", allow)))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else "HTTP ${resp.code}"
            }
        }

    /// 桌面端版本（GET /api/update/check，需认证；失败返回 null 不影响展示）
    suspend fun fetchVersion(conn: Connection): String? = withContext(Dispatchers.IO) {
        try {
            val req = authBuilder(conn).url("${conn.baseUrl}/api/update/check").build()
            shortHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string() ?: "").optString("current").ifEmpty { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun apiError(body: String, code: Int): String {
        val detail = try {
            val o = JSONObject(body)
            o.optString("error").ifEmpty {
                o.optJSONObject("error")?.optString("message").orEmpty()
            }
        } catch (_: org.json.JSONException) {
            ""
        }
        return when (code) {
            401 -> "认证失败，请重新扫码配对${if (detail.isEmpty()) "" else "（$detail）"}"
            403 -> "访问被拒绝${if (detail.isEmpty()) "" else "（$detail）"}"
            429 -> "请求过于频繁，请稍后再试"
            else -> "请求失败 HTTP $code${if (detail.isEmpty()) "" else "：$detail"}"
        }
    }

    companion object {
        /// 设备独立会话 id：remote-<16 hex>（sidPolicy=device，绝不落入桌面激活会话）
        fun newSessionId(): String {
            val b = ByteArray(8)
            SecureRandom().nextBytes(b)
            return "remote-" + Bitsign.hex(b)
        }
    }
}
