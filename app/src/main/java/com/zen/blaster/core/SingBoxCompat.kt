package com.zen.blaster.core

import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  Конфиг sing-box под ядро 1.14.
//
//  С 1.11 на 1.14 ядро перестало понимать половину того, что мы ему давали:
//    • DNS-серверы старого вида {"address": "https://…"} — удалены в 1.14,
//      теперь {"type": "https", "server": "…", "path": "…"};
//    • "detour": "direct" у DNS-сервера — ошибка «detour to an empty direct
//      outbound makes no sense»: новый сервер и так ходит напрямую;
//    • "sniff" во входах — удалён в 1.13, остался только route-action sniff;
//    • "domain_strategy" у выходов — удалён в 1.14, вместо него
//      "domain_resolver" с тем же strategy внутри;
//    • "independent_cache" — устарел, кеш и так раздельный.
//
//  Свежие конфиги ConfigBuilder собирает уже в новом виде. А upgrade() нужен
//  для СОХРАНЁННЫХ: плитка в шторке, always-on и переподключение берут
//  prefs.lastConfig, собранный ещё прошлой версией. Без перевода первое же
//  включение после обновления упало бы на «unknown field». Перевод
//  идемпотентен — новый конфиг проходит через него без изменений.
// ============================================================================
object SingBoxCompat {

    // Резолвер, которым ядро разрешает имена самих серверов (VPN-ноды, DoH по
    // имени). Это всегда прямой путь мимо туннеля — до туннеля его ещё нет.
    const val BOOT = "boot-dns"
    // Системный резолвер оператора. Заводится только когда нужен: если
    // человек выбрал «системный DNS» или вписал DoH по имени, а не по адресу.
    const val SYS = "sys-dns"

    // "https://76.76.2.11/p0" → {type: https, server: 76.76.2.11, path: /p0}.
    // "local" → системный резолвер. detour "direct" не пишем вовсе (см. выше).
    fun dnsServer(tag: String, url: String, detour: String?): JSONObject {
        val o = JSONObject().put("tag", tag)
        val u = url.trim()
        if (u.isEmpty() || u == "local") return o.put("type", "local")
        val scheme = u.substringBefore("://", "").lowercase()
        val rest = if (scheme.isEmpty()) u else u.substringAfter("://")
        val hostPort = rest.substringBefore('/')
        val path = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
        val (host, port) = splitHostPort(hostPort)
        val type = when (scheme) {
            "https" -> "https"
            "h3" -> "h3"
            "tls" -> "tls"
            "quic" -> "quic"
            "tcp" -> "tcp"
            else -> "udp"                 // "udp://1.1.1.1" и голый адрес
        }
        o.put("type", type).put("server", host)
        if (port > 0) o.put("server_port", port)
        if ((type == "https" || type == "h3") && path.isNotEmpty() && path != "/dns-query")
            o.put("path", path)
        if (!detour.isNullOrEmpty() && detour != "direct") o.put("detour", detour)
        // Сервер по ИМЕНИ ядру надо сначала разрешить — иначе оно откажется
        // стартовать («missing domain resolver»). Спрашиваем систему: имя
        // DoH-сервера и так известно всем, а ходить по кругу через самого
        // себя резолвер не может.
        if (!Resolver.isIpLiteral(host)) o.put("domain_resolver", SYS)
        return o
    }

    private fun splitHostPort(s: String): Pair<String, Int> {
        if (s.startsWith("[")) {                       // [2620:fe::fe]:443
            val host = s.substringAfter('[').substringBefore(']')
            val port = s.substringAfter("]:", "").toIntOrNull() ?: 0
            return host to port
        }
        if (s.count { it == ':' } == 1) {              // 1.1.1.1:853
            return s.substringBefore(':') to (s.substringAfter(':').toIntOrNull() ?: 0)
        }
        return s to 0                                  // голый адрес или IPv6 без порта
    }

    // Нужен ли sys-dns: на него сослался хоть один сервер.
    fun needsSys(servers: JSONArray): Boolean {
        var has = false; var wants = false
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("tag") == SYS) has = true
            if (s.optString("domain_resolver") == SYS) wants = true
        }
        return wants && !has
    }

    fun upgrade(config: String): String = try {
        val root = JSONObject(config)
        var changed = false
        val dns = root.optJSONObject("dns")
        val strategy = dns?.optString("strategy").orEmpty().ifEmpty { "ipv4_only" }
        val servers = dns?.optJSONArray("servers")
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                if (s.has("type") || !s.has("address")) continue
                servers.put(i, dnsServer(s.optString("tag"), s.optString("address"),
                    s.optString("detour").ifEmpty { null }))
                changed = true
            }
            if (needsSys(servers)) {
                servers.put(JSONObject().put("tag", SYS).put("type", "local")); changed = true
            }
        }
        if (dns != null && dns.has("independent_cache")) { dns.remove("independent_cache"); changed = true }

        val ins = root.optJSONArray("inbounds")
        if (ins != null) for (i in 0 until ins.length()) {
            val o = ins.optJSONObject(i) ?: continue
            for (k in listOf("sniff", "sniff_override_destination", "sniff_timeout", "domain_strategy"))
                if (o.has(k)) { o.remove(k); changed = true }
        }

        val boot = bootTag(servers)
        val outs = root.optJSONArray("outbounds")
        if (outs != null) for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (!o.has("domain_strategy")) continue
            val ds = o.optString("domain_strategy")
            o.remove("domain_strategy")
            if (boot != null && !o.has("domain_resolver"))
                o.put("domain_resolver", JSONObject().put("server", boot).put("strategy", ds))
            changed = true
        }

        val route = root.optJSONObject("route")
        if (route != null && boot != null && !route.has("default_domain_resolver")) {
            route.put("default_domain_resolver", JSONObject().put("server", boot).put("strategy", strategy))
            changed = true
        }
        // Нюхать трафик теперь умеет только правило маршрута. В наших
        // конфигах оно было и раньше, но чужой сохранённый мог держать
        // sniff лишь во входе — тогда без правила пропали бы имена сайтов.
        val rules = route?.optJSONArray("rules")
        if (rules != null && !hasSniff(rules)) {
            val fixed = JSONArray().put(JSONObject().put("action", "sniff"))
            for (i in 0 until rules.length()) fixed.put(rules.get(i))
            route.put("rules", fixed); changed = true
        }
        if (changed) root.toString() else config
    } catch (_: Throwable) { config }

    private fun bootTag(servers: JSONArray?): String? {
        servers ?: return null
        var first: String? = null
        for (i in 0 until servers.length()) {
            val t = servers.optJSONObject(i)?.optString("tag").orEmpty()
            if (t == BOOT) return t
            if (first == null && t.isNotEmpty()) first = t
        }
        return first
    }

    private fun hasSniff(rules: JSONArray): Boolean {
        for (i in 0 until rules.length())
            if (rules.optJSONObject(i)?.optString("action") == "sniff") return true
        return false
    }
}
