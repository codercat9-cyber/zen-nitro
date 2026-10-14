package com.zen.blaster.core

import org.json.JSONObject

// ============================================================================
//  Разрешение имён МИМО резолвера оператора.
//
//  Два повода не доверять системному DNS:
//   1. РКН и операторы подменяют ответы для «неправильных» доменов;
//   2. Go-ядра (Xray, wireguard-go) на Android часто вообще не находят
//      резолвер и лезут в [::1]:53 — там никого нет.
//
//  Поэтому спрашиваем DoH напрямую по IP, а системный резолвер оставляем
//  как запасной вариант.
//
//  ---------------------------------------------------------------------
//  ПОЧЕМУ ЗДЕСЬ ДВА ФОРМАТА ЗАПРОСА, А ГЛАВНЫЙ — БИНАРНЫЙ
//
//  Раньше спрашивали только JSON-API («?name=example.com&type=A»). Он удобен,
//  но НЕ СТАНДАРТ: его придумал Google, повторил Cloudflare, и на этом всё.
//  В списке запасных при этом стояли Quad9, AdGuard, Mullvad, ControlD и
//  Яндекс — по голым адресам, где JSON не отдаёт никто из них. То есть пять
//  из семи резолверов не могли ответить в принципе, сколько их ни спрашивай.
//
//  Наружу это вылезло на нодах партнёра с доменным адресом: в журнале
//  4 сентября 2026 двадцать три раза подряд «не удалось разрешить
//  cdn.thriller4ag.com», следом сотни строк от ядра «dial tcp: lookup ...» —
//  и нода не вставала, хотя у других клиентов на том же телефоне вставала.
//
//  Теперь основной формат — RFC 8484 (application/dns-message, бинарный
//  пакет в GET-параметре ?dns=). Его обязаны понимать все DoH-серверы, и
//  понимают. JSON остаётся вторым заходом для тех, кто вдруг ответит им.
//
//  ЗАПРОС ИДЁТ ПО ФИЗИЧЕСКОЙ СЕТИ, А НЕ «КУДА-НИБУДЬ»
//
//  Второй капкан: резолв нужен как раз в момент подъёма туннеля, когда
//  системный DNS уже показывает на 172.19.0.2 — на наш собственный туннель,
//  которого ещё нет. Сокет DoH теперь прибивается к настоящему каналу
//  (Wi-Fi или сотовому) через ConnectivityManager, поэтому чужой и свой
//  туннель ему больше не мешают.
// ============================================================================
object Resolver {

    // Куда идти за именами. Порядок важен: сначала выбранный человеком,
    // потом остальные.
    //
    // ТСПУ бьёт по известным адресам и SNI лидеров рынка — 1.1.1.1 и 8.8.8.8
    // подвисают на TLS-рукопожатии. Раньше они стояли первыми, и каждый
    // резолв начинался с двух заведомо мёртвых попыток по несколько секунд;
    // при подключении таких резолвов десятки. Малоизвестные резолверы ТСПУ
    // пока не трогает — с них и начинаем, а Cloudflare с Google оставляем
    // последней надеждой на случай, если однажды прикроют как раз мелкие.
    //
    // Все адреса — ЧИСЛОВЫЕ. Имя вроде dns.google само требует разрешения,
    // а разрешать его нечем: мы и есть резолвер. Этот круг тут когда-то был.
    private val FALLBACK = listOf(
        "https://9.9.9.9/dns-query",            // Quad9
        "https://94.140.14.14/dns-query",       // AdGuard
        // Mullvad, ControlD по 76.76.2.0 и dns0.eu здесь стояли до 3.5 и
        // были мертвы: у первых двух сертификат не на этот адрес, dns0 закрыт.
        // Каждый мёртвый в очереди — лишний таймаут при подъёме. Замена —
        // из проверки 14.10.2026 (см. DnsChoice).
        "https://185.222.222.222/dns-query",    // DNS.SB
        "https://76.76.2.11/p0",                // ControlD
        "https://130.59.31.248/dns-query",      // Switch
        "https://149.112.112.112/dns-query",    // Quad9, второй адрес
        "https://77.88.8.8/dns-query",          // Яндекс — жив внутри РФ
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
    )

    // Выбранный в настройках. Ставится из MainActivity при запуске и смене.
    @Volatile var preferred: String = ""

    // Приложение, чтобы дотянуться до ConnectivityManager. Ставится один раз
    // при старте; без него всё работает по-старому, просто без привязки сокета.
    @Volatile private var app: android.content.Context? = null
    fun attach(ctx: android.content.Context) { app = ctx.applicationContext }

    fun isIpLiteral(s: String): Boolean =
        s.isNotEmpty() && (s.all { it.isDigit() || it == '.' } || s.contains(':'))

    // Жив ли конкретный резолвер. Спрашиваем у него заведомо существующее имя:
    // ответ с адресом — значит работает, всё остальное — нет.
    fun probe(url: String): Boolean = ask(url, "example.com") != null

    // Когда DoH молчал в последний раз. Пока метка свежая, в него не лезем
    // вовсе и сразу идём к системному — иначе каждый запрос стоит секунды
    // впустую, а таких запросов при подключении десятки.
    @Volatile private var dohDeadUntil = 0L

    // -------------------------------------------------------------- кеш
    //
    // Один подъём ноды спрашивает одно и то же имя многократно: адрес сервера
    // для конфига, прогрев, проверка канала, переподключение. XHTTP вдобавок
    // открывает пачку параллельных запросов. Держим ответ в памяти пять минут,
    // иначе каждая мелочь стоит целого похода к DoH.
    private class Hit(val ip: String, val until: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Hit>()
    private const val TTL = 5L * 60 * 1000

    fun forget(host: String) { cache.remove(host) }
    fun forgetAll() { cache.clear() }

    // Сбрасываем «мёртвую метку» DoH. После смены сети ТСПУ мог смениться,
    // и DoH снова жив. Без этого клиент сидит на системном DNS целую минуту
    // впустую, пока метка не протухнет сама.
    fun resetDeadMark() { dohDeadUntil = 0L }

    // КЕШИРУЕМ ТОЛЬКО ТО, ЧЕМУ ВЕРИМ.
    //
    // Ответ шифрованного DoH подменить на проводе нельзя — его и храним пять
    // минут. А ответ системного резолвера оператора — ровно то, что ТСПУ и
    // подменяет: если DoH в этот момент молчал и мы взяли адрес у оператора,
    // в кеш мог лечь мусор, и все подъёмы следующих пяти минут стучались бы
    // в заглушку. Такой ответ используем один раз и не запоминаем: в
    // следующий раз спросим снова, и если DoH ожил — получим настоящий.
    //
    // Отсюда же ответ на «а не сбрасывать ли кеш перед каждым подъёмом»:
    // не нужно. Мусора в нём не бывает, а сброс сделал бы его бесполезным.
    fun resolve(host: String): String? {
        if (isIpLiteral(host)) return host

        cache[host]?.let { if (System.currentTimeMillis() < it.until) return it.ip }

        val (ip, trusted) = resolveUncached(host)
        if (ip != null && trusted) cache[host] = Hit(ip, System.currentTimeMillis() + TTL)
        return ip
    }

    // Второе значение — пришёл ли ответ по шифрованному каналу.
    private fun resolveUncached(host: String): Pair<String?, Boolean> {
        // Системный резолвер выбран явно — не выдумываем.
        if (preferred == "local") {
            system(host)?.let { return it to false }
            return parallelDoh(host) to true
        }

        // DoH недавно оказался мёртв — не тратим время, сразу к системному.
        // Он в такие дни как раз и работает: операторы режут DoH первым делом,
        // а свой резолвер оставляют, иначе у людей не откроются и разрешённые
        // сайты.
        if (System.currentTimeMillis() < dohDeadUntil) {
            system(host)?.let { return it to false }
            return parallelDoh(host) to true
        }

        parallelDoh(host)?.let { return it to true }

        // Ни один DoH не ответил — запоминаем это на минуту и живём системным.
        dohDeadUntil = System.currentTimeMillis() + 60_000
        return system(host) to false
    }

    // ВСЕ резолверы разом, а не по очереди.
    //
    // Раньше это был цикл: четыре адреса по четыре секунды таймаута. В день,
    // когда оператор прикрыл DoH, каждое имя стоило до шестнадцати секунд —
    // отсюда и «долгий коннект», который никак не объяснялся. Теперь спрашиваем
    // всех одновременно и берём первый ответ.
    private fun parallelDoh(host: String): String? {
        val order = (listOf(preferred).filter { it.isNotEmpty() && it != "local" } + FALLBACK)
            .distinct()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(order.size)
        return try {
            val tasks = order.map { base ->
                java.util.concurrent.Callable { ask(base, host) ?: throw java.io.IOException("нет") }
            }
            // invokeAny вернёт первый УСПЕШНЫЙ и снимет остальные.
            pool.invokeAny(tasks, 6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) { null } finally { pool.shutdownNow() }
    }

    // Спросить один резолвер. Сначала по стандарту, потом по-гугловски.
    private fun ask(base: String, host: String): String? = wire(base, host) ?: json(base, host)

    // ------------------------------------------------------- RFC 8484
    //
    // Обычный DNS-пакет, завёрнутый в HTTPS. Понимают все DoH-серверы, в
    // отличие от JSON. Пакет собираем и разбираем сами: тянуть ради этого
    // библиотеку в антицензурный клиент незачем, а формат простой.
    private fun wire(base: String, host: String): String? = try {
        val q = buildQuery(host)
        val b64 = android.util.Base64.encodeToString(
            q, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
               android.util.Base64.NO_WRAP)
        val req = okhttp3.Request.Builder()
            .url("$base?dns=$b64")
            .header("Accept", "application/dns-message")
            .build()
        val bytes = http().newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
        bytes?.let { parseAnswer(it) }
    } catch (_: Throwable) { null }

    // Запрос типа A, один вопрос, рекурсия разрешена.
    private fun buildQuery(host: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Идентификатор нулевой: соответствие ответа запросу обеспечивает сам
        // HTTPS, а постоянный ноль вдобавок не даёт отличать нас по трафику.
        out.write(0); out.write(0)
        out.write(0x01); out.write(0x00)      // флаги: RD
        out.write(0); out.write(1)            // вопросов: 1
        out.write(0); out.write(0)            // ответов: 0
        out.write(0); out.write(0)            // authority: 0
        out.write(0); out.write(0)            // additional: 0
        for (label in host.trim('.').split('.')) {
            val raw = label.toByteArray(Charsets.US_ASCII)
            if (raw.isEmpty() || raw.size > 63) continue
            out.write(raw.size); out.write(raw)
        }
        out.write(0)                          // конец имени
        out.write(0); out.write(1)            // тип A
        out.write(0); out.write(1)            // класс IN
        return out.toByteArray()
    }

    // Достать первый адрес типа A. Имена в ответе бывают сжаты указателями
    // (0xC0), поэтому просто перешагиваем имя, а не разбираем его.
    private fun parseAnswer(b: ByteArray): String? {
        if (b.size < 12) return null
        val qd = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val an = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        if (an <= 0) return null
        var p = 12
        repeat(qd) {
            p = skipName(b, p)
            p += 4                            // тип + класс
        }
        repeat(an) {
            if (p + 10 > b.size) return null
            p = skipName(b, p)
            if (p + 10 > b.size) return null
            val type = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            val len = ((b[p + 8].toInt() and 0xFF) shl 8) or (b[p + 9].toInt() and 0xFF)
            p += 10
            if (type == 1 && len == 4 && p + 4 <= b.size) {
                return "${b[p].toInt() and 0xFF}.${b[p + 1].toInt() and 0xFF}." +
                       "${b[p + 2].toInt() and 0xFF}.${b[p + 3].toInt() and 0xFF}"
            }
            p += len                          // не A (CNAME и прочее) — дальше
        }
        return null
    }

    private fun skipName(b: ByteArray, from: Int): Int {
        var p = from
        while (p < b.size) {
            val len = b[p].toInt() and 0xFF
            when {
                len == 0 -> return p + 1
                len and 0xC0 == 0xC0 -> return p + 2      // указатель, длиной два байта
                else -> p += 1 + len
            }
        }
        return p
    }

    // ------------------------------------------------------- JSON-API
    private fun json(base: String, host: String): String? = try {
        val req = okhttp3.Request.Builder()
            .url("$base?name=$host&type=A")
            .header("Accept", "application/dns-json").build()
        val body = http().newCall(req).execute().use { it.body?.string().orEmpty() }
        val ans = JSONObject(body).optJSONArray("Answer")
        var out: String? = null
        if (ans != null) for (i in 0 until ans.length()) {
            val a = ans.getJSONObject(i)
            if (a.optInt("type") == 1) { out = a.optString("data"); break }
        }
        out?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    // --------------------------------------------------- физическая сеть
    //
    // Клиент пересобирается, когда меняется сеть под нами: на новом канале
    // прежние сокеты мертвы, а имя надо разрешить прямо сейчас.
    @Volatile private var boundTo: android.net.Network? = null
    @Volatile private var client: okhttp3.OkHttpClient? = null

    private fun http(): okhttp3.OkHttpClient {
        val net = physical()
        client?.let { if (net == boundTo) return it }
        val b = okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
        // ПИННИНГА СЕРТИФИКАТОВ ЗДЕСЬ НЕТ — И ЭТО РЕШЕНИЕ, А НЕ ЗАБЫВЧИВОСТЬ.
        //
        // 30 сентября 2026 его сюда добавили: зашили отпечатки корневых
        // сертификатов пяти резолверов. Проверка живыми соединениями на
        // следующий день показала, что верен ОДИН из пяти. Quad9 уже сидит
        // на DigiCert G3, а не G2; AdGuard — на ZeroSSL; Cloudflare — на
        // SSL.com; Mullvad — на новом корне Let's Encrypt. Каждый запрос к ним
        // падал с «pinning failure», резолвер считался мёртвым, и DNS тихо
        // держался на запасных — причём Quad9 у людей стоит по умолчанию.
        //
        // Это не опечатка, это природа пиннинга: провайдеры меняют цепочку
        // когда хотят, и каждая смена кладёт DNS всем до следующего
        // обновления приложения. А защищает он от угрозы, которой в наших
        // условиях нет: ТСПУ не подменяет сертификат на DoH, он рвёт
        // соединение. Подделать сертификат 9.9.9.9 без доверенного корня
        // нельзя, а корни Минцифры в системное хранилище Android сами не
        // ставятся. Системной проверки цепочки здесь достаточно.
        // Прибиваем сокет к настоящему каналу. Без этого запрос уходит по
        // «активной сети», а в момент подъёма туннеля активная сеть — это
        // наш же полумёртвый tun с DNS на 172.19.0.2.
        net?.let { n -> runCatching { b.socketFactory(n.socketFactory) } }
        val c = b.build()
        boundTo = net; client = c
        return c
    }

    // Первая сеть, которая даёт интернет и НЕ является чьим-либо туннелем.
    //
    // Если живы сразу Wi-Fi и сотовая (Android держит сотовую в фоне, пока
    // Wi-Fi не подтвердится), берём ту, по которой телефон реально ходит:
    // проверенную системой и сначала Wi-Fi или провод. Раньше бралась первая
    // попавшаяся в списке системы — порядок там не обещан, и шифрованный DNS
    // мог уйти по сотовой при живом Wi-Fi, а смена «первой в списке» выглядела
    // как пересадка на другую сеть.
    fun physical(): android.net.Network? = try {
        val ctx = app ?: return null
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            var score = 0
            if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) score += 2
            n to score
        }.maxByOrNull { it.second }?.first
    } catch (_: Throwable) { null }

    // Спросить оператора — но не верить заглушке.
    //
    // ТСПУ отвечает на открытые запросы раньше настоящего сервера, и вместо
    // адреса подсовывает служебный: 127.0.0.1, 0.0.0.0, 10.10.34.x у НСДИ,
    // внутренние адреса оператора. Публичный сервер ноды таким быть не может
    // НИКОГДА, так что такой ответ — это подмена, а не адрес. Раньше мы брали
    // его как есть, и при «Системном» резолвере подъём стучался в заглушку,
    // а выглядело это как мёртвая нода.
    //
    // Отброшенный ответ = «оператор не знает». Дальше пойдёт шифрованный DoH.
    private fun system(host: String): String? = try {
        val ip = java.net.InetAddress.getAllByName(host)
            .firstOrNull { it is java.net.Inet4Address }?.hostAddress
        if (ip != null && isServiceAddress(ip)) {
            lastSpoof = "$host → $ip"
            null
        } else ip
    } catch (_: Throwable) { null }

    // Последняя замеченная подмена — для журнала. Пусто, если не было.
    @Volatile var lastSpoof: String = ""

    // Служебные и частные адреса: честный резолвер на публичное имя их не
    // отдаёт. Ровно такими и бывают заглушки.
    fun isServiceAddress(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 0 || p[0] == 127 || p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 100 && p[1] in 64..127) ||
            (p[0] == 169 && p[1] == 254)
    }

    // Разрешить без кеша и без запоминания — для проверок, которым нужен
    // честный свежий ответ, а не то, что лежит в памяти.
    fun resolveFresh(host: String): String? =
        if (isIpLiteral(host)) host else resolveUncached(host).first
}
