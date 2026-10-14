package com.zen.blaster.core

import com.zen.blaster.model.Node
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

// ============================================================================
//  Все виды конфигов, которые Zen отдаёт ядру sing-box, — на диск.
//
//  Сам тест проверяет только то, что ядро 1.14 гарантированно отвергнет
//  (старые поля). Полная проверка — настоящим ядром той же версии:
//  tools/check-configs.sh прогоняет каждый файл через `sing-box check`.
//  Так конфиг ловится на машине, а не на телефоне в метро.
// ============================================================================
class ConfigMatrixTest {

    companion object {
        // Чистим один раз на весь класс: JUnit создаёт экземпляр на каждый
        // тест, и чистка в поле стирала вывод соседнего теста.
        private val out = File("build/zen-configs")
        @BeforeClass @JvmStatic fun clean() { out.deleteRecursively(); out.mkdirs() }
    }

    private val uuid = "3f1c2a4e-8b7d-4c21-9e55-0a6b7c8d9e10"
    // Валидный по форме ключ x25519 (32 байта base64url) — ядро его декодирует.
    private val pbk = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"

    private val nodes = listOf(
        Node("VLESS", "reality", "203.0.113.10", 443, uuid = uuid, sni = "www.microsoft.com",
            pbk = pbk, sid = "ab12", flow = "xtls-rprx-vision", security = "reality", fp = "chrome"),
        Node("VLESS", "ws-cdn", "cdn.example.com", 443, uuid = uuid, transport = "ws",
            path = "/ws", host = "cdn.example.com", sni = "cdn.example.com"),
        Node("VLESS", "grpc", "203.0.113.11", 443, uuid = uuid, transport = "grpc",
            serviceName = "svc", sni = "grpc.example.com"),
        Node("VLESS", "httpupgrade", "203.0.113.12", 80, uuid = uuid, transport = "httpupgrade",
            path = "/up", host = "h.example.com", security = "none"),
        Node("VMess", "vmess-ws", "203.0.113.13", 443, uuid = uuid, transport = "ws",
            path = "/vm", sni = "vm.example.com"),
        Node("Hysteria2", "hy2-hop", "203.0.113.14", 443, auth = "secret", sni = "hy.example.com",
            obfs = "salamander", obfsPw = "obfspw", hopPorts = "20000-30000,443", alpn = "h3"),
        Node("TUIC", "tuic", "203.0.113.15", 443, uuid = uuid, password = "pw",
            sni = "tuic.example.com", cc = "bbr"),
        Node("Trojan", "trojan", "203.0.113.16", 443, password = "pw", sni = "tr.example.com"),
        Node("Shadowsocks", "ss", "203.0.113.17", 8388, method = "aes-128-gcm", password = "pw"),
        Node("VLESS", "wl", "203.0.113.18", 443, uuid = uuid, transport = "ws", path = "/",
            host = "wl.example.com", sni = "wl.example.com").apply { wl = true },
    )

    private fun save(name: String, cfg: String) {
        File(out, "$name.json").writeText(JSONObject(cfg).toString(2))
    }

    // То, что ядро 1.14 отвергнет при старте, — не должно встречаться нигде.
    private fun assertModern(name: String, cfg: String) {
        val root = JSONObject(cfg)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        for (i in 0 until servers.length()) {
            val s = servers.getJSONObject(i)
            assertTrue("$name: DNS-сервер без type", s.has("type"))
            assertFalse("$name: старый address у DNS", s.has("address"))
            assertFalse("$name: detour direct у DNS", s.optString("detour") == "direct")
            assertFalse("$name: strategy у DNS-сервера", s.has("strategy"))
        }
        assertFalse("$name: independent_cache", root.getJSONObject("dns").has("independent_cache"))
        val ins = root.getJSONArray("inbounds")
        for (i in 0 until ins.length()) assertFalse("$name: sniff во входе", ins.getJSONObject(i).has("sniff"))
        val outs = root.getJSONArray("outbounds")
        for (i in 0 until outs.length())
            assertFalse("$name: domain_strategy у выхода", outs.getJSONObject(i).has("domain_strategy"))
        // Перевод старого вида свежий конфиг не трогает.
        assertEquals("$name: upgrade не идемпотентен", cfg, SingBoxCompat.upgrade(cfg))
    }

    private fun build(n: Node?, vpn: Boolean = true, dpi: Boolean = false, chain: Boolean = false,
                      proxy: Boolean = false, xray: Boolean = false, geo: Boolean = false,
                      ipv6: Boolean = false, armor: Boolean = true, front: String = "",
                      doh: String = "https://1.1.1.1/dns-query",
                      pre: String = "https://9.9.9.9/dns-query",
                      lan: Boolean = false, hy: Boolean = false): String =
        ConfigBuilder.build(n, geoRouting = geo, ipv6 = ipv6, vpnEnabled = vpn, dpiEnabled = dpi,
            armor = armor, viaXray = xray, proxyMode = proxy, frontDomain = front,
            hyDown = if (hy) 50 else 0, hyUp = if (hy) 10 else 0,
            lanProxy = lan, lanUser = "zen", lanPass = if (lan) "pw" else "",
            dohUrl = doh, directDohUrl = pre, chainViaDpi = chain).toString()

    @Test fun everyNodeAndMode() {
        for (n in nodes) {
            val cases = mapOf(
                "tun" to build(n),
                "tun-geo-ipv6" to build(n, geo = true, ipv6 = true, armor = false),
                "tun-dpi-chain" to build(n, dpi = true, chain = true),
                "proxy" to build(n, proxy = true),
                "tun-lan" to build(n, lan = true),
                "tun-front" to build(n, front = "vk.com"),
                "tun-hy-brutal" to build(n, hy = true),
            )
            for ((mode, cfg) in cases) {
                val name = "${n.name}--$mode"
                assertModern(name, cfg); save(name, cfg)
            }
        }
        // Режимы без своей ноды и с особым DNS.
        val special = mapOf(
            "dpi-only" to build(null, vpn = false, dpi = true),
            "direct-only" to build(null, vpn = false),
            "xray" to build(nodes[0], xray = true),
            "dns-system" to build(nodes[0], doh = "local", pre = "local", geo = true),
            "dns-by-name" to build(nodes[0], doh = "https://dns.example.org/dns-query",
                pre = "https://dns.quad9.net/dns-query"),
            "dns-controld-path" to build(nodes[0], doh = "https://76.76.2.11/p0"),
            "dns-tls-port" to build(nodes[0], doh = "tls://1.1.1.1:853"),
        )
        for ((name, cfg) in special) { assertModern(name, cfg); save(name, cfg) }
    }

    // Конфиг, сохранённый версией 3.4 под ядро 1.11: так он лежит в
    // prefs.lastConfig у всех, кто обновится, и его поднимают плитка и
    // always-on. После перевода ядро 1.14 обязано его принять.
    @Test fun legacyLastConfig() {
        val legacy = JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject().put("tag", "remote-dns").put("address", "https://1.1.1.1/dns-query")
                        .put("detour", "proxy").put("strategy", "ipv4_only"))
                    .put(JSONObject().put("tag", "boot-dns").put("address", "https://9.9.9.9/dns-query")
                        .put("detour", "direct").put("strategy", "ipv4_only"))
                    .put(JSONObject().put("tag", "ru-dns").put("address", "local")
                        .put("detour", "direct").put("strategy", "ipv4_only")))
                .put("rules", JSONArray().put(JSONObject()
                    .put("domain_suffix", JSONArray().put(".ru")).put("server", "ru-dns")))
                .put("final", "remote-dns").put("independent_cache", true).put("strategy", "ipv4_only"))
            .put("inbounds", JSONArray()
                .put(JSONObject().put("type", "tun").put("tag", "tun-in")
                    .put("address", JSONArray().put("172.19.0.1/30")).put("auto_route", true)
                    .put("stack", "system").put("mtu", 1420))
                .put(JSONObject().put("type", "mixed").put("tag", "probe-in")
                    .put("listen", "127.0.0.1").put("listen_port", 2081).put("sniff", true)))
            .put("outbounds", JSONArray()
                .put(JSONObject().put("type", "vless").put("tag", "proxy").put("server", "vpn.example.com")
                    .put("server_port", 443).put("uuid", uuid).put("domain_strategy", "ipv4_only")
                    .put("tls", JSONObject().put("enabled", true).put("server_name", "vpn.example.com")))
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject()
                .put("rules", JSONArray().put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")))
                .put("final", "proxy").put("auto_detect_interface", true))
            .toString()
        val up = SingBoxCompat.upgrade(legacy)
        assertModern("legacy", up)
        val rules = JSONObject(up).getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).optString("action"))
        save("legacy-upgraded", up)
    }
}
