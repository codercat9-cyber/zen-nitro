package com.zen.blaster.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Строки — из журнала Xiaomi 14, 03.11.2026 (sing-box 1.14.4, FastLane).
class CoreRefusalTest {
    @Test fun hysteria2WrongPassword() {
        val v = CoreRefusal.parse("ERROR[0003] [2533174407 412ms] connection: open connection to " +
            "82.148.224.116:1935 using outbound/hysteria2[proxy]: authentication failed, status code: 404")!!
        assertTrue(v.contains("не принял пароль"))
        assertTrue(v.contains("код 404"))
    }

    @Test fun resolverIsNotNode() {
        assertNull(CoreRefusal.parse("dns: exchange failed for x.com. IN A: hysteria2 authentication failed"))
    }

    // Xiaomi 14, Tele2, 04.11.2026, «EKB-FI»: с компьютера порт открыт.
    @Test fun resetToNode() {
        val line = "ERROR[0001] [2620485253 82ms] connection: open connection to 149.154.167.41:443 using " +
            "outbound/vless[proxy]: dial rmnet_data1 (301): dial tcp 5.188.35.157:472: connect: connection refused"
        assertTrue(CoreRefusal.refusedTo(line) == "5.188.35.157:472")
        assertTrue(CoreRefusal.refusedTo("router: process DNS packet: dial rmnet_data1 (301): " +
            "dial tcp 5.188.35.157:472: connect: connection refused") == "5.188.35.157:472")
        // Сброс на прямом выходе (РУ-сайт мимо туннеля) — не про ноду.
        assertNull(CoreRefusal.refusedTo("connection: open connection to 1.2.3.4:443 using " +
            "outbound/direct[direct]: dial tcp 1.2.3.4:443: connect: connection refused"))
    }

    // Эмулятор, 06.11.2026: та же беда через Xray — у него свой формат строки.
    @Test fun resetToNodeViaXray() {
        assertTrue(CoreRefusal.refusedTo("[Xray] 2026/11/06 19:30:12 [Info] app/proxyman/outbound: failed to " +
            "process outbound traffic > proxy/vless/outbound: failed to find an available destination > " +
            "common/retry: [dial tcp 10.0.2.2:20001: connect: connection refused]") == "10.0.2.2:20001")
        // Прямой выход Xray (freedom) — не про ноду.
        assertNull(CoreRefusal.refusedTo("[Xray] proxy/freedom: failed to open connection > " +
            "dial tcp 1.2.3.4:443: connect: connection refused"))
    }

    @Test fun ordinaryTimeoutIsNotRefusal() {
        assertNull(CoreRefusal.parse("connection: open connection to 1.2.3.4:443 using " +
            "outbound/hysteria2[proxy]: context deadline exceeded"))
    }
}

// Свой локальный порт — не отказ сервера (журнал 09.11 с realme).
class CoreRefusalLoopbackTest {
    @Test fun loopbackIsNotServerRefusal() {
        org.junit.Assert.assertNull(CoreRefusal.refusedTo("ERROR[0001] connection: open connection to 1.2.3.4:443 " +
            "using outbound/socks[proxy]: dial tcp 127.0.0.1:38614: connect: connection refused"))
        org.junit.Assert.assertEquals("91.243.86.67:456", CoreRefusal.refusedTo("ERROR[0000] connection: open connection to " +
            "149.154.167.51:443 using outbound/vless[proxy]: dial ccmni0 (14): dial tcp 91.243.86.67:456: connect: connection refused"))
    }
}
