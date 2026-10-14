package com.zen.blaster.core

import com.zen.blaster.model.Node
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  Подписка, которая отдаёт готовый конфиг SING-BOX.
//
//  Панели держат для каждого известного клиента свой шаблон и отдают то, что
//  тот, по их мнению, умеет. Клиентам на sing-box (Hiddify, Karing, NekoBox)
//  прилетает не список ссылок и не конфиг Xray, а полноценный конфиг sing-box:
//  log, dns, inbounds, outbounds, route.
//
//  Мы такой формат не понимали и молча возвращали ноль нод. Со стороны это
//  выглядело хуже некуда: подписка отвечает, тело приходит, а серверы «есть
//  и вдруг нет». У Lunaire под шаблоном Hiddify приходило девять килобайт и
//  ни одной ноды — двадцать серверов уходили в «нет в подписке» и переставали
//  отвечать, потому что мы их больше и не спрашивали.
//
//  Поймали это 6 сентября 2026, когда журнал научился показывать начало
//  неразобранного тела.
//
//  Читаем ровно обратное тому, что пишет наш ConfigBuilder: outbound с живым
//  протоколом, его TLS и его транспорт. Служебное (direct, block, dns,
//  selector, urltest) пропускаем — это не серверы.
// ============================================================================
object SingBoxConfigParser {

    // Похоже ли тело на конфиг sing-box, а не на конфиг Xray.
    //
    // Отличаем по вещам, которых у Xray нет в принципе: секция route с
    // final/rules, inbounds типа tun, outbound-поля server_port и listen.
    // Одного «outbounds» мало — оно есть у обоих.
    fun looksLike(body: String): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{")) return false
        if (!t.contains("\"outbounds\"")) return false
        return t.contains("\"server_port\"") || t.contains("\"route\"") ||
               t.contains("\"listen\"") || t.contains("\"inbounds\"") &&
               t.contains("\"tun\"")
    }

    // Служебные выходы — не серверы.
    private val SERVICE = setOf(
        "direct", "block", "dns", "selector", "urltest", "socks", "http"
    )

    fun parse(body: String): List<Node> {
        val out = mutableListOf<Node>()
        try {
            val root = JSONObject(body.trimStart())
            val outs = root.optJSONArray("outbounds") ?: return out
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                nodeFrom(o)?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        return out
    }

    // Резолвер, который назвал сам провайдер: в sing-box он лежит в
    // dns.servers как address.
    fun dnsFrom(body: String): String? = try {
        val servers = JSONObject(body.trimStart())
            .optJSONObject("dns")?.optJSONArray("servers")
        var found: String? = null
        if (servers != null) for (i in 0 until servers.length()) {
            val one = servers.opt(i)
            val addr = when (one) {
                is String -> one
                is JSONObject -> one.optString("address")
                else -> ""
            }
            if (addr.startsWith("https://")) { found = addr; break }
        }
        found
    } catch (_: Throwable) { null }

    private fun nodeFrom(o: JSONObject): Node? {
        val proto = o.optString("type").lowercase()
        if (proto.isEmpty() || proto in SERVICE) return null
        val addr = o.optString("server")
        val port = o.optInt("server_port", 0)
        if (addr.isEmpty() || port == 0) return null
        val name = o.optString("tag").ifEmpty { addr }

        // ---- TLS ----
        val tls = o.optJSONObject("tls")
        val sni = tls?.optString("server_name").orEmpty()
        val alpn = tls?.optJSONArray("alpn")?.let { a ->
            (0 until a.length()).joinToString(",") { a.optString(it) }
        }.orEmpty()
        val reality = tls?.optJSONObject("reality")
        val pbk = reality?.optString("public_key").orEmpty()
        val sid = reality?.optString("short_id").orEmpty()
        val fp = tls?.optJSONObject("utls")?.optString("fingerprint").orEmpty()
        val insecure = tls?.optBoolean("insecure") == true
        val hasTls = tls?.optBoolean("enabled") == true

        // ---- транспорт ----
        val tr = o.optJSONObject("transport")
        var transport = tr?.optString("type").orEmpty().ifEmpty { "tcp" }
        // Имена транспортов у sing-box и в ссылках расходятся: http против h2.
        if (transport == "http") transport = "http"
        val path = tr?.optString("path").orEmpty()
        val host = tr?.optJSONObject("headers")?.optString("Host").orEmpty()
            .ifEmpty {
                tr?.optJSONArray("host")?.let { if (it.length() > 0) it.optString(0) else "" }.orEmpty()
            }
        val service = tr?.optString("service_name").orEmpty()

        // ---- порт-хоппинг Hysteria2 ----
        val hop = o.optJSONArray("server_ports")?.let { a ->
            (0 until a.length()).joinToString(",") { a.optString(it).replace(':', '-') }
        }.orEmpty()

        return when (proto) {
            "vless" -> Node("VLESS", name, addr, port,
                uuid = o.optString("uuid"), flow = o.optString("flow"),
                sni = sni, pbk = pbk, sid = sid, fp = fp, alpn = alpn,
                transport = transport, path = path, host = host, serviceName = service,
                security = if (hasTls) "tls" else "none", insecure = insecure)

            "vmess" -> Node("VMess", name, addr, port,
                uuid = o.optString("uuid"),
                alterId = o.optInt("alter_id", 0),
                encryption = o.optString("security").ifEmpty { "auto" },
                sni = sni, fp = fp, alpn = alpn,
                transport = transport, path = path, host = host, serviceName = service,
                security = if (hasTls) "tls" else "none", insecure = insecure)

            "trojan" -> Node("Trojan", name, addr, port,
                password = o.optString("password"),
                sni = sni, fp = fp, alpn = alpn,
                transport = transport, path = path, host = host, serviceName = service,
                security = "tls", insecure = insecure)

            "hysteria2" -> Node("Hysteria2", name, addr, port,
                auth = o.optString("password"),
                sni = sni, alpn = alpn,
                obfs = o.optJSONObject("obfs")?.optString("type").orEmpty(),
                obfsPw = o.optJSONObject("obfs")?.optString("password").orEmpty(),
                hopPorts = hop, insecure = insecure)

            "tuic" -> Node("TUIC", name, addr, port,
                uuid = o.optString("uuid"), password = o.optString("password"),
                cc = o.optString("congestion_control").ifEmpty { "bbr" },
                sni = sni, alpn = alpn, insecure = insecure)

            "shadowsocks" -> Node("Shadowsocks", name, addr, port,
                method = o.optString("method"), password = o.optString("password"))

            else -> null
        }
    }
}
