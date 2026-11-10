package com.zen.blaster.core

import com.zen.blaster.model.Node
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

// ============================================================================
//  ПРОВЕРКА ЛИНИИ — где именно умирает трафик. По байтам, а не по догадкам.
//
//  Зачем. «Канал не отвечает» и «не отозвался за 20 с» ничего не говорят о
//  причине. Сервер может быть жив (оператор Триллера проверил: «с нашей
//  стороны всё гуд»), а трафик гибнет по дороге — и в каком месте, клиент
//  не знал. Отсюда и бред вида «сегодня UDP не отвечает».
//
//  Что делаем. Мимо туннеля, по настоящей сети оператора, проходим путь до
//  сервера по ступеням и на каждой пишем в журнал, что ушло и что пришло —
//  дампом по 16 байт (hex и ASCII) и расшифровкой этих байтов:
//
//    1. адрес     — нашёлся ли и за сколько;
//    2. TCP       — SYN: ответ / сброс (RST) / тишина, с кодом ошибки сокета;
//       UDP       — у QUIC-ноды (Hysteria2, TUIC, h3) спрашиваем сам порт
//                   ноды «какую версию QUIC ты знаешь?» (версия 0x1a2a3a4a,
//                   RFC 9000 6). Живой сервер обязан ответить Version
//                   Negotiation с нашим же ID — это доказательство, что UDP
//                   доходит и отвечает именно сервер. Salamander оборачиваем
//                   её паролем, как ядро. Молчит — тот же вопрос к ya.ru;
//    3. TLS       — наше приветствие с именем ноды: ServerHello (16 03 03 …),
//                   тревога (15 03 03 00 02 02 28 = handshake_failure), FIN,
//                   RST, тишина, заглушка «HTTP/1.1 302 → warning…»;
//       HTTP      — у ноды без TLS: что сервер скажет на GET;
//    4. объём     — сколько килобайт прошло, прежде чем канал встал. Обрыв
//                   на ~16–20 КБ — почерк поведенческой блокировки ТСПУ:
//                   «подключено, а данные не идут»;
//    5. контроль  — то же до разрешённого сайта (ya.ru). Не прошёл и он —
//                   значит лежит или глушится сама сеть, и честно так и
//                   пишем, а не валим на сервер;
//    6. ядро      — последние жалобы Xray/sing-box и счёт дозвонов (CoreTrail).
//
//  В конце — одна машинная строка «#DIAG ключ=значение …»: по ней журнал
//  разбирается grep-ом, без чтения глазами.
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
    //   UDP    — QUIC к серверу молчит, а ya.ru по QUIC отвечает;
    //   NET    — молчит и разрешённый сайт: лежит или глушится сама сеть;
    //   DNS    — адрес не находится; PARTIAL — TCP и TLS прошли, объём не измерен;
    //   SELF   — сама проверка не вышла мимо туннеля: выводов нет, ноду не трогать.
    enum class Kind { FLOW, VOLUME, PORT, IP, SNI, TLS, UDP, NET, DNS, PARTIAL, SELF }

    class Report(val lines: List<String>, val verdict: String, val kind: Kind = Kind.FLOW, val diag: String = "")

    private fun sock(): Socket = Bypass.socket()

    // Машинная строка: ключи в порядке ступеней.
    private class Diag {
        val kv = LinkedHashMap<String, String>()
        operator fun set(k: String, v: String) { kv[k] = v.replace(' ', '_') }
        fun line(kind: Kind) = "#DIAG " + kv.entries.joinToString(" ") { "${it.key}=${it.value}" } + " kind=$kind"
    }

    private fun tcpTry(ip: String, port: Int, ms: Int): Pair<Long, Throwable?> {
        val t = System.currentTimeMillis()
        val e = try { sock().use { it.connect(InetSocketAddress(ip, port), ms) }; null } catch (e: Exception) { e }
        return (System.currentTimeMillis() - t) to e
    }

    fun run(n: Node, trail: List<String> = CoreTrail.lines(), trailCode: String = CoreTrail.code()): Report =
        runRaw(n.addr, n.port,
            n.sni.ifEmpty { n.host }.ifEmpty { if (Resolver.isIpLiteral(n.addr)) "" else n.addr },
            udp = n.quic(), tls = n.security != "none", reality = n.security == "reality",
            obfsPw = if (n.obfs.equals("salamander", true)) n.obfsPw else "",
            trail = trail, trailCode = trailCode)

    private fun selfFail(out: MutableList<String>) = Report(out,
        "Проверка не смогла выйти мимо туннеля (EPERM) — про линию сказать нечего, ноду не трогаю", Kind.SELF)

    // То же по голым данным — для сторожа канала, у которого есть только
    // конфиг, а не нода. След ядра снимает вызывающий: к концу проверки
    // переподключение его уже обнулит.
    fun runRaw(host: String, nodePort: Int, sni: String, udp: Boolean, tls: Boolean,
               reality: Boolean = false, obfsPw: String = "",
               trail: List<String> = CoreTrail.lines(), trailCode: String = CoreTrail.code()): Report {
        val out = mutableListOf<String>()
        val d = Diag()
        d["ep"] = "$host:$nodePort"
        d["t"] = if (udp) "udp" else "tcp"
        d["sec"] = (if (reality) "reality" else if (tls) "tls" else "none") + if (obfsPw.isNotEmpty()) "+salamander" else ""
        fun finish(r: Report): Report {
            if (trail.isNotEmpty()) { out += "6 ядро:"; trail.forEach { out += "   $it" } }
            d["core"] = trailCode
            // ЛИНИЯ ЧИСТАЯ, А ЯДРО НЕ ДОЗВАНИВАЕТСЯ — значит, дело не в линии.
            // 09.11, «Каскад Finland»: TCP 45 мс, ServerHello маски 160 мс, а
            // Xray 7 раз подряд не открыл ни одного соединения: сервер не
            // принимает рукопожатие с ключом. Это ответ, а не «линию не режут».
            val dial = Regex("dial=(\\d+)/open=(\\d+)").find(trailCode)
            val dials = dial?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val opened = dial?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val verdict = if (r.kind in setOf(Kind.FLOW, Kind.PARTIAL) && dials >= 3 && opened == 0)
                "Линия до сервера чистая, а ядро $dials раз подряд не открыло ни одного соединения — сервер " +
                "не принимает рукопожатие (ключ Reality/пароль/UUID, перегрузка или сервер завис). Дело на " +
                "стороне сервера или конфига, не оператора — нужна другая нода"
            else r.verdict
            val line = d.line(r.kind)
            out += line
            return Report(out, verdict, r.kind, line)
        }
        fun net(kind: Kind, v: String): Report =
            finish(control(out, d)?.let { Report(out, it, Kind.NET) } ?: Report(out, v, kind))

        // 1. Адрес.
        val t = System.currentTimeMillis()
        val ip = Resolver.resolve(host)
        val dnsMs = System.currentTimeMillis() - t
        if (ip == null) {
            out += "1 адрес: $host не нашёлся за $dnsMs мс"
            d["dns"] = "fail:$dnsMs"
            return net(Kind.DNS, "Адрес сервера не находится — ни шифрованный DNS, ни DNS оператора его не знают")
        }
        out += "1 адрес: $host → $ip за $dnsMs мс"
        d["dns"] = "ok:$dnsMs"; d["ip"] = ip

        // 2. QUIC-нода: главный вопрос — доходит ли UDP до самого порта.
        if (udp) {
            val q = quic(ip, nodePort, obfsPw, out, "2 UDP")
            d["udp"] = q.code
            if (q.code.startsWith("EPERM")) return finish(selfFail(out))
            if (q.ok) return finish(Report(out, "UDP до сервера проходит: на вопрос о версии QUIC сервер ответил сам" +
                (if (obfsPw.isNotEmpty()) " (через Salamander — пароль верный)" else "") + " за ${q.ms} мс. " +
                "Линия живая — если туннель не встаёт, дело в ключе/пароле, имени (SNI) или перегрузке сервера", Kind.FLOW))
            val (tcpMs, tcpErr) = tcpTry(ip, 443, 4000)
            out += "2в TCP $ip:443 (сам хост): " + (tcpErr?.let { "${Hex.errno(it)} за $tcpMs мс" } ?: "есть за $tcpMs мс")
            d["tcp443"] = tcpErr?.let { "${Hex.errno(it)}:$tcpMs" } ?: "ok:$tcpMs"
            // Контроль: тот же вопрос к ya.ru — Яндекс держит HTTP/3.
            val yip = Resolver.resolve("ya.ru")
            val c = yip?.let { quic(it, 443, "", out, "2б UDP контроль ya.ru") }
            d["qctl"] = c?.code ?: "-"
            return when {
                q.code.startsWith("unreach") -> finish(Report(out, "Порт $nodePort по UDP закрыт: пришло ICMP «порт недоступен» — " +
                    "на сервере здесь никто не слушает (сервис лежит или порт сменили)", Kind.PORT))
                c == null || !c.ok -> net(Kind.UDP, "QUIC молчит и у сервера, и у ya.ru — на этой сети UDP/QUIC режут " +
                    "целиком. Помогут ноды на TCP: VLESS Reality, XHTTP")
                else -> finish(Report(out, "UDP к серверу молчит, а ya.ru по QUIC отвечает — UDP режут именно к " +
                    "$ip:$nodePort" + (if (tcpErr == null) " (сам хост по TCP жив)" else "") +
                    ", либо сервер не принимает QUIC" + (if (obfsPw.isNotEmpty()) " с этим паролем Salamander" else "") +
                    ". Нужна другая нода или порт", Kind.UDP))
            }
        }

        // 2. TCP.
        val (tcpMs, tcpErr) = tcpTry(ip, nodePort, 5000)
        if (tcpErr != null) {
            val code = Hex.errno(tcpErr)
            d["tcp"] = "$code:$tcpMs"
            if (code == "EPERM") { out += "2 TCP $ip:$nodePort: EPERM — проверка не вышла мимо туннеля"; return finish(selfFail(out)) }
            val rst = code == "ECONNREFUSED"
            out += "2 TCP $ip:$nodePort: " + when {
                rst -> "СБРОС за $tcpMs мс — на SYN пришёл RST"
                code == "ETIMEDOUT" -> "SYN без ответа — тишина $tcpMs мс"
                else -> "$code за $tcpMs мс"
            } + " [$code]"
            // Порт или весь адрес? Стучимся в соседние порты того же IP.
            val side = listOf(443, 80, 8443).filter { it != nodePort }.take(2)
            val alive = side.firstOrNull { tcpTry(ip, it, 3000).second == null }
            out += "2б соседние порты ${side.joinToString("/")}: " + (alive?.let { "$it отвечает" } ?: "молчат")
            d["side"] = alive?.let { "$it:ok" } ?: "silent"
            return when {
                alive != null -> net(Kind.PORT, "Режут порт $nodePort, сам адрес живой (порт $alive отвечает) — " +
                    "поможет другой порт этой ноды")
                rst -> net(Kind.PORT, "До сервера соединение сбрасывают сразу — порт закрыт или вмешательство на линии")
                else -> net(Kind.IP, "Адрес $ip молчит на всех портах — сгорел для этой сети (дроп по адресу). " +
                    "Нужен другой вход: другая нода или другой IP")
            }
        }
        out += "2 TCP $ip:$nodePort: есть за $tcpMs мс (SYN → SYN-ACK)"
        d["tcp"] = "ok:$tcpMs"

        if (!tls) {
            // 3. Нода без TLS: что сервер скажет на обычный GET.
            val e = exchange(ip, nodePort, ("GET / HTTP/1.1\r\nHost: ${sni.ifEmpty { host }}\r\n" +
                "User-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n").toByteArray(), "HTTP GET", out, "3 HTTP")
            d["http"] = e.code()
            return finish(Report(out, "Линия до сервера чистая (TLS у ноды нет): TCP есть, ответ сервера на GET — " +
                "в дампе выше", Kind.FLOW))
        }
        if (sni.isEmpty()) return finish(Report(out, "TCP до сервера проходит; имени для TLS у ноды нет — " +
            "приветствие не проверить", Kind.PARTIAL))

        // 3. Приветствие TLS. Не прошло — то же к тому же адресу с нейтральным
        // именем: так видно, режут ли по имени (SNI) или TLS к адресу целиком.
        val h = hello(ip, nodePort, sni, out, "3 TLS")
        d["tls"] = h.code
        if (!h.ok) {
            val n2 = hello(ip, nodePort, "www.google.com", out, "3б TLS нейтр.")
            d["tls_neutral"] = n2.code
            // REALITY МОЛЧИТ НЕ ОТ БЛОКИРОВКИ.
            //
            // Сервер Reality на приветствие без ключа не отвечает сам, а
            // пересылает его своей «маске» (dest) — и с любым именем ответ
            // зависит от того, отвечает ли маска ЕМУ. У Триллера она молчит:
            // журнал 03.11 — «режут TLS к этому адресу целиком», SPb-US
            // «сгорела» и ушла в конец каскада, а в том же блоке через 11 с
            // туннель через тот же адрес и то же имя поднялся. Тишина с обоими
            // именами для Reality ничего не говорит о линии — так и пишем и
            // ноду не жжём.
            return if (n2.ok) net(Kind.SNI, "Режут по имени $sni: с нейтральным именем этот же адрес " +
                    "отвечает — нужно другое имя маскировки (SNI)")
                else if (reality) finish(Report(out, "TCP до сервера проходит; на приветствие без ключа Reality " +
                    "молчит с любым именем — так сервер прячется от проверок, линию по TLS здесь не " +
                    "проверить. Это не приговор ноде", Kind.PARTIAL))
                else net(Kind.TLS, h.verdict + ". С нейтральным именем — тоже: режут TLS к этому адресу целиком")
        }

        // 4. Объём.
        val (kb, stalled) = volume(ip, nodePort, sni)
        // 0 КБ без остановки — объём не измерен (пустой ответ, чужой
        // сертификат), а не «прошёл». Журнал 31.10: так и было написано
        // «объём прошли» при нуле.
        val measured = stalled || kb > 0
        out += "4 объём: " + when {
            stalled -> "встал на $kb КБ"
            measured -> "прошло $kb КБ без остановки"
            else -> "не измерить — сервер не отдал данных по этому имени"
        }
        d["vol"] = if (stalled) "stall:$kb" else if (measured) "ok:$kb" else "-"
        return finish(when {
            stalled && kb in 8..40 -> Report(out,
                "Обрыв после $kb КБ — почерк поведенческой блокировки ТСПУ на этот адрес: " +
                "соединение есть, данные дальше не идут. Сервер тут ни при чём — нужен другой вход " +
                "(IP/подсеть) или другое имя (SNI)", Kind.VOLUME)
            stalled -> Report(out, "Данные встали на $kb КБ — линия до сервера нестабильна", Kind.VOLUME)
            !measured -> Report(out, "До сервера доходят TCP и TLS-приветствие — линию не режут. Объём измерить не удалось. " +
                "Если туннель встаёт, а сайты не открываются, — дело в выходе сервера", Kind.PARTIAL)
            else -> Report(out, "Линия до сервера чистая: адрес, TCP, TLS и объём прошли, свежие соединения " +
                "идут. Если туннель залипал — дропнули сам поток: помогает быстрое переподключение", Kind.FLOW)
        })
    }

    // Контроль — разрешённый сайт. Возвращает вердикт, если сеть мертва
    // сама по себе, иначе null (дело в пути до нашего сервера).
    private fun control(out: MutableList<String>, d: Diag): String? {
        val ip = Resolver.resolve("ya.ru") ?: run {
            out += "5 контроль: ya.ru не нашёлся"
            d["ctl"] = "dns_fail"
            return "Сеть сама не работает: даже разрешённый сайт не находится. Проблема не в сервере — " +
                "плохая связь или сеть глушат"
        }
        val h = hello(ip, 443, "ya.ru", out, "5 контроль")
        d["ctl"] = h.code
        return if (h.ok) null
        else "Сеть сама не работает: разрешённый сайт тоже не отвечает. Проблема не в сервере — " +
            "плохая связь или сеть глушат"
    }

    // ---------------------------------------------------- обмен по TCP

    // Итог одного обмена: сколько байт пришло (-2 тишина, -1 FIN, -3 ошибка).
    private class Ex(val got: Int, val ms: Long, val buf: ByteArray, val err: Throwable?) {
        fun code(): String = when {
            err != null -> "${Hex.errno(err)}:$ms"
            got == -2 -> "silent:$ms"
            got < 0 -> "fin:$ms"
            buf[0] == 0x16.toByte() && got >= 6 && buf[5].toInt() == 2 -> "sh:$ms"
            buf[0] == 0x16.toByte() -> "hs:$ms"
            buf[0] == 0x15.toByte() && got >= 7 -> "alert${buf[6].toInt() and 0xff}:$ms"
            got >= 5 && String(buf, 0, 5, Charsets.ISO_8859_1) == "HTTP/" ->
                "http" + String(buf, 9, minOf(3, (got - 9).coerceAtLeast(0)), Charsets.ISO_8859_1) + ":$ms"
            else -> "junk%02x:%d".format(buf[0], ms)
        }
    }

    // Соединиться, отправить, прочитать первый ответ — и всё это дампом.
    private fun exchange(ip: String, port: Int, payload: ByteArray, what: String,
                         out: MutableList<String>, tag: String): Ex {
        val t = System.currentTimeMillis()
        return try {
            sock().use { s ->
                s.connect(InetSocketAddress(ip, port), 5000)
                s.soTimeout = 6000
                s.getOutputStream().apply { write(payload); flush() }
                out += "$tag → ${payload.size} Б $what:"
                Hex.rows(payload, max = 16).forEach { out += "$tag   $it" }
                val buf = ByteArray(1024)
                val got = try { s.getInputStream().read(buf) } catch (_: java.net.SocketTimeoutException) { -2 }
                val ms = System.currentTimeMillis() - t
                when {
                    got == -2 -> out += "$tag ← тишина $ms мс: соединение есть, но в ответ ни байта"
                    got < 0 -> out += "$tag ← FIN за $ms мс: соединение закрыли без ответа"
                    else -> {
                        out += "$tag ← $got Б за $ms мс:"
                        Hex.rows(buf, got, 32).forEach { out += "$tag   $it" }
                        out += "$tag   ⇒ ${Hex.explainTcp(buf, got)}"
                    }
                }
                Ex(got, ms, buf, null)
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t
            val code = Hex.errno(e)
            out += "$tag ✖ " + (if (code == "ECONNRESET") "СБРОС (RST)" else code) + " за $ms мс · ${e.message?.take(90)}"
            Ex(-3, ms, ByteArray(1), e)
        }
    }

    private class Hello(val ok: Boolean, val verdict: String, val code: String)

    // Наше приветствие TLS и ответ на него.
    private fun hello(ip: String, port: Int, sni: String, out: MutableList<String>, tag: String): Hello {
        val e = exchange(ip, port, clientHello(sni), "ClientHello SNI=$sni", out, tag)
        val b0 = e.buf[0]
        return when {
            e.err != null -> Hello(false, if (Hex.errno(e.err) == "ECONNRESET")
                "На приветствие TLS с именем $sni соединение сбрасывают (RST) — режут по имени"
                else "Приветствие TLS не прошло: ${e.err.message}", e.code())
            e.got == -2 -> Hello(false, "Приветствие TLS с именем $sni до $ip глохнет: ответа нет — " +
                "на линии режут по имени (SNI) или отпечатку", e.code())
            e.got < 0 -> Hello(false, "На приветствие TLS с именем $sni соединение закрывают — вмешательство на линии", e.code())
            // Тревога — тоже ответ: сервер сказал сам, линия пропускает.
            b0 == 0x16.toByte() || b0 == 0x15.toByte() -> Hello(true, "", e.code())
            else -> Hello(false, "Вместо TLS пришёл чужой ответ — похоже на заглушку оператора", e.code())
        }
    }

    // ---------------------------------------------------- обмен по UDP

    private class Q(val ok: Boolean, val code: String, val ms: Long)

    // «Какую версию QUIC ты знаешь?» — пакет с заведомо чужой версией
    // 0x1a2a3a4a, добитый до 1200 байт (меньше сервер обязан выбросить).
    private fun quic(ip: String, port: Int, obfsPw: String, out: MutableList<String>, tag: String): Q {
        val rnd = java.security.SecureRandom()
        val dcid = ByteArray(8).also { rnd.nextBytes(it) }
        val scid = ByteArray(8).also { rnd.nextBytes(it) }
        val pkt = ByteArray(1200).also { rnd.nextBytes(it) }
        pkt[0] = (0xc0 or (pkt[0].toInt() and 0x0f)).toByte()
        pkt[1] = 0x1a; pkt[2] = 0x2a; pkt[3] = 0x3a; pkt[4] = 0x4a
        pkt[5] = 8; System.arraycopy(dcid, 0, pkt, 6, 8)
        pkt[14] = 8; System.arraycopy(scid, 0, pkt, 15, 8)
        val wire = if (obfsPw.isNotEmpty()) Blake2b.salamander(obfsPw, pkt) else pkt
        val t = System.currentTimeMillis()
        return try {
            Bypass.datagram().use { s ->
                s.soTimeout = 1500
                // connect — чтобы ICMP «порт недоступен» дошёл до нас ошибкой.
                s.connect(InetSocketAddress(ip, port))
                out += "$tag → ${wire.size} Б QUIC «какая версия?» на $ip:$port" +
                    if (obfsPw.isNotEmpty()) " (в обёртке Salamander):" else ":"
                Hex.rows(wire, max = 16).forEach { out += "$tag   $it" }
                if (obfsPw.isNotEmpty()) { out += "$tag   без обёртки:"; Hex.rows(pkt, max = 16).forEach { out += "$tag   $it" } }
                val buf = ByteArray(2048)
                val pk = java.net.DatagramPacket(buf, buf.size)
                var got = -1
                // UDP теряется — три попытки по полторы секунды.
                for (i in 0 until 3) {
                    s.send(java.net.DatagramPacket(wire, wire.size))
                    try { s.receive(pk); got = pk.length; break } catch (_: java.net.SocketTimeoutException) {}
                }
                val ms = System.currentTimeMillis() - t
                if (got < 0) {
                    out += "$tag ← тишина $ms мс: 3 пакета ушли, ни одного ответа"
                    return Q(false, "silent:$ms", ms)
                }
                out += "$tag ← $got Б за $ms мс:"
                Hex.rows(buf, got, 32).forEach { out += "$tag   $it" }
                val plain = if (obfsPw.isNotEmpty()) Blake2b.unSalamander(obfsPw, buf, got) else buf.copyOf(got)
                if (plain == null) { out += "$tag   ⇒ обрывок — не QUIC"; return Q(false, "junk:$ms", ms) }
                if (obfsPw.isNotEmpty()) {
                    out += "$tag   снял Salamander:"
                    Hex.rows(plain, plain.size, 32).forEach { out += "$tag   $it" }
                }
                val vn = Hex.explainVn(plain, plain.size, scid)
                out += "$tag   ⇒ ${vn.text}"
                Q(vn.ok, if (vn.ok) "vn:$ms" else "junk:$ms", ms)
            }
        } catch (e: java.net.PortUnreachableException) {
            val ms = System.currentTimeMillis() - t
            out += "$tag ← ICMP «порт недоступен» за $ms мс: на $ip:$port по UDP никто не слушает"
            Q(false, "unreach:$ms", ms)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t
            val code = Hex.errno(e)
            out += "$tag ✖ $code за $ms мс · ${e.message?.take(90)}"
            Q(false, "$code:$ms", ms)
        }
    }

    // ЧЕСТНЫЙ UDP-ПИНГ QUIC-НОДЫ — тот же вопрос о версии, но молча.
    //
    // Замер пинга считал, что Hysteria2 на QUIC «молчит у всех», и мерил
    // соседний TCP-порт (оценка с тильдой). Молчит она на настоящее Initial,
    // а на чужую версию отвечает обязана — 09.11 Bypass-hy2-AT через
    // Salamander ответил за 89 мс. Возвращает мс или -1.
    fun quicPing(ip: String, port: Int, obfsPw: String, timeoutMs: Int): Int = try {
        val rnd = java.security.SecureRandom()
        val scid = ByteArray(8).also { rnd.nextBytes(it) }
        val pkt = ByteArray(1200).also { rnd.nextBytes(it) }
        pkt[0] = (0xc0 or (pkt[0].toInt() and 0x0f)).toByte()
        pkt[1] = 0x1a; pkt[2] = 0x2a; pkt[3] = 0x3a; pkt[4] = 0x4a
        pkt[5] = 8; pkt[14] = 8; System.arraycopy(scid, 0, pkt, 15, 8)
        val wire = if (obfsPw.isNotEmpty()) Blake2b.salamander(obfsPw, pkt) else pkt
        Bypass.datagram().use { s ->
            s.soTimeout = (timeoutMs / 2).coerceAtLeast(300)
            s.connect(InetSocketAddress(ip, port))
            val buf = ByteArray(2048)
            val pk = java.net.DatagramPacket(buf, buf.size)
            var ms = -1
            for (i in 0 until 2) {
                val t = System.currentTimeMillis()
                s.send(java.net.DatagramPacket(wire, wire.size))
                try { s.receive(pk) } catch (_: java.net.SocketTimeoutException) { continue }
                val plain = if (obfsPw.isNotEmpty()) Blake2b.unSalamander(obfsPw, buf, pk.length) else buf.copyOf(pk.length)
                if (plain != null && Hex.explainVn(plain, plain.size, scid).ok)
                    ms = (System.currentTimeMillis() - t).toInt().coerceAtLeast(1)
                break
            }
            ms
        }
    } catch (_: Exception) { -1 }

    // Сколько килобайт пройдёт, прежде чем встанет. Несколько запросов по
    // одному соединению — у многих сайтов главная страница меньше 16 КБ.
    private fun volume(ip: String, port: Int, sni: String): Pair<Int, Boolean> {
        val sec = java.util.concurrent.TimeUnit.SECONDS
        val http = okhttp3.OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName(ip))
            })
            .socketFactory(Bypass.factory)
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
