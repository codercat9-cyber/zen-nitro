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
    // ОТПЕЧАТОК ДЛЯ XRAY — Chrome 120.
    //
    // Замер 10.11.2026 на Xray 26.10.10 (Reality, приветствие на локальный
    // приёмник): chrome/131/133 — 1754–1818 байт, firefox — 1887, safari —
    // 1535 (постквантовый ключ, больше пакета; в этот день такие перестали
    // проходить ТСПУ). hellochrome_120 — 517–596 байт: настоящий Chrome, как
    // на любом Android, с перетасовкой расширений, но ещё без ключа. Тот же
    // выбор есть в INCY. «android» в uTLS — OkHttp из Android 11 без TLS 1.3:
    // Reality с ним не стартует вовсе («Current fingerprint Android11 does not
    // support TLS 1.3») — для Reality это тоже Chrome 120, браузер Android.
    const val LIGHT_FP = "hellochrome_120"
    // Тонкая настройка из Prefs.applyTuning(): mux, нарезка приветствия и
    // «тяжёлый» отпечаток, если человек выбрал его сам.
    @Volatile var muxOn = false
    @Volatile var fragOn = true
    @Volatile var allowHeavy = false
    fun xrayFp(fp: String, reality: Boolean): String = when (fp.lowercase()) {
        "chrome", "firefox", "safari" -> if (allowHeavy) fp else LIGHT_FP
        "", "edge", "random", "randomized" -> LIGHT_FP
        "android" -> if (reality) LIGHT_FP else "android"
        else -> fp
    }

    // Выход-нарезчик: через него (dialerProxy) прокси дозванивается до сервера,
    // и TLS-приветствие уходит кусками. См. buildConfig, «броня».
    const val FRAG_TAG = "frag"

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
                // Встроенный — по имени файла, модуль — по своей папке
                // (у него в командной строке первым стоит загрузчик).
                if (!cmd.contains("libxrayexec.so") && !cmd.contains("/cores/xray/")) continue
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
    fun handles(n: Node): Boolean = n.raw.isNotEmpty() ||
        n.type == "VLESS" &&
        (n.transport.lowercase() in XRAY_ONLY || n.encryption.isNotEmpty() || n.fm.isNotEmpty())

    // Транспорты, которые умеют ОБА ядра.
    //
    // Такую ноду можно поднять и тем, и другим — а значит, отказ одного ядра
    // ещё не приговор ноде. Реализации расходятся в мелочах: padding, ALPN,
    // порядок расширений в TLS-приветствии. Там, где молчит одно, второе
    // иногда проходит — и наоборот, поэтому откат нужен в ОБЕ стороны.
    private val BOTH = setOf("tcp", "ws", "grpc", "http", "h2", "httpupgrade")

    fun bothPossible(n: Node): Boolean =
        (n.type == "VLESS" && n.transport.lowercase().ifEmpty { "tcp" } in BOTH) || hy2ViaXray(n)

    // HYSTERIA2 — ТОЖЕ ОБОИМИ ЯДРАМИ. Было «второго ядра для этой ноды нет»
    // (журналы Bypass-hy2-AT/LV, FastLane), а Xray 26 умеет Hysteria2 и
    // вдобавок шум перед рукопожатием «как у Amnezia» (см. AwgBait). Нельзя
    // только две вещи: сертификат «не проверять» (allowInsecure в Xray 26
    // удалён) и обфускацию gecko (в Xray её нет).
    fun hy2ViaXray(n: Node): Boolean =
        n.type == "Hysteria2" && !n.insecure && n.obfs.lowercase() in setOf("", "salamander")

    @Volatile private var proc: Process? = null
    @Volatile var lastError: String = ""; private set
    val running: Boolean get() = proc?.isAlive == true

    private fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libxrayexec.so")

    // МОДУЛЬ XRAY (CoreModules): файл в папке приложения. Запустить его
    // напрямую Android 10+ не даст — запускаем системный загрузчик и отдаём
    // файл ему (загрузчик грузит его как библиотеку, это разрешено). Модуль
    // собран под Android (PIE), встроенный — как раньше, из nativeLibraryDir.
    private val LINKER = if (android.os.Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    // Команда запуска: модуль через загрузчик либо встроенный бинарник.
    // Второе — модуль ли это (для отчёта CoreModules).
    private fun command(ctx: Context): Pair<List<String>, Boolean> {
        val m = CoreModules.active(ctx, CoreModules.XRAY)
        if (m != null && !forceBuiltin) return listOf(LINKER, m.path) to true
        return listOf(binary(ctx).absolutePath) to false
    }

    // На этот запуск — только встроенный (модуль только что не поднялся).
    @Volatile private var forceBuiltin = false

    @Volatile var moduleInUse = false; private set

    fun available(ctx: Context): Boolean =
        binary(ctx).canExecute() || CoreModules.active(ctx, CoreModules.XRAY) != null

    fun version(ctx: Context, builtin: Boolean = false): String = try {
        val cmd = if (builtin) listOf(binary(ctx).absolutePath) else command(ctx).first
        val p = ProcessBuilder(cmd + "version").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("Xray ([0-9.]+)").find(out)?.groupValues?.get(1) ?: "?"
    } catch (_: Throwable) { "?" }

    // Поднимает Xray с готовым конфигом (JSON-строка). Возвращает true, если
    // процесс жив и SOCKS реально принимает соединения.
    // VLESS без TLS и без шифрования. Xray 26.10.29+ такой выход до публичного
    // IP запрещает уже при старте («vless without TLS or other encryption is
    // prohibited unless the server address is a private IP or domain»), а мы
    // ещё и подставляем IP вместо имени (resolveServer). Такие ноды всегда
    // отдаём встроенному ядру, остальные — модулю. Иначе из-за пары нод mKCP
    // новое ядро нельзя было бы выпускать вовсе (робот поймал 31.10.2026).
    fun bareVless(configJson: String): Boolean = try {
        val o = JSONObject(configJson).getJSONArray("outbounds").getJSONObject(0)
        if (o.optString("protocol") != "vless") false else {
            val sec = o.optJSONObject("streamSettings")?.optString("security").orEmpty()
            val enc = o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                ?.optJSONArray("users")?.optJSONObject(0)?.optString("encryption").orEmpty()
            (sec.isEmpty() || sec == "none") && (enc.isEmpty() || enc == "none")
        }
    } catch (_: Throwable) { false }

    // Следующий запуск — на встроенном ядре: откат, когда нода не встала на
    // модуле (каскад в MainActivity). Срабатывает один раз.
    @Volatile var builtinNext = false

    fun start(ctx: Context, configJson: String, onLog: (String) -> Unit): Boolean {
        if (builtinNext && CoreModules.active(ctx, CoreModules.XRAY) != null) {
            builtinNext = false
            onLog("↩ Откат: эту ноду — на встроенном ядре Xray")
            forceBuiltin = true
            return try { startOnce(ctx, configJson, onLog) } finally { forceBuiltin = false }
        }
        builtinNext = false
        if (CoreModules.active(ctx, CoreModules.XRAY) != null && bareVless(configJson)) {
            onLog("🧩 VLESS без TLS — беру встроенное ядро Xray: новые модули такое до IP не пускают")
            forceBuiltin = true
            return try { startOnce(ctx, configJson, onLog) } finally { forceBuiltin = false }
        }
        val ok = startOnce(ctx, configJson, onLog)
        if (!moduleInUse) return ok
        // Процесс поднялся и открыл SOCKS — это ещё НЕ удача модуля: удачу
        // засчитывает MainActivity, когда через модуль встал проверенный
        // туннель. Здесь было report(true) на каждый запуск — и счётчик
        // провалов обнулялся раньше, чем доходил до трёх: 04.11 модуль
        // 26.10.31 пять раз подряд не поднял ноды Триллера (встроенное ядро
        // поднимало), а снят так и не был.
        if (ok) return true
        // Модуль не поднялся сам по себе (процесс умер, SOCKS не открылся) —
        // засчитываем ему отказ и тут же пробуем встроенным ядром: человек
        // не должен остаться без ноды из-за свежего модуля.
        val why = lastError
        val off = CoreModules.report(ctx, CoreModules.XRAY, false, why.take(80))
        onLog("⚠️ Модуль Xray не поднялся ($why) — запускаю встроенное ядро" +
            (if (off) " · модуль выключен после трёх отказов" else ""))
        forceBuiltin = true
        return try { startOnce(ctx, configJson, onLog) } finally { forceBuiltin = false }
    }

    // ПЕРЕЗАПУСК ОДНОГО XRAY — ТУННЕЛЬ НЕ ТРОГАЕМ.
    //
    // Тот же порт, тот же логин и то же ядро (модуль или встроенное), что
    // были: sing-box и TUN продолжают работать и снова находят Xray, как
    // только он встал. Заодно Xray заново спрашивает адрес сервера по DoH —
    // если провайдер сменил IP (10.11.2026, «AI🤖»: 50.7.87.251 умер, имя уже
    // отдавало другие), он уходит на новый. Так INCY держит соединение:
    // работающий туннель не рвут, чинят то, что под ним.
    @Volatile var respawning = false; private set

    @Synchronized
    fun respawn(ctx: Context, configJson: String, onLog: (String) -> Unit): Boolean {
        val builtin = !moduleInUse
        forceBuiltin = builtin
        respawning = true
        return try { startOnce(ctx, configJson, onLog, keepPort = true) }
               finally { forceBuiltin = false; respawning = false }
    }

    private fun startOnce(ctx: Context, configJson: String, onLog: (String) -> Unit,
                          keepPort: Boolean = false): Boolean {
        stop()
        val orphans = reapOrphans()
        if (orphans > 0 && !keepPort) onLog("♻️ Погасил брошенный Xray от прошлого запуска ($orphans)")
        val (cmd, fromModule) = command(ctx)
        moduleInUse = fromModule
        if (!fromModule && !binary(ctx).canExecute()) { lastError = "бинарник Xray не найден/не исполняемый"; return false }
        // Порт только что отпущен убитым процессом — ждём, пока освободится.
        if (keepPort) { for (i in 1..15) { if (ClashApi.free(port)) break; Thread.sleep(200) } }
        else claim()
        return try {
            val cfg = File(ctx.filesDir, "xray_config.json")
            cfg.writeText(withSocks(retargetDpi(cloudflareSafe(resolveServer(configJson, onLog), onLog))))

            val pb = ProcessBuilder(cmd + listOf("run", "-c", cfg.absolutePath))
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
                        CoreTrail.feed(line)                  // след для разбора обрыва
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

    // Адреса серверов — нашим шифрованным DNS, ДО запуска Xray.
    //
    // Сам Xray (он вне туннеля) спросил бы DNS оператора, а тот подменяет
    // ответы (журнал 09.11: «2 из 3»). Раньше так разрешался только первый
    // выход; у конфига-группы серверов до трёх десятков — разрешаем все,
    // параллельно. Имя для TLS и заголовок Host при этом сохраняем: без них
    // Xray взял бы именем адрес, а это теперь IP.
    private fun resolveServer(configJson: String, onLog: (String) -> Unit): String = try {
        val obj = JSONObject(configJson)
        val outs = obj.getJSONArray("outbounds")
        // (выход, точка с адресом) для всех настоящих выходов.
        val spots = mutableListOf<Pair<JSONObject, JSONObject>>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") !in PROXY) continue
            val s = o.optJSONObject("settings") ?: continue
            s.optJSONArray("vnext")?.let { a -> for (j in 0 until a.length()) a.optJSONObject(j)?.let { spots += o to it } }
            s.optJSONArray("servers")?.let { a -> for (j in 0 until a.length()) a.optJSONObject(j)?.let { spots += o to it } }
            if (s.has("address")) spots += o to s
        }
        val hosts = spots.map { it.second.optString("address") }
            .filter { it.isNotEmpty() && !Resolver.isIpLiteral(it) }.distinct()
        val ips = HashMap<String, String?>()
        if (hosts.size == 1) ips[hosts[0]] = Resolver.resolve(hosts[0])
        else if (hosts.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, hosts.size))
            try {
                val fut = hosts.associateWith { h -> pool.submit(java.util.concurrent.Callable { Resolver.resolve(h) }) }
                for ((h, f) in fut) ips[h] = try { f.get(6, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
            } finally { pool.shutdownNow() }
        }
        for ((o, spot) in spots) {
            val host = spot.optString("address")
            val ip = ips[host] ?: continue
            val ss = o.optJSONObject("streamSettings")
            val tls = ss?.optJSONObject("tlsSettings")
            val sni = tls?.optString("serverName").orEmpty()
            if (tls != null && sni.isEmpty()) tls.put("serverName", host)
            // Host — ТОЛЬКО если ни Host, ни SNI не заданы: тогда Xray взял бы
            // им адрес, а это теперь IP. При заданном SNI Xray сам берёт Host
            // из него — и так и надо. 10.11.2026: здесь подставлялся адрес, и у
            // нод Sirius (адрес api.ferrin.uk, SNI a5c76b20.ferrin.uk) Host
            // стал не тем: мелкие запросы проходили, «канал живой», а загрузка
            // стояла намертво — 2 МБ за 25 с: 0 байт (INCY на той же ноде — 646 КБ/с).
            if (sni.isEmpty()) for (k in listOf("wsSettings", "httpupgradeSettings", "xhttpSettings", "splithttpSettings")) {
                val t = ss?.optJSONObject(k) ?: continue
                val hdr = t.optJSONObject("headers")?.optString("Host").orEmpty()
                if (t.optString("host").isEmpty() && hdr.isEmpty()) t.put("host", host)
            }
            spot.put("address", ip)
        }
        when {
            hosts.isEmpty() -> {}
            hosts.size == 1 -> ips[hosts[0]]?.let { onLog("🔎 ${hosts[0]} → $it") }
                ?: onLog("⚠️ Не удалось разрешить ${hosts[0]} — Xray попробует сам")
            else -> onLog("🔎 Адреса серверов группы шифрованным DNS: ${ips.values.count { it != null }} из ${hosts.size}" +
                ips.filterValues { it == null }.keys.take(3).let { if (it.isEmpty()) "" else " (не нашлись: ${it.joinToString()})" })
        }
        if (ips.values.any { it != null }) obj.toString() else configJson
    } catch (t: Throwable) { configJson }

    // Нода за Cloudflare — отпечаток без постквантового ключа (см.
    // core/Cloudflare.kt). XHTTP через CDN — самый частый такой случай.
    // Зовётся после resolveServer: судим по адресу. Reality не трогаем.
    private fun cloudflareSafe(configJson: String, onLog: (String) -> Unit): String = try {
        val obj = JSONObject(configJson)
        val out = obj.getJSONArray("outbounds").getJSONObject(0)
        val addr = out.optJSONObject("settings")?.optJSONArray("vnext")
            ?.optJSONObject(0)?.optString("address").orEmpty()
        val tls = out.optJSONObject("streamSettings")?.optJSONObject("tlsSettings")
        val fp = tls?.optString("fingerprint").orEmpty()
        if (tls == null || !Cloudflare.owns(addr) || !Cloudflare.risky(fp)) configJson
        else {
            tls.put("fingerprint", Cloudflare.SAFE_FP)
            onLog("☁️ Нода за Cloudflare — отпечаток ${Cloudflare.SAFE_FP} вместо ${fp.ifEmpty { "стандартного" }}: " +
                "chrome к Cloudflare ТСПУ глушит")
            obj.toString()
        }
    } catch (_: Throwable) { configJson }

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

    // Hysteria2 через Xray: QUIC с паролем, Salamander и прыжки по портам —
    // маски finalmask; под бронёй сверху шум «как у Amnezia».
    private fun buildHysteria2(n: Node, armor: Boolean, bait: List<String>): JSONObject {
        val sni = n.sni.ifEmpty { n.addr }
        val tls = JSONObject().put("serverName", sni)
            .put("alpn", JSONArray(n.alpn.ifEmpty { "h3" }.split(",").map { it.trim() }))
        val masks = JSONArray()
        if (n.obfs.equals("salamander", true) && n.obfsPw.isNotEmpty())
            masks.put(JSONObject().put("type", "salamander")
                .put("settings", JSONObject().put("password", n.obfsPw)))
        // Диапазон портов, как у sing-box (см. ConfigBuilder): «20000-50000».
        val hop = n.hopPorts.split(",").mapNotNull { r ->
            val t = r.trim().replace(':', '-')
            if (Regex("^\\d+(-\\d+)?$").matches(t)) t else null
        }
        if (hop.isNotEmpty())
            masks.put(JSONObject().put("type", "udphop").put("settings", JSONObject()
                .put("mode", "intervalRemote").put("interval", "30-60")
                .put("remotePorts", hop.joinToString(","))))
        // Аварийным нодам шум не нужен по той же причине, что и нарезка
        // приветствия у VLESS: их сила — быть похожими на обычный трафик.
        if (armor && !n.wl) masks.put(AwgBait.noiseMask(bait))
        val stream = JSONObject().put("network", "hysteria")
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", n.auth))
            .put("security", "tls").put("tlsSettings", tls)
        if (masks.length() > 0) stream.put("finalmask", JSONObject().put("udp", masks))
        val proxy = JSONObject().put("tag", "proxy").put("protocol", "hysteria")
            .put("settings", JSONObject().put("version", 2).put("address", n.addr).put("port", n.port))
            .put("streamSettings", stream)
        val inbound = JSONObject()
            .put("tag", "socks-in").put("listen", "127.0.0.1").put("port", SOCKS_PORT)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(proxy)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
    }

    // КОНФИГ ПРОВАЙДЕРА ЦЕЛИКОМ — нода-группа (см. Node.raw).
    //
    // 09.11.2026: «АВТО ВЫБОР» Пантеры — 13 серверов с балансировщиком, а мы
    // брали первый (103.76.130.95:445). Tele2 его придушил — и две минуты
    // ни одного соединения, хотя двенадцать запасных были живы. INCY запускает
    // такой конфиг как есть, Xray сам меряет серверы и уходит с зависшего.
    //
    // Берём как есть выходы, маршруты с балансировщиком, обсерваторию,
    // политики. Меняем только то, что обязано быть нашим:
    //  - входы: один наш SOCKS (порт и пароль проставит withSocks); правила,
    //    ссылавшиеся на входы провайдера, перевешиваем на него;
    //  - api/stats/metrics/reverse/fakedns — служебные порты провайдера;
    //  - geoip:/geosite:/ext: — файлов .dat у нас нет, и Xray с такой ссылкой
    //    не стартует вовсе. Правило с гео-условием убираем ЦЕЛИКОМ: без этого
    //    условия оно стало бы шире задуманного («всё — напрямую»). Российское
    //    напрямую у нас и так решает sing-box до Xray;
    //  - журнал — info: по нему CoreTrail считает дозвоны.
    private val GEO_ITEM = Regex("^(geoip|geosite|ext|ext-ip|ext-domain):", RegexOption.IGNORE_CASE)
    private val GEO_ANY = Regex("\"(geoip|geosite|ext|ext-ip|ext-domain):", RegexOption.IGNORE_CASE)

    fun buildRaw(n: Node, armor: Boolean = false, fp: String = ""): JSONObject {
        val root = JSONObject(n.raw)
        if (armor) boostRaw(root, fp)
        val oldIns = root.optJSONArray("inbounds")
        val oldTags = (0 until (oldIns?.length() ?: 0))
            .mapNotNull { oldIns?.optJSONObject(it)?.optString("tag")?.ifEmpty { null } }.toSet()
        for (k in listOf("api", "stats", "metrics", "reverse", "fakedns", "remarks")) root.remove(k)
        root.put("log", JSONObject().put("loglevel", "info"))
        root.put("inbounds", JSONArray().put(JSONObject()
            .put("tag", "socks-in").put("listen", "127.0.0.1").put("port", SOCKS_PORT)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))))
        root.optJSONObject("routing")?.optJSONArray("rules")?.let { rules ->
            val kept = JSONArray()
            rules@ for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                if (rule.optString("outboundTag") == "api") continue
                val tags = rule.optJSONArray("inboundTag")
                if (tags != null) {
                    val mapped = (0 until tags.length()).map { tags.optString(it) }
                        .map { if (it in oldTags) "socks-in" else it }.distinct()
                    if (mapped.isEmpty() || mapped.all { it == "api" }) continue@rules
                    rule.put("inboundTag", JSONArray(mapped))
                }
                for (key in listOf("domain", "ip", "source")) {
                    val a = rule.optJSONArray(key) ?: continue
                    val left = (0 until a.length()).map { a.optString(it) }.filterNot { GEO_ITEM.containsMatchIn(it) }
                    if (left.isEmpty()) continue@rules
                    if (left.size < a.length()) rule.put(key, JSONArray(left))
                }
                kept.put(rule)
            }
            root.getJSONObject("routing").put("rules", kept)
        }
        // DNS провайдера с гео-ссылками Xray тоже не загрузит — без него.
        root.optJSONObject("dns")?.let { if (GEO_ANY.containsMatchIn(it.toString())) root.remove("dns") }
        return root
    }

    // Адреса серверов группы (до max) — для пинга: первый молчит, а группа
    // жива, пока отвечает хоть один.
    fun groupEndpoints(n: Node, max: Int = 4): List<Pair<String, Int>> = try {
        val outs = JSONObject(n.raw).optJSONArray("outbounds")
        val out = mutableListOf<Pair<String, Int>>()
        for (i in 0 until (outs?.length() ?: 0)) {
            val o = outs?.optJSONObject(i) ?: continue
            if (o.optString("protocol") !in PROXY) continue
            val s = o.optJSONObject("settings") ?: continue
            val v = s.optJSONArray("vnext")?.optJSONObject(0) ?: s.optJSONArray("servers")?.optJSONObject(0) ?: s
            val a = v.optString("address"); val p = v.optInt("port")
            if (a.isNotEmpty() && p > 0 && (a to p) !in out) out += a to p
            if (out.size >= max) break
        }
        out
    } catch (_: Throwable) { emptyList() }

    // БРОНЯ ДЛЯ ГРУППЫ ПРОВАЙДЕРА — то же, что для своих нод (см. buildConfig):
    //  - тяжёлый отпечаток (chrome/firefox/safari — приветствие с
    //    постквантовым ключом больше пакета) → наш лёгкий;
    //  - нарезка приветствия через выход freedom, если у провайдера своей нет.
    // Аварийные (белосписочные) группы сюда не попадают — им нужна похожесть
    // на обычный сайт, а не нарезка.
    private fun boostRaw(root: JSONObject, fp: String) {
        val outs = root.optJSONArray("outbounds") ?: return
        var needFrag = false
        val tags = (0 until outs.length()).mapNotNull { outs.optJSONObject(it)?.optString("tag") }.toSet()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") !in setOf("vless", "vmess", "trojan")) continue
            val st = o.optJSONObject("streamSettings") ?: continue
            for (k in listOf("realitySettings", "tlsSettings")) st.optJSONObject(k)?.let { s ->
                s.put("fingerprint", xrayFp(s.optString("fingerprint"), reality = k == "realitySettings"))
            }
            val net = st.optString("network", "tcp").lowercase()
            val sec = st.optString("security", "none").lowercase()
            if (net in setOf("kcp", "mkcp", "quic") || sec == "none") continue
            val so = st.optJSONObject("sockopt") ?: JSONObject().also { st.put("sockopt", it) }
            if (fragOn && so.optString("dialerProxy").isEmpty()) { so.put("dialerProxy", FRAG_TAG); needFrag = true }
        }
        if (needFrag && FRAG_TAG !in tags) outs.put(JSONObject().put("tag", FRAG_TAG).put("protocol", "freedom")
            .put("settings", JSONObject().put("fragment", JSONObject()
                .put("packets", "tlshello").put("length", "100-200").put("interval", "10-20"))))
    }

    // Сколько серверов в группе — для журнала.
    fun groupSize(n: Node): Int = try {
        val outs = JSONObject(n.raw).optJSONArray("outbounds")
        (0 until (outs?.length() ?: 0)).count { outs?.optJSONObject(it)?.optString("protocol") in PROXY }
    } catch (_: Throwable) { 0 }

    private val PROXY = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "tuic")

    fun buildConfig(n: Node, armor: Boolean, fp: String, viaDpi: Boolean = false,
                    frontDomain: String = "", bait: List<String> = emptyList()): JSONObject {
        if (n.raw.isNotEmpty()) return buildRaw(n, armor && !n.wlLike(), fp)
        if (n.type == "Hysteria2") return buildHysteria2(n, armor, bait)
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
        val useFp = xrayFp(if (armor && fp.isNotEmpty()) fp else n.fp, reality = n.pbk.isNotEmpty())
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
                    // Chrome — но 120-й, лёгкий: нынешний с постквантовым
                    // ключом весит 1,8 КБ и не влезает в один пакет, а Chrome
                    // ещё и тасует расширения — имя может уехать во второй
                    // кусок, где ТСПУ белых списков его уже не ищет.
                    t.put("fingerprint", xrayFp("chrome", reality = false))
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
        // Keepalive до сервера — как у sing-box (см. ConfigBuilder): 30 с / 15 с.
        val sockopt = JSONObject().put("tcpKeepAliveIdle", 30).put("tcpKeepAliveInterval", 15)
        if (viaDpi) {
            // Цепочка через ByeDPI: фрагментация ClientHello до того, как пакет
            // увидит оператор. dialerProxy — штатный способ Xray сцепить outbound'ы.
            sockopt.put("dialerProxy", "dpi")
        } else if (armor && !n.wlLike()) {
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
            //
            // С 3.7.9 правило шире флага wl (см. Node.wlLike): белосписочные
            // ноды из подписок и всё с российским SNI идут целым приветствием.
            // 11.11.2026 так же «поднимались, но не ехали» Белые списки Sirius.

            // НАРЕЗКА ПРИВЕТСТВИЯ — ЧЕРЕЗ ВЫХОД freedom, А НЕ В sockopt.
            //
            // До 3.7.7 здесь стояло sockopt.fragment/noises. Таких полей в
            // sockopt у Xray НЕТ — он их молча пропускал, и «броня» для VLESS
            // и Reality не резала ничего (журнал при этом писал «броня вкл»).
            // Проверено 09.11.2026 на Xray 26.10.10: со старым конфигом
            // ClientHello приходил на сервер одним куском 1721 байт, с
            // выходом freedom + dialerProxy — двенадцатью по 114–180 байт с
            // паузами 10–20 мс. Так же устроено у Happ и INCY, их
            // умолчание — 100–200 байт, 10–20 мс.
            //
            // 1721 байт — это chrome-приветствие с постквантовым ключом: оно
            // не влезает в один TCP-пакет, и ТСПУ на таких спотыкается
            // (Reality-ноды Триллера и Пантеры 09.11: линия чистая, а Xray из
            // 25–48 дозвонов открывал 3).
            if (fragOn) sockopt.put("dialerProxy", FRAG_TAG)
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

        // FINALMASK (Xray 26.3+) — маска поверх транспорта: нарезка
        // приветствия, шум в UDP, свои заголовки. Провайдер задаёт её в
        // ссылке (fm=…), мы кладём как есть: параметры должны совпасть с
        // сервером. Битый JSON — без маски, но нода не ломается.
        if (n.fm.isNotEmpty()) try { stream.put("finalmask", JSONObject(n.fm)) } catch (_: Throwable) {}

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
        // 10.11.2026: mux — по переключателю (Prefs.muxOn, по умолчанию выкл.):
        // восемь потоков в одном TCP — придушат его, и встанет всё сразу.
        if (muxOn && armor && stream.getString("network") != "xhttp" && n.flow.isEmpty()) {
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
        // Нарезчик — только если прокси на него сослался (броня, не аварийная
        // нода, не через ByeDPI). Умолчания как у INCY/Happ: приветствие
        // кусками по 100–200 байт с паузой 10–20 мс. Шумы freedom касаются
        // только UDP — пусть будут и для xudp.
        if (stream.optJSONObject("sockopt")?.optString("dialerProxy") == FRAG_TAG)
            outbounds.put(JSONObject().put("tag", FRAG_TAG).put("protocol", "freedom")
                .put("settings", JSONObject()
                    .put("fragment", JSONObject()
                        .put("packets", "tlshello").put("length", "100-200").put("interval", "10-20"))
                    .put("noises", JSONArray()
                        .put(JSONObject().put("type", "rand").put("packet", "10-20").put("delay", "10-16"))))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject()
                    .put("tcpKeepAliveIdle", 30).put("tcpKeepAliveInterval", 15))))

        return JSONObject()
            // info, а не warning: на warning ядро молчит о том, ПОЧЕМУ не идёт
            // трафик. В UI-лог всё равно попадает только важное — фильтр
            // стоит при чтении вывода процесса.
            .put("log", JSONObject().put("loglevel", "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
    }
}
