package com.wafflehq.commander.data.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RFC 6238 Appendix B test vectors (SHA1, 6-digit truncation), secret = ASCII "12345678901234567890".
 */
class TotpGeneratorTest {

    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `generate matches RFC 6238 test vectors`() {
        assertEquals("287082", TotpGenerator.generate(secret, 59_000L))
        assertEquals("081804", TotpGenerator.generate(secret, 1_111_111_109_000L))
        assertEquals("050471", TotpGenerator.generate(secret, 1_111_111_111_000L))
        assertEquals("005924", TotpGenerator.generate(secret, 1_234_567_890_000L))
        assertEquals("279037", TotpGenerator.generate(secret, 2_000_000_000_000L))
    }

    @Test
    fun `generate is stable within the same 30s step`() {
        val stepStart = (1_111_111_109_000L / 30_000L) * 30_000L
        assertEquals(TotpGenerator.generate(secret, stepStart), TotpGenerator.generate(secret, stepStart + 29_000L))
    }

    @Test
    fun `generate changes across a 30s step boundary`() {
        val stepStart = (1_111_111_109_000L / 30_000L) * 30_000L
        val next = TotpGenerator.generate(secret, stepStart + 30_000L)
        val current = TotpGenerator.generate(secret, stepStart)
        assertEquals(false, current == next)
    }

    @Test
    fun `decodeBase32 rejects invalid characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.decodeBase32("not-valid-base32!!!")
        }
    }

    @Test
    fun `generate is case-insensitive and tolerates surrounding whitespace`() {
        val lower = TotpGenerator.generate(secret.lowercase(), 59_000L)
        val padded = TotpGenerator.generate("  $secret  ", 59_000L)
        assertEquals("287082", lower)
        assertEquals("287082", padded)
    }
}
