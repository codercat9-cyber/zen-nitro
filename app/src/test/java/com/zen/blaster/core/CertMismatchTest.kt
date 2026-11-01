package com.zen.blaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Строки — из журналов Realme RMX5120, 31.10–01.11.2026 (sing-box 1.14).
class CertMismatchTest {
    @Test fun dnsThroughNode() {
        val h = CertMismatch.parse("ERROR[0003] [3778606186 313ms] router: process DNS packet: " +
            "x509: certificate is valid for *.avito.ru, avito.ru, not media.mediavitrina.ru")!!
        assertEquals("*.avito.ru", h.real)
        assertEquals("media.mediavitrina.ru", h.want)
        assertFalse(CertMismatch.isResolver("router: process DNS packet: x509: …"))
    }

    @Test fun outboundConnection() {
        val h = CertMismatch.parse("ERROR[0003] [1789948036 324ms] connection: open connection to " +
            "47.82.156.156:443 using outbound/vless[proxy]: x509: certificate is valid for " +
            "*.avito.ru, avito.ru, not eh.vk.ru")!!
        assertEquals("*.avito.ru", h.real)
        assertEquals("eh.vk.ru", h.want)
    }

    @Test fun truncatedLine() {
        // Обрезанная строка (старый журнал резал на 220 символах) — имя,
        // которого ждали, неизвестно, но и мусора вместо него нет.
        val h = CertMismatch.parse("… x509: certificate is valid for *.avito.ru, avito.ru, n")!!
        assertEquals("*.avito.ru", h.real)
        assertNull(h.want)
    }

    @Test fun resolverErrorIsNotNode() {
        assertTrue(CertMismatch.isResolver("dns: exchange failed for example.com: x509: " +
            "certificate is valid for 76.76.2.22, not 76.76.2.0"))
    }

    @Test fun noMismatch() {
        assertNull(CertMismatch.parse("ERROR connection: i/o timeout"))
    }
}
