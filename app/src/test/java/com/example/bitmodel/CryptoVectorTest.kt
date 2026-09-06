// yxpil · BIT Mobile
// 协议层测试：BIT-Crypt v1 / bitsign-v2 / 二维码 payload。
// 加密与签名向量由 node（crypto）按桌面端 security.rs 同口径独立生成，交叉验证实现正确性。
package com.example.bitmodel.bit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoVectorTest {
    // ── BIT-Crypt v1：解密向量（node crypto 生成，salt 固定）──

    @Test
    fun decryptVectorAscii() {
        val enc = "BIT1:ABEiM0RVZneImaq7zN3u/3C6Et+iCibnFQ=="
        assertEquals("hello bit", BitCrypt.decrypt(enc))
    }

    @Test
    fun decryptVectorChinese() {
        val enc = "BIT1:3q2+78r+ur4BI0VniavN76PSHDIsNFCwlo8G5njGUK1jFukMv60zqSU="
        assertEquals("你好，BIT 桌面端！", BitCrypt.decrypt(enc))
    }

    @Test
    fun decryptVectorMultiBlock() {
        // 100 字节明文 → 跨 4 个 32B keystream 块，验证 ctr 进位正确
        val enc = "BIT1:AAECAwQFBgcICQoLDA0OD5f6+wYzlbA15uRngXdpaCQSSw6iYj+nXpo8UAYuipzZ7YC8Qw5zR2v1pieyVQ/XsFklyt3bqXfhHooTQl61FOMLjF8oVSDLpbNaBBQHILk5ZIV3MkTrS4Q9tfT6ikS1LEbLxjQ="
        assertEquals("x".repeat(100), BitCrypt.decrypt(enc))
    }

    @Test
    fun encryptRoundtripRandomSalt() {
        val msgs = listOf(
            "正常的技术问题",
            "BIT1: 前缀出现在明文里也不影响",
            "",
            "emoji--free 中文 + ASCII mix 42",
        )
        for (m in msgs) {
            val salt = ByteArray(16) { (it * 7 + 3).toByte() }
            val enc = BitCrypt.encrypt(m, salt)
            assertTrue(enc.startsWith("BIT1:"))
            if (m.isNotEmpty()) assertFalse(enc.contains(m))
            assertEquals(m, BitCrypt.decrypt(enc))
        }
    }

    @Test
    fun decryptRejectsGarbage() {
        assertNull(BitCrypt.decrypt("not a bit code"))
        assertNull(BitCrypt.decrypt("BIT1:!!!not-base64!!!"))
        assertNull(BitCrypt.decrypt("BIT1:QUJD")) // 解码后 3 字节 < 16
        assertNull(BitCrypt.decrypt("BIT1:")) // 空体
    }

    // ── bitsign-v2：签名向量（node crypto 生成）──

    private val key = "bit_test_key_1234567890"
    private val material = "ab12cd34ef56ab12"
    private val rid = "rid3333"
    private val ts = 1_000_000L
    private val nonce = "nonce1234"

    @Test
    fun bitsignVector() {
        val sign = Bitsign.bitsign(key, material, rid, ts, nonce, "POST", "/api/chat")
        assertEquals("103ae04ead8f546b69dd61631424cf9f5ff6521a80077411f3bae94cf41b312d", sign)
        // 同参数确定；任一参数变动即变
        assertEquals(sign, Bitsign.bitsign(key, material, rid, ts, nonce, "POST", "/api/chat"))
        assertNotEquals(sign, Bitsign.bitsign(key, material, rid, ts + 1, nonce, "POST", "/api/chat"))
        assertNotEquals(sign, Bitsign.bitsign(key, material, rid, ts, nonce, "GET", "/api/chat"))
        assertNotEquals(sign, Bitsign.bitsign(key, material, "rid4444", ts, nonce, "POST", "/api/chat"))
        assertNotEquals(sign, Bitsign.bitsign(key, "ff" + material.substring(2), rid, ts, nonce, "POST", "/api/chat"))
        assertNotEquals(sign, Bitsign.bitsign("other_key", material, rid, ts, nonce, "POST", "/api/chat"))
    }

    @Test
    fun deviceMaterialVector() {
        // sha256("bitdev-material:bitdev_abcdef") 前 8 字节 hex（与 device.rs::sig_material 同口径）
        assertEquals("85775ea887f6553d", Bitsign.deviceMaterial("bitdev_abcdef"))
    }

    @Test
    fun workerBindAndProofVector() {
        val bind = Bitsign.workerBind(key, rid)
        assertEquals("3b5e67c5ece9885ca0dd56e1725ebb1bca0548478fd3e5a71b4a8ea03c3bb998", bind)
        val proof = Bitsign.proofOf(bind, "challenge-abc")
        assertEquals("090b643879306d1fc21e000d9d9e17ef259914b11b62651b4784ab8da2581667", proof)
    }

    @Test
    fun signHeadersShape() {
        val h = Bitsign.signHeaders(key, material, rid, "POST", "/api/chat", now = ts)
        assertEquals(3, h.size)
        assertEquals(ts.toString(), h["x-bit-ts"])
        assertEquals(64, h["x-bit-sign"]?.length)
        assertTrue(h["x-bit-nonce"]!!.length >= 8)
        assertTrue(h["x-bit-sign"]!!.all { it.isDigit() || it in 'a'..'f' })
    }
}

class QrPayloadTest {
    private fun payloadJson(): String = JSONObject().apply {
        put("v", 2)
        put("app", "bit")
        put("port", 8600)
        put("key", "bit_client_key")
        put("rid", "7700d2d131015c46df75904768f4ad01")
        put("sidPolicy", "device")
        put("retention", "device-only")
        put(
            "methods",
            JSONObject()
                .put("lan", org.json.JSONArray().put("http://192.168.1.10:8600"))
                .put("direct6", org.json.JSONArray().put("http://[fe80::1]:8600"))
                .put("relay", "https://osbt.space/relay/7700d2d131015c46df75904768f4ad01"),
        )
        put("pwd", "87654321")
        put("alg", "bitsign-v2")
    }.toString()

    @Test
    fun parseHappyPath() {
        val p = QrPayload.parse(payloadJson())
        assertEquals(2, p.version)
        assertEquals("bit_client_key", p.clientKey)
        assertEquals("87654321", p.password)
        assertEquals(listOf("http://192.168.1.10:8600", "http://[fe80::1]:8600"), p.directCandidates)
        assertEquals("https://osbt.space/relay/7700d2d131015c46df75904768f4ad01", p.relay)
    }

    @Test
    fun parseRejectsNonBitAndOldVersion() {
        val badApp = JSONObject(payloadJson()).put("app", "other").toString()
        assertTrue(
            runCatching { QrPayload.parse(badApp) }.exceptionOrNull()?.message?.contains("不是 BIT") == true,
        )
        val old = JSONObject(payloadJson()).put("v", 1).toString()
        assertTrue(
            runCatching { QrPayload.parse(old) }.exceptionOrNull()?.message?.contains("版本过旧") == true,
        )
        val noKey = JSONObject(payloadJson()).apply { remove("key") }.toString()
        assertTrue(
            runCatching { QrPayload.parse(noKey) }.exceptionOrNull()?.message?.contains("key") == true,
        )
        val notJson = "{broken"
        assertTrue(runCatching { QrPayload.parse(notJson) }.isFailure)
    }

    @Test
    fun parseToleratesMissingOptionalFields() {
        val minimal = JSONObject()
            .put("v", 2)
            .put("app", "bit")
            .put("key", "k")
            .put("rid", "r")
            .toString()
        val p = QrPayload.parse(minimal)
        assertNull(p.password)
        assertTrue(p.directCandidates.isEmpty())
        assertEquals("", p.relay)
    }

    @Test
    fun newSessionIdFormat() {
        val id = BitClient.newSessionId()
        assertTrue(id.startsWith("remote-"))
        assertEquals(7 + 16, id.length)
        assertTrue(id.removePrefix("remote-").all { it.isDigit() || it in 'a'..'f' })
    }
}
