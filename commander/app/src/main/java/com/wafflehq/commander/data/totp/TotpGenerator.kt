package com.wafflehq.commander.data.totp

import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238/4226 TOTP, kept byte-for-byte compatible with claude-cli's src/totp.ts
 * (HMAC-SHA1, 30s step, 6 digits, standard Base32 alphabet, standard dynamic truncation).
 */
object TotpGenerator {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val CODE_DIGITS = 6
    private const val CODE_MODULUS = 1_000_000
    private const val STEP_SECONDS = 30L
    private const val HMAC_ALGORITHM = "HmacSHA1"

    fun decodeBase32(secret: String): ByteArray {
        val cleaned = secret.trim().uppercase().trimEnd('=')
        var bitBuffer = 0
        var bitCount = 0
        val bytes = ArrayList<Byte>(cleaned.length * 5 / 8 + 1)
        for (char in cleaned) {
            val value = BASE32_ALPHABET.indexOf(char)
            require(value >= 0) { "Ungueltiges Base32-Zeichen: $char" }
            bitBuffer = (bitBuffer shl 5) or value
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                bytes.add(((bitBuffer shr bitCount) and 0xFF).toByte())
            }
        }
        return bytes.toByteArray()
    }

    fun generate(secretBase32: String, timeMillis: Long = System.currentTimeMillis()): String {
        val counter = TimeUnit.MILLISECONDS.toSeconds(timeMillis) / STEP_SECONDS
        return hotp(decodeBase32(secretBase32), counter)
    }

    private fun hotp(secret: ByteArray, counter: Long): String {
        val counterBytes = ByteArray(8)
        var remaining = counter
        for (index in 7 downTo 0) {
            counterBytes[index] = (remaining and 0xFF).toByte()
            remaining = remaining shr 8
        }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binaryCode = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        return (binaryCode % CODE_MODULUS).toString().padStart(CODE_DIGITS, '0')
    }
}
