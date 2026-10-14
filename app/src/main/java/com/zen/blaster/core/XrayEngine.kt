package com.zen.blaster.core

import android.content.Context
import com.zen.blaster.model.Node
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================
//  Xray-core — второй движок, ОТДЕЛЬНЫМ ПРОЦЕССОМ.
//
//  Почему процессом, а не библиотекой: и sing-box, и Xray собраны через
//  gomobile и содержат класс go.Seq, причём РАЗНЫЙ (sing-box — форк sagernet,
//  Xray — стандартный gomobile). В одном процессе они не уживаются: каждый
//  go.Seq грузит свою нативку. Отдельный процесс снимает конфликт полностью.
//
//  Бинарник лежит в jniLibs как libxrayexec.so — Android разрешает exec
//  только из nativeLibraryDir (и только при useLegacyPackaging = true).
//
//  Схема: TUN(sing-box) → socks 127.0.0.1:10809 → Xray → сервер.
// ============================================================================
object XrayEngine {

    // Порт по умолчанию — только для собранного заранее конфига. При каждом
    // подъёме берётся СЛУЧАЙНЫЙ свободный (см. claim), с паролем.
    const val SOCKS_PORT = 10809

    // ПОРТ И ПАРОЛЬ SOCKS — НОВЫЕ НА КАЖДЫЙ ПОДЪЁМ.
    //
    // Был зашит 10809 без пароля, и это било дважды:
    //  • Брошенный Xray (Android перезапустил приложение, а дочерний процесс
    //    остался сиротой) держал 10809. Новый Xray молча не вставал, а мы
    //    видели «порт отвечает» и поднимались ПОВЕРХ СТАРОГО — со старым
    //    конфигом, то есть со старой нодой. За 14.10.2026 «прошлое ядро держит
    //    SOCKS 10809» — 20 раз; со стороны это «прокси виснет».
    //  • Открытый SOCKS на известном порту — подарок приложениям, которые
    //    сканируют 127.0.0.1 в поисках VPN: через него виден адрес сервера.
    @Volatile var port: Int = SOCKS_PORT; private set
    @Volatile var user: String = ""; private set
    @Volatile var pass: String = ""; private set

    private fun claim() {
        user = "x" + ClashApi.token(8); pass = ClashApi.token(20)
        val r = java.security.SecureRandom()
        for (i in 1..40) {
            val p = 20000 + r.nextInt(40000)
            if (ClashApi.free(p)) { port = p; return }
        }
        port = SOCKS_PORT
    }

    // Убить брошенные процессы Xray нашего же приложения. Они остаются, когда
    // система перезапускает приложение: дочерний процесс переживает родителя
    // и продолжает держать порт и ходить в старую ноду.
    fun reapOrphans(): Int {
        val me = android.os.Process.myUid()
        var n = 0
        val dirs = java.io.File("/proc").listFiles() ?: return 0
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                if (android.system.Os.stat("/proc/$pid").st_uid != me) continue
                val cmd = java.io.File(d, "cmdline").readText()
                if (!cmd.contains("libxrayexec.so")) continue
                android.os.Process.killProcess(pid)
                n++
            } catch (_: Throwable) {}
        }
        return n
    }

    // Хранилища доверенных корней Android. На 14+ они переехали в apex,
    // на старых лежат в /system — перечисляем оба через двоеточие (Go так умеет).
    private const val CA_DIRS =
        "/system/etc/security/cacerts:/apex/com.android.conscrypt/cacerts"

    // Транспорты, которые sing-box не умеет — их отдаём Xray.
    private val XRAY_ONLY = setOf("xhttp", "splithttp", "kcp", "mkcp")

    // Переименования полей XHTTP.
    //
    // Панели выдают ссылки под разные версии ядра, а Xray незнакомые поля
    // молча игнорирует: запрос уходит без сессионной куки, сервер отвечает
    // 400, и нода выглядит мёртвой. Приводим известные синонимы к именам,
    // которые ядро действительно читает.
    private val ALIAS = mapOf(
        "sessionPlacement" to "sessionIDPlacement",
        "sessionKey" to "sessionIDKey",
        "sessionTable" to "sessionIDTable",
        "sessionLength" to "sessionIDLength"
    )

    // Xray нужен не только из-за транспорта: VLESS Encryption sing-box не умеет
    // вовсе, поэтому такую ноду он поднять не сможет ни при каком транспорте.
    fun handles(n: Node): Boolean =
        n.type == "VLESS" &&
        (n.transport.lowercase() in XRAY_ONLY || n.encryption.isNotEmpty())

    // Транспорты, которые умеют ОБА ядра.
    //
    // Такую ноду можно поднять и тем, и другим — а значит, отказ одного ядра
    // ещё не приговор ноде. Реализации расходятся в мелочах: padding, ALPN,
    // порядок расширений в TLS-приветствии. Там, где молчит одно, второе
    // иногда проходит — и наоборот, поэтому откат нужен в ОБЕ стороны.
    private val BOTH = setOf("tcp", "ws", "grpc", "http", "h2", "httpupgrade")

    fun bothPossible(n: Node): Boolean =
        n.type == "VLESS" && n.transport.lowercase().ifEmpty { "tcp" } in BOTH

    @Volatile private var proc: Process? = null
    @Volatile var lastError: String = ""; private set
    val running: Boolean get() = proc?.isAlive == true

    private fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libxrayexec.so")

    fun available(ctx: Context): Boolean = binary(ctx).canExecute()

    fun version(ctx: Context): String = try {
        val p = ProcessBuilder(binary(ctx).absolutePath, "version").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("Xray ([0-9.]+)").find(out)?.groupValues?.get(1) ?: "?"
    } catch (_: Throwable) { "?" }

    // Поднимает Xray с готовым конфигом (JSON-строка). Возвращает true, если
    // процесс жив и SOCKS реально принимает соединения.
    fun start(ctx: Context, configJson: String, onLog: (String) -> Unit): Boolean {
        stop()
        val orphans = reapOrphans()
        if (orphans > 0) onLog("♻️ Погасил брошенный Xray от прошлого запуска ($orphans)")
        val bin = binary(ctx)
        if (!bin.canExecute()) { lastError = "бинарник Xray не найден/не исполняемый"; return false }
        claim()
        return try {
            val cfg = File(ctx.filesDir, "xray_config.json")
            cfg.writeText(withSocks(retargetDpi(resolveServer(configJson, onLog))))

            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.absolutePath
            // Xray собран как GOOS=linux и ищет корневые сертификаты по путям
            // обычного линукса (/etc/ssl/certs и т.п.), которых на Android нет.
            // Без этого ЛЮБОЙ обычный TLS-сервер отваливается с
            // «x509: certificate signed by unknown authority» — выживают только
            // Reality-ноды, которые цепочку не проверяют вовсе.
            pb.environment()["SSL_CERT_DIR"] = CA_DIRS
            val p = pb.start()
            proc = p

            // Читаем вывод ядра в лог приложения.
            Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        android.util.Log.i("ZenXray", line)   // видно через adb logcat
                        // в UI-лог тащим только важное, иначе он захлёбывается
                        if (line.contains("[Warning]") || line.contains("[Error]") ||
                            line.contains("failed") || line.contains("started"))
                            onLog("[Xray] ${line.take(200)}")
                    }
                } catch (_: Throwable) {}
            }, "xray-log").apply { isDaemon = true }.start()

            // Ждём, пока SOCKS начнёт принимать соединения.
            for (i in 1..25) {
                if (!p.isAlive) { lastError = "процесс Xray завершился (код ${p.exitValue()})"; return false }
                if (socksAlive()) { warmUp(onLog); return true }
                Thread.sleep(200)
            }
            lastError = "Xray запущен, но SOCKS $port не отвечает"
            false
        } catch (t: Throwable) {
            lastError = t.message ?: "исключение при запуске Xray"
            false
        }
    }

    // ---------------------------------------------- предразрешение адреса
    //
    //  На Android нет /etc/resolv.conf, поэтому Go-резолвер внутри Xray лезет
    //  в [::1]:53 и получает «connection refused». Для XHTTP это фатально:
    //  транспорт дозванивается обычным net/http, минуя внутренний DNS ядра,
    //  и никакие dns/sockopt-настройки это не чинят.
    //
    //  Поэтому имя сервера разрешаем САМИ (DoH, мимо резолвера оператора) и
    //  кладём в конфиг уже IP. SNI/host остаются доменом — TLS не ломается.
    // Порт щита в конфиге — актуальный, а не тот, что был при сборке.
    //
    // Конфиг Xray собирается заранее, вместе с выбором ноды, а ByeDPI берёт
    // свободный порт уже при старте. Со старым номером в конфиге Xray уходил
    // стучаться в пустоту, и нода не поднималась при живом сервере.
    private fun retargetDpi(configJson: String): String = try {
        val root = JSONObject(configJson)
        val outs = root.optJSONArray("outbounds") ?: return configJson
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") != "dpi") continue
            val servers = o.optJSONObject("settings")?.optJSONArray("servers") ?: continue
            for (j in 0 until servers.length()) {
                servers.optJSONObject(j)?.put("port", ByeDpi.port)?.let { changed = true }
            }
        }
        if (changed) root.toString() else configJson
    } catch (_: Throwable) { configJson }

    private fun resolveServer(configJson: String, onLog: (String) -> Unit): String = try {
        val obj = JSONObject(configJson)
        val vnext = obj.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val host = vnext.getString("address")
        if (Resolver.isIpLiteral(host)) configJson else {
            val ip = Resolver.resolve(host)
            if (ip == null) { onLog("⚠️ Не удалось разрешить $host — Xray попробует сам"); configJson }
            else { onLog("🔎 $host → $ip"); vnext.put("address", ip); obj.toString() }
        }
    } catch (t: Throwable) { configJson }

    // Вход SOCKS в конфиге — на выпавший порт и с паролем.
    private fun withSocks(json: String): String = try {
        val root = JSONObject(json)
        val ins = root.optJSONArray("inbounds")
        if (ins != null) for (i in 0 until ins.length()) {
            val o = ins.optJSONObject(i) ?: continue
            if (o.optString("tag") != "socks-in") continue
            o.put("port", port)
            o.put("settings", JSONObject().put("udp", true).put("auth", "password")
                .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass))))
        }
        root.toString()
    } catch (_: Throwable) { json }

    // SOCKS5 с паролем руками: у Java-сокетов через Proxy пароль толком не
    // передать. Возвращает сокет, уже соединённый с host:port через Xray.
    private fun socks5(host: String, dstPort: Int, timeoutMs: Int): java.net.Socket {
        val s = java.net.Socket()
        s.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
        s.soTimeout = timeoutMs
        val o = s.getOutputStream(); val i = java.io.DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2)); o.flush()                 // метод: логин/пароль
        val m = ByteArray(2); i.readFully(m)
        if (m[1].toInt() != 2) { s.close(); throw java.io.IOException("SOCKS: нет метода с паролем") }
        val u = user.toByteArray(); val pw = pass.toByteArray()
        o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw); o.flush()
        val a = ByteArray(2); i.readFully(a)
        if (a[1].toInt() != 0) { s.close(); throw java.io.IOException("SOCKS: пароль не принят") }
        val h = host.toByteArray()
        o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h +
            byteArrayOf((dstPort shr 8).toByte(), dstPort.toByte())); o.flush()
        val r = ByteArray(4); i.readFully(r)
        if (r[1].toInt() != 0) { s.close(); throw java.io.IOException("SOCKS: отказ ${r[1]}") }
        when (r[3].toInt()) {                                     // хвост ответа
            1 -> i.readFully(ByteArray(6)); 4 -> i.readFully(ByteArray(18))
            3 -> i.readFully(ByteArray(i.readUnsignedByte() + 2))
        }
        return s
    }

    // Первый запрос через XHTTP дорогой: поднимается xmux-сессия, бывает и 5 с.
    // Проверка туннеля в сервисе ждёт меньше — и живая нода записывалась в
    // мёртвые. Поэтому один прогревочный запрос делаем сами, не торопясь.
    private fun warmUp(onLog: (String) -> Unit) {
        try {
            val t = System.currentTimeMillis()
            val code = socks5("cp.cloudflare.com", 80, 12_000).use { s ->
                s.getOutputStream().write(("GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                s.getInputStream().bufferedReader().readLine().orEmpty()
                    .split(' ').getOrNull(1) ?: "?"
            }
            onLog("🔥 Прогрев Xray: HTTP $code за ${System.currentTimeMillis() - t} мс")
        } catch (t: Throwable) {
            onLog("⚠️ Прогрев Xray не прошёл: ${t.message}")
        }
    }

    private fun socksAlive(): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Throwable) { false }

    fun stop() = kill(detach())

    // Забрать текущий процесс, ничего с ним не делая. Нужно, чтобы гасить его
    // в фоне: если бы фоновый поток читал proc сам, он мог бы прибить уже
    // НОВЫЙ Xray, запущенный человеком через секунду после «отключить».
    fun detach(): Process? { val p = proc; proc = null; return p }

    // Погасить процесс. Ждёт до двух секунд — поэтому с главного потока
    // не звать (см. ZenVpnService.stopVpn).
    fun kill(p: Process?) {
        p ?: return
        try { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        catch (_: Throwable) { try { p.destroyForcibly() } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------ конфиг Xray

    fun buildConfig(n: Node, armor: Boolean, fp: String, viaDpi: Boolean = false,
                    frontDomain: String = ""): JSONObject {
        // Режим белых списков: прикрываемся разрешённым доменом. Reality не
        // трогаем — там SNI связан с настройкой сервера, подмена ломает связь.
        val front = frontDomain.isNotEmpty() && n.pbk.isEmpty()
        val tr = n.transport.lowercase()
        val stream = JSONObject().put("network", if (tr == "splithttp") "xhttp" else tr)

        when (stream.getString("network")) {
            "xhttp" -> {
                // extra несёт xmux/scMax*/xPaddingBytes. Xray принимает эти поля
                // как ВЛОЖЕННЫЕ в xhttpSettings, поэтому разворачиваем JSON, а не
                // кладём строкой — иначе сервер рвёт соединение или отвечает 400.
                val x = JSONObject()
                if (n.extra.isNotEmpty()) {
                    try {
                        val e = JSONObject(n.extra)
                        e.keys().forEach { k -> x.put(ALIAS[k] ?: k, e.get(k)) }
                    } catch (_: Throwable) {}
                }
                // Путь ОБЯЗАН заканчиваться слешем: к нему ядро дописывает
                // идентификатор сессии и номер пакета. Без слеша сервер видит
                // склеенный путь и отвечает 404 — нода выглядит мёртвой, хотя
                // жива. В ссылках подписок слеш проставлен не всегда.
                x.put("path", n.path.ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" })
                // Host в режиме белых списков тоже должен называть разрешённый
                // домен: DPI читает и SNI, и заголовок Host.
                val h = if (front) frontDomain else n.host
                if (h.isNotEmpty()) x.put("host", h)
                if (n.mode.isNotEmpty()) x.put("mode", n.mode)
                // Аварийный режим: одна длинная сессия вместо пачки коротких —
                // пачка параллельных запросов к «картинке с CDN» выглядит
                // подозрительно, одиночная сессия похожа на обычную страницу.
                //
                // НО только если провайдер сам ничего не задал. Его mode и extra
                // подогнаны под конкретный сервер и жёстко связаны между собой:
                // например uplinkDataPlacement=header допустим ТОЛЬКО в
                // packet-up. Подмена режима ломала конфиг целиком, и ядро
                // выходило с ошибкой, так и не подключившись.
                if (n.wl && n.mode.isEmpty() && n.extra.isEmpty()) {
                    x.put("mode", "stream-one")
                    x.put("xmux", JSONObject().put("maxConnections", 1).put("maxReuseTimes", 0))
                }
                stream.put("xhttpSettings", x)
            }
            "kcp", "mkcp" -> {
                stream.put("network", "kcp")
                val k = JSONObject().put("header",
                    JSONObject().put("type", n.headerType.ifEmpty { "none" }))
                val s = n.seed.ifEmpty { n.path }
                if (s.isNotEmpty()) k.put("seed", s)
                stream.put("kcpSettings", k)
            }
            "ws" -> {
                val w = JSONObject().put("path", n.path.ifEmpty { "/" })
                val h = if (front) frontDomain else n.host
                if (h.isNotEmpty()) w.put("host", h)
                stream.put("wsSettings", w)
            }
            "grpc" -> stream.put("grpcSettings",
                JSONObject().put("serviceName", n.serviceName.ifEmpty { n.path }))
        }

        val sni = if (front) frontDomain
                  else n.sni.ifEmpty { if (n.host.isNotEmpty()) n.host else n.addr }
        val useFp = if (armor && fp.isNotEmpty()) fp else n.fp.ifEmpty { "chrome" }
        when {
            n.pbk.isNotEmpty() -> {
                stream.put("security", "reality")
                stream.put("realitySettings", JSONObject()
                    .put("serverName", sni).put("publicKey", n.pbk)
                    .put("shortId", n.sid).put("fingerprint", useFp))
            }
            n.security != "none" -> {
                val t = JSONObject().put("serverName", sni).put("fingerprint", useFp)
                if (n.alpn.isNotEmpty()) t.put("alpn", JSONArray(n.alpn.split(",")))
                if (n.wl) {
                    // Аварийный профиль: скучный распространённый отпечаток и
                    // обычный TLS вместо QUIC — h3 в такие моменты режут первым.
                    t.put("fingerprint", "chrome")
                    t.put("alpn", JSONArray().put("h2").put("http/1.1"))
                }
                // allowInsecure в Xray 26 УДАЛЁН: ядро отвергает такой конфиг
                // целиком и выходит с ошибкой (тот самый «код 23»).
                // Замена лучше оригинала: имя, которое видит DPI, и имя, по
                // которому проверяется сертификат, теперь РАЗНЫЕ. Шифрование и
                // проверка подлинности остаются в силе — мы ничего не отключаем.
                if (front || n.insecure) {
                    val real = n.sni.ifEmpty { if (n.host.isNotEmpty()) n.host else n.addr }
                    t.put("verifyPeerCertByName", real)
                }
                stream.put("security", "tls")
                stream.put("tlsSettings", t)
            }
            else -> stream.put("security", "none")
        }

        // ------------------------------------------------------- ОБФУСКАЦИЯ
        //
        // Подсмотрено у Happ Desktop, который на этих же нодах поднимается
        // там, где мы молчали. Секрета не оказалось: это НЕ свойство ноды и
        // не чужой протокол, а настройки НАШЕЙ стороны соединения, которые
        // ядро Xray умеет само. У Happ они лежат открытым текстом в
        // ~/.config/Happ.conf:
        //     Noises\noisesSchemaV2=true
        //     Muxing\muxingTcpConcurrency=8
        //     Muxing\muxingXudpConcurrency=8
        //
        // Что это даёт:
        //  • fragment — режет TLS-приветствие на куски. DPI собирает поток
        //    не так, как сервер, и не видит целиком имя сайта, по которому
        //    принимает решение.
        //  • noises — перед приветствием уходит немного мусора. Сигнатура
        //    начала сессии перестаёт совпадать с шаблоном «клиент VPN».
        //
        // Через ByeDPI это НЕ включаем: он режет тот же пакет по-своему, и
        // две нарезки поверх друг друга дают мусор, который сервер не соберёт.
        val sockopt = JSONObject()
        if (viaDpi) {
            // Цепочка через ByeDPI: фрагментация ClientHello до того, как пакет
            // увидит оператор. dialerProxy — штатный способ Xray сцепить outbound'ы.
            sockopt.put("dialerProxy", "dpi")
        } else if (armor && !n.wl) {
            // АВАРИЙНЫЕ НОДЫ (n.wl) ОБФУСКАЦИЮ НЕ ПОЛУЧАЮТ — и это не
            // упущение, а то же правило, по которому мы не пускаем их через
            // ByeDPI.
            //
            // Смысл аварийной ноды в том, что она ВЫГЛЯДИТ обычным походом на
            // разрешённый сайт: XHTTP через российский CDN, нормальное
            // TLS-приветствие, узнаваемый домен. Нарезка приветствия и шум
            // перед рукопожатием ломают ровно эту похожесть: вместо «человек
            // открыл картинку с CDN» получается «кто-то шлёт мусор и рвёт
            // пакеты» — то есть именно то, что мы прячем.
            //
            // Проверено на живой сети 14.08.2026: с обфускацией обходная нода
            // поднималась, но трафик через неё не шёл совсем.

            // Нарезка приветствия: первые пакеты, по 10-20 байт, с паузой.
            sockopt.put("fragment", JSONObject()
                .put("packets", "tlshello")
                .put("length", "10-20")
                .put("interval", "10-20"))
            // Шум перед рукопожатием. Схема V2 — массив описаний,
            // ровно как у Happ.
            sockopt.put("noises", JSONArray()
                .put(JSONObject().put("type", "rand")
                    .put("packet", "10-20").put("delay", "10-16"))
                .put(JSONObject().put("type", "rand")
                    .put("packet", "8-16").put("delay", "5-10")))
        }
        if (sockopt.length() > 0) stream.put("sockopt", sockopt)

        // Не "none" наглухо: ноды с VLESS Encryption присылают свой длинный ключ,
        // и подмена его на "none" ломала расшифровку ответа сервера.
        val user = JSONObject().put("id", n.uuid)
            .put("encryption", n.encryption.ifEmpty { "none" })
        if (n.flow.isNotEmpty()) user.put("flow", n.flow)

        val proxy = JSONObject()
            .put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", n.addr).put("port", n.port)
                    .put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)

        // Мультиплексирование — восемь потоков в одном соединении, как у Happ.
        //
        // Смысл не в скорости, а во внешнем виде: вместо роя коротких TCP-сессий
        // оператор видит одно долгое соединение. Рой — это почерк туннеля, по
        // нему и ловят.
        //
        // ДВА СЛУЧАЯ, КОГДА MUX ВКЛЮЧАТЬ НЕЛЬЗЯ:
        //
        //  • XHTTP — у него собственное мультиплексирование (xmux), два слоя
        //    поверх друг друга ломают сессию;
        //  • VLESS Vision (flow=xtls-rprx-vision) — Vision несовместим с mux
        //    ПО УСТРОЙСТВУ: он работает с исходным потоком TLS, а mux этот
        //    поток заворачивает в свой. Проверено на живой ноде: туннель
        //    поднимался и тут же падал, а выглядело это как «нода умерла».
        if (armor && stream.getString("network") != "xhttp" && n.flow.isEmpty()) {
            proxy.put("mux", JSONObject()
                .put("enabled", true)
                .put("concurrency", 8)
                .put("xudpConcurrency", 8)
                .put("xudpProxyUDP443", "reject"))
        }

        val inbound = JSONObject()
            .put("tag", "socks-in").put("listen", "127.0.0.1").put("port", SOCKS_PORT)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))

        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        if (viaDpi) outbounds.put(JSONObject()
            .put("tag", "dpi").put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", "127.0.0.1").put("port", ByeDpi.PORT)))))

        return JSONObject()
            // info, а не warning: на warning ядро молчит о том, ПОЧЕМУ не идёт
            // трафик. В UI-лог всё равно попадает только важное — фильтр
            // стоит при чтении вывода процесса.
            .put("log", JSONObject().put("loglevel", "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
    }
}
