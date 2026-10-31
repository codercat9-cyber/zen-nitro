package com.zen.blaster.core

// ============================================================================
//  Как приложению сходить наружу ИЗНУТРИ собственного туннеля.
//
//  Само приложение из туннеля исключено — иначе ядро заворачивало бы свой же
//  трафик в туннель, который от него и зависит. Побочный итог: любой запрос,
//  отправленный приложением напрямую, уходит мимо VPN и показывает настоящий
//  адрес. Поэтому «узнать, где мы вышли» нельзя обычным запросом — он честно
//  ответит домашним IP.
//
//  Решение: ядро всегда держит локальный вход на 127.0.0.1, и приложение
//  ходит через него как через обычный прокси. Тогда путь ровно тот же, каким
//  идёт трафик телефона, и ответ настоящий.
// ============================================================================
object LocalProbe {

    private const val FIRST = 2081
    private const val LAST = 2101

    @Volatile
    var port: Int = FIRST
        private set

    @Volatile var user: String = ""; private set
    @Volatile var pass: String = ""; private set

    fun claim(): Int {
        user = "z" + ClashApi.token(8); pass = ClashApi.token(20)
        for (p in FIRST..LAST) if (ClashApi.free(p)) { port = p; return p }
        port = FIRST
        return FIRST
    }

    // Клиент, ходящий ЧЕРЕЗ туннель.
    // Через HTTP-прокси того же входа, а не SOCKS: у SOCKS в Java пароль
    // передать толком нечем, а HTTP-прокси берёт его заголовком. OkHttp
    // спрашивает proxyAuthenticator заранее, ещё до CONNECT для https.
    private fun through(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", port)))
        .proxyAuthenticator { _, resp ->
            if (user.isEmpty() || resp.request.header("Proxy-Authorization") != null) null
            else resp.request.newBuilder()
                .header("Proxy-Authorization", okhttp3.Credentials.basic(user, pass)).build()
        }
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Клиент, идущий НАПРЯМУЮ, мимо туннеля. Нужен для сравнения: именно так
    // выглядит телефон, если защита отвалится.
    // ОБЪЁМ ЧЕРЕЗ ТУННЕЛЬ: сколько байт пройдёт, прежде чем встанет.
    //
    // Журнал 31.10, «Белые списки #3»: маленькие проверки (204) проходят,
    // а YouTube молчит; через три минуты та же нода работает. Отличить
    // заморозку потока на линии (почерк поведенческой блокировки ТСПУ:
    // первые килобайты идут, дальше тишина) от глухого выхода сервера
    // (не приходит ничего) можно только по этой цифре.
    fun volume(): Pair<Long, Boolean> {
        val c = through().newBuilder()
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        var total = 0L
        return try {
            val req = okhttp3.Request.Builder().url("https://speed.cloudflare.com/__down?bytes=262144").build()
            c.newCall(req).execute().use { r ->
                val src = r.body?.source() ?: return 0L to false
                val sink = okio.Buffer()
                while (true) { val n = src.read(sink, 8192); if (n < 0) break; total += n; sink.clear() }
            }
            total to false
        } catch (_: java.io.InterruptedIOException) { total to true
        } catch (_: Exception) { total to (total > 0) }
    }

    private fun direct(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class Where(val ip: String, val country: String, val city: String, val isp: String) {
        fun short(): String = when {
            country.isEmpty() -> ip
            city.isEmpty() -> country
            else -> "$country, $city"
        }
    }

    // Спрашиваем у нескольких служб по очереди: одна может лежать или быть
    // заблокированной, и делать по ней вывод о туннеле нельзя.
    private val ASK = listOf(
        "http://ip-api.com/json/?fields=query,country,city,isp",
        "https://ipwho.is/",
        "https://ifconfig.co/json"
    )

    fun where(viaTunnel: Boolean): Where? {
        val http = if (viaTunnel) through() else direct()
        for (url in ASK) {
            try {
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)").build()
                val body = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use ""
                    r.body?.string().orEmpty()
                }
                if (body.isBlank()) continue
                val o = org.json.JSONObject(body)
                val ip = o.optString("query", o.optString("ip"))
                if (ip.isEmpty()) continue
                return Where(
                    ip = ip,
                    country = o.optString("country", o.optString("country_name")),
                    city = o.optString("city"),
                    isp = o.optString("isp", o.optString("connection.isp",
                        o.optString("asn_org", o.optString("org"))))
                )
            } catch (_: Throwable) { /* пробуем следующую */ }
        }
        return null
    }
}
