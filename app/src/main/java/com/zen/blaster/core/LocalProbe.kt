package com.zen.blaster.core

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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
    fun through(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
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

    // ПУСКАЮТ ЛИ НЕЙРОСЕТИ С ЭТОГО ВЫХОДА.
    //
    // Человек ставит на ноду метку «🤖 AI» — значит, должен знать, что с неё
    // правда открываются ChatGPT и Claude, а не гадать по флагу в имени.
    // 04.11: «🇺🇸 Каскад NoV-US» выходит в Новосибирске, а «🇺🇸 SPb-US» —
    // то в Шарлотте, то на Кипре (выход через Cloudflare WARP). Спрашиваем
    // сами службы, без ключа: «нет ключа» (401) — регион пускают, «страна не
    // поддерживается» (403) — нет. Плюс страна, какой нас видит Cloudflare
    // перед chatgpt.com, — по ней такие службы и решают.
    class AiAccess(val openai: Boolean?, val anthropic: Boolean?, val loc: String) {
        val good: Boolean get() = openai == true && anthropic == true
        fun line(): String =
            listOf("ChatGPT" to openai, "Claude" to anthropic).joinToString(" · ") { (n, v) ->
                n + when (v) { true -> " ✓"; false -> " ✗ регион"; null -> " ?" }
            } + (if (loc.isNotEmpty()) " · страна для них: $loc" else "")
    }

    fun ai(): AiAccess {
        val c = through().newBuilder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        fun call(req: okhttp3.Request): Pair<Int, String>? = try {
            c.newCall(req).execute().use { it.code to it.body?.string().orEmpty().take(800) }
        } catch (_: Throwable) { null }
        val ua = "Mozilla/5.0 (Linux; Android 14)"
        val json = "application/json".toMediaTypeOrNull()
        val res = arrayOfNulls<Pair<Int, String>>(3)
        val reqs = listOf(
            okhttp3.Request.Builder().url("https://api.openai.com/v1/models")
                .header("User-Agent", ua).build(),
            okhttp3.Request.Builder().url("https://api.anthropic.com/v1/messages")
                .header("User-Agent", ua).header("anthropic-version", "2023-06-01")
                .post("{}".toRequestBody(json)).build(),
            okhttp3.Request.Builder().url("https://chatgpt.com/cdn-cgi/trace")
                .header("User-Agent", ua).build()
        )
        val jobs = reqs.mapIndexed { i, r -> Thread { res[i] = call(r) }.apply { isDaemon = true; start() } }
        jobs.forEach { it.join(16_000) }
        val openai = res[0]?.let { (code, body) -> when {
            code == 401 -> true
            code == 403 && body.contains("unsupported_country") -> false
            else -> null } }
        val anthropic = res[1]?.let { (code, body) -> when {
            code == 401 -> true
            code == 403 && (body.contains("not allowed") || body.contains("forbidden")) -> false
            else -> null } }
        val loc = res[2]?.second?.let { Regex("(?m)^loc=(\\w+)").find(it)?.groupValues?.get(1) }.orEmpty()
        return AiAccess(openai, anthropic, loc)
    }

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
