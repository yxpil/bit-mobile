// yxpil · BIT Mobile
// BIT-Crypt v1：连接二维码加密块解密（与桌面端 src-tauri/src/security.rs 同口径）。
// keystream = SHA256(master || salt(16B) || ctr_be_u32) 逐 32 字节块拼接（CTR 模式），
// 明文 = 密文 XOR keystream；blob = "BIT1:" + base64(salt(16B) || cipher)。
// 桌面端二维码图只编此密文块，普通扫码器读不出 client_key / rid 等凭据。
package com.example.bitmodel.bit

import java.security.MessageDigest
import java.util.Base64

object BitCrypt {
    /// 与桌面端 CRYPT_MASTER 一致（混淆级：防普通逆向直读，非对抗性加密）
    private const val MASTER = "bit-enc-v1-master:9f4c2a77e1b3d806a5c4f21e7d0b6a39:7d31"
    private const val PREFIX = "BIT1:"

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** 解密连接码。非 BIT1 前缀 / base64 非法 / 过短 / 非 UTF-8 → 返回 null（由调用方给出错误提示） */
    fun decrypt(blob: String): String? {
        val raw = blob.trim().removePrefix(PREFIX)
        if (raw == blob.trim()) return null // 无前缀
        val buf = try {
            Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (buf.size < 16) return null
        val salt = buf.copyOfRange(0, 16)
        val cipher = buf.copyOfRange(16, buf.size)
        val plain = ByteArray(cipher.size)
        var loadedCtr = -1
        var keystream = ByteArray(0)
        for (i in cipher.indices) {
            val ctr = i / 32
            if (ctr != loadedCtr) {
                loadedCtr = ctr
                val input = MASTER.toByteArray(Charsets.UTF_8) +
                    salt + byteArrayOf(
                    (ctr ushr 24).toByte(), (ctr ushr 16).toByte(),
                    (ctr ushr 8).toByte(), ctr.toByte()
                )
                keystream = sha256(input)
            }
            plain[i] = (cipher[i].toInt() xor keystream[i % 32].toInt()).toByte()
        }
        return try {
            String(plain, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /// 加密（仅供本机测试向量互验；生产路径只解不编）
    fun encrypt(plaintext: String, salt: ByteArray): String {
        require(salt.size == 16) { "salt must be 16 bytes" }
        val data = plaintext.toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(data.size)
        var loadedCtr = -1
        var keystream = ByteArray(0)
        for (i in data.indices) {
            val ctr = i / 32
            if (ctr != loadedCtr) {
                loadedCtr = ctr
                keystream = sha256(MASTER.toByteArray(Charsets.UTF_8) + salt + byteArrayOf(
                    (ctr ushr 24).toByte(), (ctr ushr 16).toByte(),
                    (ctr ushr 8).toByte(), ctr.toByte()
                ))
            }
            cipher[i] = (data[i].toInt() xor keystream[i % 32].toInt()).toByte()
        }
        val buf = salt + cipher
        return PREFIX + Base64.getEncoder().encodeToString(buf)
    }
}
