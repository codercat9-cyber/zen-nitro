package com.zen.blaster.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Байтовый разбор: BLAKE2b/Salamander, дамп, расшифровка ответов, след ядра.
class ByteDiagTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // RFC 7693, приложение A: BLAKE2b-512("abc").
    @Test fun blake2bRfcVector() {
        assertEquals("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
            "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            toHex(Blake2b.hash("abc".toByteArray(), 64)))
    }

    // Пустое сообщение и сообщение ровно в блок (128 Б) — граничные случаи.
    @Test fun blake2bEdges() {
        assertEquals("786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
            "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            toHex(Blake2b.hash(ByteArray(0), 64)))
        assertEquals(32, Blake2b.hash(ByteArray(128) { it.toByte() }, 32).size)
        assertFalse(Blake2b.hash(ByteArray(128), 32).contentEquals(Blake2b.hash(ByteArray(129), 32)))
    }

    @Test fun salamanderRoundTrip() {
        val payload = ByteArray(1200) { (it * 7).toByte() }
        val wire = Blake2b.salamander("pw-123", payload, salt = ByteArray(8) { 1 })
        assertEquals(1208, wire.size)
        assertArrayEquals(ByteArray(8) { 1 }, wire.copyOfRange(0, 8))
        assertFalse(wire.copyOfRange(8, 1208).contentEquals(payload))
        assertArrayEquals(payload, Blake2b.unSalamander("pw-123", wire, wire.size))
        assertFalse(payload.contentEquals(Blake2b.unSalamander("другой", wire, wire.size)))
    }

    @Test fun hexRowHasAscii() {
        val r = Hex.row("HTTP/1.1 403\r\n".toByteArray())
        assertEquals("48 54 54 50 2f 31 2e 31 20 34 30 33 0d 0a |HTTP/1.1 403..|", r)
        val rows = Hex.rows(ByteArray(40) { 0x41 }, max = 32)
        assertEquals(3, rows.size)
        assertTrue(rows[0].startsWith("0000  41 41"))
        assertTrue(rows[2].contains("ещё 8 Б"))
    }

    @Test fun explainTlsAlertAndServerHello() {
        val alert = hex("15030300020228")
        assertTrue(Hex.explainTcp(alert, alert.size).contains("handshake_failure"))
        val sh = hex("160303007a020000760303" + "11".repeat(32))
        assertTrue(Hex.explainTcp(sh, sh.size).contains("ServerHello"))
        val hrr = hex("160303007a020000760303cf21ad74e59a6111be" + "00".repeat(24))
        assertTrue(Hex.explainTcp(hrr, hrr.size).contains("HelloRetryRequest"))
    }

    @Test fun explainOperatorStub() {
        val b = "HTTP/1.1 302 Found\r\nLocation: http://warning.rt.ru/?id=1\r\n\r\n".toByteArray()
        val s = Hex.explainTcp(b, b.size)
        assertTrue(s, s.contains("302") && s.contains("заглушка"))
    }

    // Version Negotiation: наш SCID стоит у сервера в DCID.
    @Test fun explainVersionNegotiation() {
        val ours = hex("0102030405060708")
        val vn = hex("80" + "00000000" + "08" + "0102030405060708" + "08" + "a1a2a3a4a5a6a7a8" + "00000001" + "6b3343cf")
        val r = Hex.explainVn(vn, vn.size, ours)
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("v1") && r.text.contains("v2") && r.text.contains("настоящий"))
        val alien = Hex.explainVn(vn, vn.size, hex("ffffffffffffffff"))
        assertTrue(alien.text.contains("чужой"))
        assertFalse(Hex.explainVn(hex("4000000000000000"), 8, ours).ok)
    }

    @Test fun errnoFromMessages() {
        assertEquals("EPERM", Hex.errno(java.net.SocketException("Binding socket to network 529 failed: EPERM (Operation not permitted)")))
        assertEquals("ECONNRESET", Hex.errno(java.net.SocketException("Connection reset")))
        assertEquals("ETIMEDOUT", Hex.errno(java.net.SocketTimeoutException("failed to connect after 5000ms")))
    }

    // Строки — из logcat Xiaomi 14, 09.11.2026, «АВТО ВЫБОР» Пантеры.
    @Test fun coreTrailSeesDialHang() {
        CoreTrail.reset()
        repeat(5) {
            CoreTrail.feed("2026/11/09 17:16:0$it.892567 [Info] [42639$it] transport/internet/tcp: dialing TCP to tcp:103.76.130.95:445")
        }
        CoreTrail.feed("2026/11/09 17:17:09.896883 [Info] [4263753929] app/proxyman/outbound: app/proxyman/outbound: " +
            "failed to process outbound traffic > proxy/vless/outbound: failed to find an available destination")
        CoreTrail.feed("2026/11/09 17:17:13.901603 [Info] [2065938103] app/proxyman/outbound: app/proxyman/outbound: " +
            "failed to process outbound traffic > proxy/vless/outbound: failed to find an available destination")
        CoreTrail.feed("[Info] [1] app/proxyman/inbound: connection ends > read udp 127.0.0.1:1: use of closed network connection")
        val lines = CoreTrail.lines()
        assertTrue(lines.joinToString("\n"), lines[0].contains("дозвонов до сервера 5, открылось соединений 0") &&
            lines[0].contains("НЕ ДОЗВАНИВАЕТСЯ"))
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("×2") && lines[1].contains("available destination"))
        assertFalse(lines[1].contains("[4263753929]"))
        assertEquals("dial=5/open=0/last=no_dest", CoreTrail.code())
    }

    @Test fun groupsFoundInXrayConfig() {
        val body = """[{"remarks":"АВТО","outbounds":[
            {"protocol":"vless","settings":{"vnext":[{"address":"1.1.1.1","port":445}]}},
            {"protocol":"vless","settings":{"vnext":[{"address":"2.2.2.2","port":443}]}},
            {"protocol":"freedom"}],
            "routing":{"balancers":[{"tag":"b","selector":["p"],"strategy":{"type":"leastPing"}}]}}]"""
        val g = XrayConfigParser.groups(body)
        assertEquals(1, g.size)
        assertTrue(g[0], g[0].contains("2 серверов") && g[0].contains("leastPing") && g[0].contains("1.1.1.1:445"))
        assertTrue(XrayConfigParser.groups("""[{"remarks":"один","outbounds":[{"protocol":"vless"}]}]""").isEmpty())
    }
}

// Конфиг-группа провайдера (Node.raw): что Xray получит на самом деле.
class GroupConfigTest {
    private val raw = """{"remarks":"АВТО ВЫБОР","log":{"loglevel":"warning"},
        "api":{"tag":"api","services":["StatsService"]},"stats":{},
        "inbounds":[{"tag":"socks","port":10808,"protocol":"socks"},{"tag":"api","port":10085,"protocol":"dokodemo-door"}],
        "outbounds":[
          {"tag":"p1","protocol":"vless","settings":{"vnext":[{"address":"103.76.130.95","port":445,"users":[{"id":"u1"}]}]}},
          {"tag":"p2","protocol":"vless","settings":{"vnext":[{"address":"adress-8.uptime-video.com","port":443,"users":[{"id":"u2"}]}]}},
          {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
        "observatory":{"subjectSelector":["p"],"probeUrl":"https://www.google.com/generate_204"},
        "routing":{"balancers":[{"tag":"auto","selector":["p"],"strategy":{"type":"leastPing"}}],
          "rules":[
            {"inboundTag":["api"],"outboundTag":"api"},
            {"ip":["geoip:private","geoip:ru"],"outboundTag":"direct"},
            {"domain":["geosite:category-ads-all","ads.example.com"],"outboundTag":"block"},
            {"inboundTag":["socks"],"network":"tcp,udp","balancerTag":"auto"}]}}"""

    @Test fun rawKeepsBalancerDropsGeoAndApi() {
        val n = com.zen.blaster.model.Node(type = "VLESS", name = "АВТО ВЫБОР", addr = "103.76.130.95", port = 445, raw = raw)
        assertTrue(XrayEngine.handles(n))
        assertEquals(2, XrayEngine.groupSize(n))
        val c = XrayEngine.buildRaw(n)
        assertFalse(c.has("api")); assertFalse(c.has("stats"))
        assertEquals("info", c.getJSONObject("log").getString("loglevel"))
        val ins = c.getJSONArray("inbounds")
        assertEquals(1, ins.length()); assertEquals("socks-in", ins.getJSONObject(0).getString("tag"))
        assertTrue(c.has("observatory"))
        assertEquals("leastPing", c.getJSONObject("routing").getJSONArray("balancers")
            .getJSONObject(0).getJSONObject("strategy").getString("type"))
        val rules = c.getJSONObject("routing").getJSONArray("rules")
        // api-правило и правило «geoip → direct» ушли; у рекламного остался обычный домен.
        assertEquals(2, rules.length())
        val ads = rules.getJSONObject(0)
        assertEquals("block", ads.getString("outboundTag"))
        assertEquals(1, ads.getJSONArray("domain").length())
        assertEquals("ads.example.com", ads.getJSONArray("domain").getString(0))
        val main = rules.getJSONObject(1)
        assertEquals("socks-in", main.getJSONArray("inboundTag").getString(0))
        assertEquals("auto", main.getString("balancerTag"))
        assertEquals(4, c.getJSONArray("outbounds").length())
    }

    @Test fun groupDetectedInSubscription() {
        val nodes = XrayConfigParser.parse("[$raw]")
        assertEquals(1, nodes.size)
        assertTrue(nodes[0].raw.isNotEmpty())
        assertEquals("103.76.130.95", nodes[0].addr)
        val single = XrayConfigParser.parse("""[{"remarks":"один","outbounds":[
            {"protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u"}]}]}}]}]""")
        assertTrue(single.isEmpty() || single[0].raw.isEmpty())
        // Сохранение в кеш и обратно не теряет конфиг группы.
        val back = com.zen.blaster.model.Node.fromJson(nodes[0].toJson())
        assertEquals(nodes[0].raw, back.raw)
    }
}

// Отпечаток для Xray: лёгкий Chrome 120 вместо тяжёлых (замер 10.11.2026).
class FingerprintTest {
    @Test fun heavyAndAndroidBecomeChrome120ForReality() {
        assertEquals("hellochrome_120", XrayEngine.xrayFp("chrome", reality = true))
        assertEquals("hellochrome_120", XrayEngine.xrayFp("firefox", reality = false))
        assertEquals("hellochrome_120", XrayEngine.xrayFp("", reality = true))
        assertEquals("hellochrome_120", XrayEngine.xrayFp("edge", reality = true))
        assertEquals("hellochrome_120", XrayEngine.xrayFp("android", reality = true))
        assertEquals("android", XrayEngine.xrayFp("android", reality = false))
        assertEquals("ios", XrayEngine.xrayFp("ios", reality = true))
    }

    @Test fun armoredGroupGetsLightFingerprintAndFragment() {
        val raw = """{"outbounds":[{"tag":"p1","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"fingerprint":"chrome","publicKey":"k","serverName":"a.ru"}}},
            {"tag":"p2","protocol":"vless","settings":{"vnext":[{"address":"5.6.7.8","port":443,"users":[{"id":"u"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"fingerprint":"android","publicKey":"k","serverName":"a.ru"}}}],
            "routing":{"balancers":[{"tag":"b","selector":["p"]}]}}"""
        val n = com.zen.blaster.model.Node(type = "VLESS", name = "g", addr = "1.2.3.4", port = 443, raw = raw)
        val c = XrayEngine.buildRaw(n, armor = true, fp = "edge")
        val outs = c.getJSONArray("outbounds")
        for (i in 0..1) {
            val st = outs.getJSONObject(i).getJSONObject("streamSettings")
            assertEquals("hellochrome_120", st.getJSONObject("realitySettings").getString("fingerprint"))
            assertEquals("frag", st.getJSONObject("sockopt").getString("dialerProxy"))
        }
        assertEquals("frag", outs.getJSONObject(2).getString("tag"))
        // Без брони — конфиг провайдера как есть.
        val plain = XrayEngine.buildRaw(n)
        assertEquals("chrome", plain.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("realitySettings").getString("fingerprint"))
    }
}
