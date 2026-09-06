// yxpil · BIT Mobile
// 全链接 E2E（需真实 BIT 实例，仅 BIT_E2E=1 时运行，平时 testDebugUnitTest 自动跳过）：
// 实例由 e2e/mobile-e2e.cjs 拉起——headless BIT（隔离数据目录）+ mock-ai 上游（9901）。
// 覆盖：扫码解密 → payload 解析 → 连接（探活+双重认证）→ 版本 → 对话（mock 默认回复）→
// 工具审批完整往返（ask 模式：排队 → 手机端应答 → shell 执行 → 最终回复）→ 非法用例
//（错误密码 / 错误 Key / 不可达候选 / 空 session_id / 空消息）。
package com.example.bitmodel.bit

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BitE2ETest {
    private val host = System.getenv("BIT_E2E_HOST") ?: "127.0.0.1"
    private val port = System.getenv("BIT_E2E_PORT")?.toIntOrNull() ?: 18600
    private val key = System.getenv("BIT_E2E_KEY") ?: "0123456789abcdef0123456789abcdef"
    private val password = System.getenv("BIT_E2E_PASSWORD") ?: "87654321"
    private val rid = "e2e0mobile0test0rid0000000deadbeef".take(32)

    @Before
    fun requireRealInstance() {
        assumeTrue("仅在 BIT_E2E=1 时运行（需真实 BIT 实例）", System.getenv("BIT_E2E") == "1")
    }

    /// 桌面端 commands.rs::qr_payload 同构的 payload 明文（v0.5.14 字段全集）
    private fun payloadJson(lan: String, relay: String): String = org.json.JSONObject().apply {
        put("v", 2)
        put("app", "bit")
        put("port", port)
        put("key", key)
        put("nat", "e2e")
        put("rid", rid)
        put("sidPolicy", "device")
        put("retention", "device-only")
        put(
            "methods",
            org.json.JSONObject()
                .put("lan", org.json.JSONArray().put(lan))
                .put("direct6", org.json.JSONArray())
                .put("relay", relay),
        )
        put("pwd", password)
        put("alg", "bitsign-v2")
    }.toString()

    /// 完整扫码路径：BIT-Crypt 加密（模拟桌面端出码）→ 解密 → 解析
    private fun scanPayload(lan: String, relay: String, pwd: String? = null): QrPayload {
        val json = if (pwd == null) payloadJson(lan, relay) else {
            org.json.JSONObject(payloadJson(lan, relay)).put("pwd", pwd).toString()
        }
        val cipher = BitCrypt.encrypt(json, ByteArray(16) { (it * 11 + 5).toByte() })
        val plain = BitCrypt.decrypt(cipher) ?: throw AssertionError("扫码解密失败")
        return QrPayload.parse(plain)
    }

    @Test
    fun fullLinkScanConnectChat() = runBlocking {
        val payload = scanPayload("http://$host:$port", "http://127.0.0.1:1/relay/$rid")
        val client = BitClient()

        // 连接：局域网直连，探活 + 双重认证通过
        val conn = client.connect(payload)
        assertEquals("lan", conn.method)
        assertTrue(conn.baseUrl.startsWith("http://$host:$port"))

        // 桌面端版本可达（GET /api/update/check）
        assertTrue("版本号应形如 0.x，实际=$" + client.fetchVersion(conn), client.fetchVersion(conn)!!.startsWith("0."))

        // 审批表可达且为空（无待审批工具）
        assertEquals(0, client.listApprovals(conn).size)

        // 对话：mock 上游默认回复「好的。」
        val sid = BitClient.newSessionId()
        assertTrue(sid.startsWith("remote-"))
        val out = client.chat(conn, sid, "E2E-MOBILE-GREETING") { }
        assertNull(out.error)
        assertTrue("默认回复应含「好的」，实际=${out.reply}", out.reply.contains("好的"))

        // 同会话二轮对话（会话保持）
        val out2 = client.chat(conn, sid, "再来一条") { }
        assertNull(out2.error)
        assertTrue(out2.reply.isNotEmpty())
    }

    @Test
    fun approvalRoundTripAllow() = runBlocking {
        val payload = scanPayload("http://$host:$port", "http://127.0.0.1:1/relay/$rid")
        val client = BitClient()
        val conn = client.connect(payload)

        // ask 模式下 shell 须审批：mock 对 E2E-CMD-SHELL 回工具调用 → 排队 → 手机端应答允许
        // → 桌面端执行 echo e2e-shell-ok → mock 最终回复 E2E-FINAL-OK
        var pendingId: String? = null
        var pendingTool: String? = null
        val answerJob = launch {
            while (pendingId == null) delay(250)
            assertNull(client.answerApproval(conn, pendingId!!, true))
        }
        val out = client.chat(conn, BitClient.newSessionId(), "E2E-CMD-SHELL") { list ->
            list.firstOrNull { it.tool == "shell" }?.let {
                if (pendingId == null) {
                    pendingId = it.id
                    pendingTool = it.params
                }
            }
        }
        answerJob.join()
        assertNull("审批对话失败：${out.error}", out.error)
        assertNotNull("应收到 shell 审批卡片", pendingId)
        assertTrue("审批卡片应含命令参数，实际=$pendingTool", pendingTool!!.contains("e2e-shell-ok"))
        assertTrue("最终回复应含 E2E-FINAL-OK，实际=${out.reply}", out.reply.contains("E2E-FINAL-OK"))
        // 审批应答后队列表清空
        assertEquals(0, client.listApprovals(conn).size)
    }

    @Test
    fun approvalRoundTripDeny() = runBlocking {
        val payload = scanPayload("http://$host:$port", "http://127.0.0.1:1/relay/$rid")
        val client = BitClient()
        val conn = client.connect(payload)

        // 拒绝路径：手机端应答拒绝 → 工具不执行 → 回合正常收尾（不悬挂、无错误）
        var pendingId: String? = null
        val answerJob = launch {
            while (pendingId == null) delay(250)
            client.answerApproval(conn, pendingId!!, false)
        }
        val out = client.chat(conn, BitClient.newSessionId(), "E2E-CMD-SHELL") { list ->
            list.firstOrNull { it.tool == "shell" }?.let { if (pendingId == null) pendingId = it.id }
        }
        answerJob.join()
        assertNull("拒绝后对话应正常完成：${out.error}", out.error)
        assertTrue(out.reply.isNotEmpty())
    }

    @Test
    fun wrongPasswordRejected() = runBlocking {
        val payload = scanPayload("http://$host:$port", "http://127.0.0.1:1/relay/$rid", pwd = "00000000")
        try {
            BitClient().connect(payload)
            throw IllegalStateException("错误密码不应连接成功")
        } catch (e: BitClient.ConnectException) {
            assertTrue("应提示认证失败，实际=${e.message}", e.message!!.contains("认证失败"))
        }
    }

    @Test
    fun wrongKeyRejected() = runBlocking {
        val bad = org.json.JSONObject(payloadJson("http://$host:$port", "http://127.0.0.1:1/relay/$rid"))
            .put("key", "not-the-right-key").toString()
        val payload = QrPayload.parse(
            BitCrypt.decrypt(BitCrypt.encrypt(bad, ByteArray(16) { 1 })) ?: throw AssertionError("解密失败"),
        )
        try {
            BitClient().connect(payload)
            throw IllegalStateException("错误 Key 不应连接成功")
        } catch (e: BitClient.ConnectException) {
            assertTrue("应提示认证失败，实际=${e.message}", e.message!!.contains("认证失败"))
        }
    }

    @Test
    fun unreachableCandidatesFailGracefully() {
        val payload = scanPayload("http://127.0.0.1:9", "http://127.0.0.1:1/relay/$rid")
        try {
            runBlocking { BitClient().connect(payload) }
            throw IllegalStateException("不可达候选不应连接成功")
        } catch (e: BitClient.ConnectException) {
            val msg = e.message!!
            assertTrue(msg.contains("127.0.0.1:9"))
            assertTrue(msg.contains("127.0.0.1:1"))
            assertTrue(msg.contains("无法连接"))
        }
    }

    /// 非法请求：空 session_id / 空消息必须被桌面端 400 拒绝（绝不落入桌面激活会话）
    @Test
    fun illegalChatRequestsRejected() {
        val http = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val json = "application/json; charset=utf-8".toMediaType()
        fun post(body: String): Int {
            val req = Request.Builder()
                .url("http://$host:$port/api/chat")
                .header("Authorization", "Bearer $key")
                .header("X-Access-Password", password)
                .post(body.toRequestBody(json))
                .build()
            http.newCall(req).execute().use { return it.code }
        }
        assertEquals(400, post("""{"message":"hi","session_id":""}"""))
        assertEquals(400, post("""{"message":"  ","session_id":"remote-e2e"}"""))
    }
}
