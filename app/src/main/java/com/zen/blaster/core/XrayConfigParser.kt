package com.zen.blaster.core

import com.zen.blaster.model.Node
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  Подписка, которая отдаёт не ссылки, а ГОТОВЫЕ КОНФИГИ.
//
//  Так делает, например, Capybara: на запрос прилетает не список vless://…,
//  а массив полных конфигов Xray в JSON. Наш парсер искал в теле «://», не
//  находил и честно докладывал «ни одного сервера» — притом что серверы там
//  были, просто в другой упаковке.
//
//  Здесь мы разбираем такой конфиг обратно в ноду: берём исходящее соединение
//  с настоящим протоколом (vless/trojan/shadowsocks), вытаскиваем адрес,
//  ключи и параметры транспорта — ровно то же, что несёт обычная ссылка.
//
//  Заодно ловим заглушку. Панель, не узнавшая клиента, отвечает одним
//  конфигом с именем вроде «Данное приложение не поддерживается» — это не
//  сервер, и подсовывать его человеку как ноду нельзя.
// ============================================================================
object XrayConfigParser {

    // Похоже ли тело на конфиг(и) Xray, а не на список ссылок.
    fun looksLikeConfig(body: String): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        return t.contains("\"outbounds\"")
    }

    // Имена, которыми панели отвечают «я тебя не знаю».
    private val STUB = listOf(
        "не поддерживается", "not supported", "unsupported",
        "обнови", "update your", "подписка не найдена", "not found"
    )

    fun isStub(name: String): Boolean =
        STUB.any { name.lowercase().contains(it) }

    // Разбирает тело подписки. Возвращает пустой список, если это не конфиг
    // или внутри одна заглушка.
    fun parse(body: String): List<Node> {
        val out = mutableListOf<Node>()
        try {
            val t = body.trimStart()
            val arr = if (t.startsWith("[")) JSONArray(t)
            else JSONArray().put(JSONObject(t))

            for (i in 0 until arr.length()) {
                val cfg = arr.optJSONObject(i) ?: continue
                val name = cfg.optString("remarks").ifEmpty { "config-${i + 1}" }
                if (isStub(name)) continue          // заглушка — не нода
                nodeFrom(cfg, name)?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        return out
    }

    // РЕЗОЛВЕР, КОТОРЫЙ НАЗВАЛ САМ ПРОВАЙДЕР.
    //
    //  Подписка в формате конфига несёт секцию dns со своими серверами. Это
    //  не пустяк: провайдер знает, какой резолвер дружит с ЕГО нодами и не
    //  отдаёт для них подменённые адреса. Наш собственный выбор — догадка,
    //  а это — сведения от того, кто держит серверы.
    //
    //  Берём первый DoH-адрес. Голые IP и «localhost» пропускаем: нам нужен
    //  именно шифрованный резолвер, обычный UDP-порт 53 оператор читает и
    //  подменяет.
    fun dnsFrom(body: String): String? = try {
        val t = body.trimStart()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        var found: String? = null
        outer@ for (i in 0 until arr.length()) {
            val servers = arr.optJSONObject(i)?.optJSONObject("dns")
                ?.optJSONArray("servers") ?: continue
            for (j in 0 until servers.length()) {
                // Сервер бывает строкой, а бывает объектом с полем address.
                val one = servers.opt(j)
                val addr = when (one) {
                    is String -> one
                    is JSONObject -> one.optString("address")
                    else -> ""
                }
                if (addr.startsWith("https://")) { found = addr; break@outer }
            }
        }
        found
    } catch (_: Throwable) { null }

    // Есть ли в теле ТОЛЬКО заглушка — чтобы сказать человеку прямо, что
    // подписка жива, но нас за своего не приняли.
    fun stubName(body: String): String? = try {
        val t = body.trimStart()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        val names = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("remarks") }
        names.firstOrNull { isStub(it) }
    } catch (_: Throwable) { null }

    private fun nodeFrom(cfg: JSONObject, name: String): Node? {
        val outs = cfg.optJSONArray("outbounds") ?: return null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            // freedom/blackhole — служебные, нода не в них.
            val proto = o.optString("protocol")
            val st = o.optJSONObject("streamSettings") ?: JSONObject()
            val net = st.optString("network", "tcp").lowercase()
            val sec = st.optString("security", "none").lowercase()

            val tls = st.optJSONObject("tlsSettings")
            val reality = st.optJSONObject("realitySettings")
            val fp = (reality ?: tls)?.optString("fingerprint") ?: ""
            val sni = (reality ?: tls)?.optString("serverName") ?: ""
            val alpn = tls?.optJSONArray("alpn")?.let { a ->
                (0 until a.length()).joinToString(",") { a.optString(it) }
            } ?: ""

            // Транспорт и его параметры.
            var path = ""; var host = ""; var svc = ""; var mode = ""; var extra = ""
            when (net) {
                "ws" -> st.optJSONObject("wsSettings")?.let {
                    path = it.optString("path")
                    host = it.optJSONObject("headers")?.optString("Host") ?: it.optString("host")
                }
                "grpc" -> st.optJSONObject("grpcSettings")?.let {
                    svc = it.optString("serviceName")
                }
                "xhttp", "splithttp" -> st.optJSONObject("xhttpSettings")
                    ?: st.optJSONObject("splithttpSettings")?.let { }
                "httpupgrade" -> st.optJSONObject("httpupgradeSettings")?.let {
                    path = it.optString("path"); host = it.optString("host")
                }
            }
            (st.optJSONObject("xhttpSettings") ?: st.optJSONObject("splithttpSettings"))?.let {
                path = it.optString("path"); host = it.optString("host")
                mode = it.optString("mode")
                extra = it.toString()
            }

            when (proto) {
                "vless", "vmess" -> {
                    val v = o.optJSONObject("settings")?.optJSONArray("vnext")
                        ?.optJSONObject(0) ?: continue
                    val user = v.optJSONArray("users")?.optJSONObject(0) ?: continue
                    return Node(
                        type = "VLESS",
                        name = name,
                        addr = v.optString("address"),
                        port = v.optInt("port", 443),
                        uuid = user.optString("id"),
                        encryption = user.optString("encryption").takeIf { it != "none" } ?: "",
                        flow = user.optString("flow"),
                        sni = sni,
                        pbk = reality?.optString("publicKey") ?: "",
                        sid = reality?.optString("shortId") ?: "",
                        transport = if (net == "splithttp") "xhttp" else net,
                        path = path, host = host, serviceName = svc,
                        alpn = alpn,
                        security = if (reality != null) "reality" else sec,
                        fp = fp, mode = mode, extra = extra,
                        insecure = tls?.optBoolean("allowInsecure") ?: false
                    )
                }
                "trojan" -> {
                    val s = o.optJSONObject("settings")?.optJSONArray("servers")
                        ?.optJSONObject(0) ?: continue
                    return Node(
                        type = "Trojan", name = name,
                        addr = s.optString("address"), port = s.optInt("port", 443),
                        password = s.optString("password"),
                        sni = sni, transport = net, path = path, host = host,
                        alpn = alpn, security = sec, fp = fp,
                        insecure = tls?.optBoolean("allowInsecure") ?: false
                    )
                }
                "shadowsocks" -> {
                    val s = o.optJSONObject("settings")?.optJSONArray("servers")
                        ?.optJSONObject(0) ?: continue
                    return Node(
                        type = "Shadowsocks", name = name,
                        addr = s.optString("address"), port = s.optInt("port", 443),
                        password = s.optString("password"),
                        method = s.optString("method")
                    )
                }
            }
        }
        return null
    }
}
