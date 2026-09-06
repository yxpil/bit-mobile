// yxpil · BIT Mobile
// bitsign-v2 信道签名 + 云中继握手（与桌面端 security.rs / relay-worker 协议同口径）。
// canonical = "bitsign-v2\n{rid}\n{ts}\n{nonce}\n{method}\n{path}\n{device_material}"
// mac       = HMAC-SHA256(key = client_key, canonical)
// k         = SHA256("bitsign-v2:" + client_key)
// out[i]    = mac[i] rotl (i%7)+1 再异或 k[i%16]，sign = 64 hex
// 握手密钥  S = HMAC-SHA256(key = client_key, "bit-worker-bind:{rid}")（手机端与桌面端
// poller 各自从 client_key 派生同一把，经 Worker 挑战-应答换取连接许可 permit）。
package com.example.bitmodel.bit

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Bitsign {
    const val ALG: String = "bitsign-v2"

    fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }

    /// 设备签名材料：device_key → sha256("bitdev-material:" + key) 前 8 字节的 16 hex
    /// （与 device.rs::sig_material 同口径）。v0.5.14 桌面端不向手机端披露 device_key，
    /// 中继信道由桌面端侧校验，这里保留完整算法供桌面端配套升级后直接启用。
    fun deviceMaterial(deviceKey: String): String =
        hex(sha256("bitdev-material:$deviceKey".toByteArray(Charsets.UTF_8)).copyOfRange(0, 8))

    fun bitsign(
        clientKey: String,
        deviceMaterial: String,
        rid: String,
        ts: Long,
        nonce: String,
        method: String,
        path: String,
    ): String {
        val canonical = "$ALG\n$rid\n$ts\n$nonce\n$method\n$path\n$deviceMaterial"
        val mac = hmacSha256(clientKey.toByteArray(Charsets.UTF_8), canonical.toByteArray(Charsets.UTF_8))
        val k = sha256("$ALG:$clientKey".toByteArray(Charsets.UTF_8))
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val v = mac[i].toInt() and 0xFF
            val n = (i % 7) + 1
            val rot = ((v shl n) or (v ushr (8 - n))) and 0xFF
            out[i] = (rot xor (k[i % 16].toInt() and 0xFF)).toByte()
        }
        return hex(out)
    }

    /// 签名三件套请求头（nonce 16 hex 随机，防重放）
    fun signHeaders(
        clientKey: String,
        deviceMaterial: String,
        rid: String,
        method: String,
        path: String,
        now: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val ts = now
        val nonce = hex(sha256("$ts|$rid|$path".toByteArray() + randomBytes(8)).copyOfRange(0, 8))
        return mapOf(
            "x-bit-sign" to bitsign(clientKey, deviceMaterial, rid, ts, nonce, method, path),
            "x-bit-ts" to ts.toString(),
            "x-bit-nonce" to nonce,
        )
    }

    /// 中继握手密钥：S = HMAC-SHA256(key=client_key, "bit-worker-bind:{rid}")（64 hex）
    fun workerBind(clientKey: String, rid: String): String =
        hex(hmacSha256(clientKey.toByteArray(Charsets.UTF_8), "bit-worker-bind:$rid".toByteArray(Charsets.UTF_8)))

    /// 挑战应答：proof = HMAC-SHA256(key=bytes(S), msg=challenge)（64 hex）
    fun proofOf(bindHex: String, challenge: String): String =
        hex(hmacSha256(unhex(bindHex), challenge.toByteArray(Charsets.UTF_8)))

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
}
