package com.zen.blaster.core

import com.zen.blaster.model.Node
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

// ============================================================================
//  ПРОВЕРКА ЛИНИИ — где именно умирает трафик. По фактам, а не по догадкам.
//
//  Зачем. «Канал не отвечает» и «не отозвался за 20 с» ничего не говорят о
//  причине. Сервер может быть жив (оператор Триллера проверил: «с нашей
//  стороны всё гуд»), а трафик гибнет по дороге — и в каком месте, клиент
//  не знал. Отсюда и бред вида «сегодня UDP не отвечает».
//
//  Что делаем. Мимо туннеля, по настоящей сети оператора, проходим путь до
//  сервера по ступеням и на каждой пишем в журнал, что ушло и что пришло, с
//  временем и первыми байтами в hex:
//
//    1. адрес     — нашёлся ли и за сколько;
//    2. TCP       — соединение: прошло / сброшено (RST) / утонуло в тишине;
//    3. TLS       — наше приветствие с именем ноды: что пришло в ответ —
//                   ServerHello (16 03 03 …), тревога (15 …), сброс, тишина;
//    4. объём     — сколько килобайт прошло, прежде чем канал встал. Обрыв
//                   на ~16–20 КБ — почерк поведенческой блокировки ТСПУ:
//                   «подключено, а данные не идут»;
//    5. контроль  — то же до разрешённого сайта (ya.ru). Не прошёл и он —
//                   значит лежит или глушится сама сеть, и честно так и
//                   пишем, а не валим на сервер.
//
//  Приветствие TLS собираем сами: так в журнале ровно те байты, что ушли в
//  провод. Отпечаток у него не браузерный — это проверка пути (пропускают ли
//  такое имя к такому адресу), а не подмена ядра.
// ============================================================================
object LineProbe {

    // НА КАКОМ УРОВНЕ РЕЖУТ (permanent packet drop бывает и с TCP, не только
    // с UDP — ТСПУ молча выбрасывает пакеты потока, порта, адреса или имени):
    //   FLOW   — линия чистая, свежие соединения проходят: дропнут конкретный
    //            поток → быстрое переподключение, дробление на короткие
    //            соединения (XHTTP, ротация xmux);
    //   VOLUME — встало на десятках КБ: заморозка потока после порога;
    //   PORT   — этот порт молчит, соседний на том же IP отвечает;
    //   IP     — адрес молчит на всех портах: «сгорел» для этой сети;
    //   SNI    — с именем ноды тишина, с нейтральным именем тот же адрес отвечает;
    //   TLS    — TLS к адресу режут целиком, с любым именем;
    //   NET    — молчит и разрешённый сайт: лежит или глушится сама сеть;
    //   DNS    — адрес не находится; PARTIAL — TCP и TLS прошли, объём не измерен.
    enum class Kind { FLOW, VOLUME, PORT, IP, SNI, TLS, NET, DNS, PARTIAL }

    class Report(val lines: List<String>, val verdict: String, val kind: Kind = Kind.FLOW)

    private fun sock(): Socket = Resolver.physical()?.socketFactory?.createSocket() ?: Socket()

    private fun tcpOk(ip: String, port: Int, ms: Int): Boolean = try {
        sock().use { it.connect(InetSocketAddress(ip, port), ms) }; true
    } catch (_: Exception) { false }

    fun run(n: Node): Report = runRaw(n.addr, n.port,
        n.sni.ifEmpty { n.host }.ifEmpty { if (Resolver.isIpLiteral(n.addr)) "" else n.addr },
        udp = n.quic(), tls = n.security != "none")

    // То же по голым данным — для сторожа канала, у которого есть только
    // конфиг, а не нода.
    fun runRaw(host: String, nodePort: Int, sni: String, udp: Boolean, tls: Boolean): Report {
        val out = mutableListOf<String>()
        // У QUIC-ноды сам порт живёт по UDP и чужим пакетам не отвечает —
        // путь до хоста меряем по TCP 443 (см. Subscriptions.sideProbe).
        val port = if (udp) 443 else nodePort
        fun net(kind: Kind, v: String): Report =
            control(out)?.let { Report(out, it, Kind.NET) } ?: Report(out, v, kind)

        // 1. Адрес.
        var t = System.currentTimeMillis()
        val ip = Resolver.resolve(host)
        val dnsMs = System.currentTimeMillis() - t
        if (ip == null) {
            out += "1 адрес: $host не нашёлся за $dnsMs мс"
            return net(Kind.DNS, "Адрес сервера не находится — ни шифрованный DNS, ни DNS оператора его не знают")
        }
        out += "1 адрес: $host → $ip за $dnsMs мс"

        // 2. TCP.
        t = System.currentTimeMillis()
        val tcp = try {
            sock().use { it.connect(InetSocketAddress(ip, port), 5000) }
            null
        } catch (e: Exception) { e }
        val tcpMs = System.currentTimeMillis() - t
        if (tcp != null) {
            val rst = tcp is java.net.ConnectException
            out += "2 TCP $ip:$port: " + (if (rst) "СБРОС за $tcpMs мс (${tcp.message})" else "тишина $tcpMs мс")
            // Порт или весь адрес? Стучимся в соседние порты того же IP.
            val side = listOf(443, 80, 8443).filter { it != port }.take(2)
            val alive = side.firstOrNull { tcpOk(ip, it, 3000) }
            out += "2б соседние порты ${side.joinToString("/")}: " + (alive?.let { "$it отвечает" } ?: "молчат")
            return when {
                alive != null -> net(Kind.PORT, "Режут порт $port, сам адрес живой (порт $alive отвечает) — " +
                    "поможет другой порт этой ноды")
                rst -> net(Kind.PORT, "До сервера соединение сбрасывают сразу — порт закрыт или вмешательство на линии")
                else -> net(Kind.IP, "Адрес $ip молчит на всех портах — сгорел для этой сети (дроп по адресу). " +
                    "Нужен другой вход: другая нода или другой IP")
            }
        }
        out += "2 TCP $ip:$port: есть за $tcpMs мс"
        if (sni.isEmpty() || !tls) {
            return Report(out, if (udp) "Хост доступен по TCP; сама UDP-часть снаружи не проверяется — " +
                "если туннель не встаёт, скорее всего режут UDP" else "Линия до сервера чистая (TLS у ноды нет)")
        }

        // 3. Приветствие TLS. Не прошло — то же к тому же адресу с нейтральным
        // именем: так видно, режут ли по имени (SNI) или TLS к адресу целиком.
        val (helloOk, helloVerdict) = hello(ip, port, sni, out, "3 TLS")
        if (!helloOk) {
            val (neutralOk, _) = hello(ip, port, "www.google.com", out, "3б TLS нейтр.")
            return if (neutralOk) net(Kind.SNI, "Режут по имени $sni: с нейтральным именем этот же адрес " +
                    "отвечает — нужно другое имя маскировки (SNI)")
                else net(Kind.TLS, helloVerdict + ". С нейтральным именем — тоже: режут TLS к этому адресу целиком")
        }

        // 4. Объём.
        val (kb, stalled) = volume(ip, port, sni)
        // 0 КБ без остановки — объём не измерен (пустой ответ, чужой
        // сертификат), а не «прошёл». Журнал 31.10: так и было написано
        // «объём прошли» при нуле.
        val measured = stalled || kb > 0
        out += "4 объём: " + when {
            stalled -> "встал на $kb КБ"
            measured -> "прошло $kb КБ без остановки"
            else -> "не измерить — сервер не отдал данных по этому имени"
        }
        return when {
            stalled && kb in 8..40 -> Report(out,
                "Обрыв после $kb КБ — почерк поведенческой блокировки ТСПУ на этот адрес: " +
                "соединение есть, данные дальше не идут. Сервер тут ни при чём — нужен другой вход " +
                "(IP/подсеть) или другое имя (SNI)", Kind.VOLUME)
            stalled -> Report(out, "Данные встали на $kb КБ — линия до сервера нестабильна", Kind.VOLUME)
            udp -> Report(out, "Путь до хоста чистый по TCP, TLS и объёму. Если UDP-нода не встаёт — режут именно UDP")
            !measured -> Report(out, "До сервера доходят TCP и TLS-приветствие — линию не режут. Объём измерить не удалось. " +
                "Если туннель встаёт, а сайты не открываются, — дело в выходе сервера", Kind.PARTIAL)
            else -> Report(out, "Линия до сервера чистая: адрес, TCP, TLS и объём прошли, свежие соединения " +
                "идут. Если туннель залипал — дропнули сам поток: помогает быстрое переподключение", Kind.FLOW)
        }
    }

    // Контроль — разрешённый сайт. Возвращает вердикт, если сеть мертва
    // сама по себе, иначе null (дело в пути до нашего сервера).
    private fun control(out: MutableList<String>): String? {
        val ip = Resolver.resolve("ya.ru") ?: run {
            out += "5 контроль: ya.ru не нашёлся"
            return "Сеть сама не работает: даже разрешённый сайт не находится. Проблема не в сервере — " +
                "плохая связь или сеть глушат"
        }
        val (ok, _) = hello(ip, 443, "ya.ru", out, "5 контроль")
        return if (ok) null
        else "Сеть сама не работает: разрешённый сайт тоже не отвечает. Проблема не в сервере — " +
            "плохая связь или сеть глушат"
    }

    // Наше приветствие и первые байты ответа — в журнал hex-дампом.
    private fun hello(ip: String, port: Int, sni: String, out: MutableList<String>, tag: String): Pair<Boolean, String> {
        val ch = clientHello(sni)
        val t = System.currentTimeMillis()
        return try {
            sock().use { s ->
                s.connect(InetSocketAddress(ip, port), 5000)
                s.soTimeout = 6000
                s.getOutputStream().apply { write(ch); flush() }
                val buf = ByteArray(64)
                val got = try { s.getInputStream().read(buf) } catch (e: java.net.SocketTimeoutException) { -2 }
                val ms = System.currentTimeMillis() - t
                out += "$tag → ${ch.size} Б SNI=$sni: ${hex(ch, 11)}…"
                when {
                    got == -2 -> {
                        out += "$tag ← тишина ${ms} мс"
                        false to "Приветствие TLS с именем $sni до $ip глохнет: ответа нет — " +
                            "на линии режут по имени (SNI) или отпечатку"
                    }
                    got < 0 -> {
                        out += "$tag ← соединение закрыто за $ms мс"
                        false to "На приветствие TLS с именем $sni соединение закрывают — вмешательство на линии"
                    }
                    buf[0] == 0x16.toByte() -> {
                        out += "$tag ← ServerHello за $ms мс: ${hex(buf, got.coerceAtMost(16))}"
                        true to ""
                    }
                    buf[0] == 0x15.toByte() -> {
                        out += "$tag ← тревога TLS за $ms мс: ${hex(buf, got.coerceAtMost(7))}"
                        // Сервер ответил сам — линия пропускает; ему не
                        // понравилось наше приветствие, это не блокировка.
                        true to ""
                    }
                    else -> {
                        out += "$tag ← чужой ответ за $ms мс: ${hex(buf, got.coerceAtMost(16))}"
                        false to "Вместо TLS пришёл чужой ответ — похоже на заглушку оператора"
                    }
                }
            }
        } catch (e: java.net.SocketException) {
            val ms = System.currentTimeMillis() - t
            out += "$tag → SNI=$sni: сброс за $ms мс (${e.message})"
            false to "На приветствие TLS с именем $sni соединение сбрасывают (RST) — режут по имени"
        } catch (e: Exception) {
            out += "$tag → SNI=$sni: ${e.javaClass.simpleName} ${e.message}"
            false to "Приветствие TLS не прошло: ${e.message}"
        }
    }

    // Сколько килобайт пройдёт, прежде чем встанет. Несколько запросов по
    // одному соединению — у многих сайтов главная страница меньше 16 КБ.
    private fun volume(ip: String, port: Int, sni: String): Pair<Int, Boolean> {
        val sec = java.util.concurrent.TimeUnit.SECONDS
        val http = okhttp3.OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName(ip))
            })
            .socketFactory(Resolver.physical()?.socketFactory ?: javax.net.SocketFactory.getDefault())
            .proxy(java.net.Proxy.NO_PROXY)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(5, sec).readTimeout(5, sec).callTimeout(30, sec)
            .build()
        var total = 0L
        try {
            repeat(8) {
                val req = okhttp3.Request.Builder().url("https://$sni:$port/")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
                    .build()
                http.newCall(req).execute().use { r ->
                    val src = r.body?.source() ?: return@use
                    val sink = okio.Buffer()
                    while (total < 64 * 1024) {
                        val n = src.read(sink, 8192)
                        if (n < 0) break
                        total += n; sink.clear()
                    }
                }
                if (total >= 64 * 1024) return (total / 1024).toInt() to false
            }
        } catch (_: java.io.InterruptedIOException) {
            return (total / 1024).toInt() to true          // встало посреди
        } catch (_: javax.net.ssl.SSLException) {
            // Сертификат не на это имя — это не блокировка, объём не мерим.
            return (total / 1024).toInt() to false
        } catch (_: Exception) {
            return (total / 1024).toInt() to (total > 0)
        }
        return (total / 1024).toInt() to false
    }

    private fun hex(b: ByteArray, n: Int) =
        b.take(n.coerceAtLeast(0)).joinToString(" ") { "%02x".format(it) }

    // Минимальное приветствие TLS 1.3: имя, группы, подписи, ALPN, версии,
    // ключ x25519 (32 случайных байта — для проверки пути этого достаточно).
    private fun clientHello(sni: String): ByteArray {
        val rnd = java.security.SecureRandom()
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        val ext = ByteArrayOutputStream()
        fun extension(type: Int, body: ByteArray) { ext.write(u16(type)); ext.write(u16(body.size)); ext.write(body) }
        val host = sni.toByteArray()
        extension(0x0000, u16(host.size + 3) + byteArrayOf(0) + u16(host.size) + host)
        extension(0x000a, u16(4) + u16(0x001d) + u16(0x0017))
        val sig = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)
        extension(0x000d, u16(sig.size * 2) + sig.flatMap { u16(it).toList() }.toByteArray())
        val alpn = byteArrayOf(2) + "h2".toByteArray() + byteArrayOf(8) + "http/1.1".toByteArray()
        extension(0x0010, u16(alpn.size) + alpn)
        extension(0x002b, byteArrayOf(4) + u16(0x0304) + u16(0x0303))
        extension(0x002d, byteArrayOf(1, 1))
        extension(0x0033, u16(36) + u16(0x001d) + u16(32) + random(32))
        val ciphers = intArrayOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8)
        val body = ByteArrayOutputStream().apply {
            write(u16(0x0303)); write(random(32))
            write(32); write(random(32))
            write(u16(ciphers.size * 2)); ciphers.forEach { write(u16(it)) }
            write(1); write(0)
            val e = ext.toByteArray(); write(u16(e.size)); write(e)
        }.toByteArray()
        val hs = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(0x16, 0x03, 0x01) + u16(hs.size) + hs
    }
}
