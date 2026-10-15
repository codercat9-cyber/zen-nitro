package com.zen.blaster.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.zen.blaster.MainActivity
import com.zen.blaster.core.AmneziaCore
import com.zen.blaster.core.Resolver
import com.zen.blaster.model.AwgProfile
import org.json.JSONObject

// ============================================================================
//  ZenAwgService — туннель на ядре AmneziaWG (WireGuard, AWG 1.0 … AWG 3).
//
//  Живёт в отдельном процессе (:awg), поэтому со Stats из UI-процесса общается
//  broadcast'ами, а не напрямую.
//
//  Запасное ядро старой ветки (AWG 1.x) — та же служба в своём процессе
//  (:awg1), см. ZenAwgLegacyService ниже. Если основное ядро не дождалось
//  рукопожатия, а конфиг старому ядру по силам, пробуем ещё раз на нём.
// ============================================================================
open class ZenAwgService : VpnService() {

    // Какое ядро у этого процесса. Переопределяет запасная служба.
    protected open val legacyCore = false
    private val slot get() = if (legacyCore) com.zen.blaster.core.CoreModules.AWG_LEGACY
                             else com.zen.blaster.core.CoreModules.AWG

    companion object {
        const val ACTION_START = "com.zen.blaster.AWG_START"
        const val ACTION_STOP = "com.zen.blaster.AWG_STOP"
        const val EXTRA_PROFILE = "profile"          // AwgProfile в JSON

        // Обратная связь в UI-процесс.
        const val BROADCAST = "com.zen.blaster.AWG_STATE"
        const val B_CONNECTED = "connected"
        const val B_STAGE = "stage"
        const val B_LOG = "log"
        const val B_UP = "up"
        const val B_DOWN = "down"
        const val B_HANDSHAKE = "handshake"
        const val B_PING = "ping"

        private const val CHANNEL = "zen_awg"
        private const val NOTIF_ID = 2

        // Остановить туннель Amnezia, на каком бы ядре он ни шёл. Запасную
        // службу будим, только если она и правда работает: иначе ради одной
        // команды «стоп» система подняла бы лишний процесс.
        fun stopAll(ctx: Context) {
            ctx.startService(Intent(ctx, ZenAwgService::class.java).apply { action = ACTION_STOP })
            if (com.zen.blaster.core.Prefs(ctx).awgLegacyRunning)
                ctx.startService(Intent(ctx, ZenAwgLegacyService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var statsRunning = false
    private var profile: AwgProfile? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AmneziaCore.legacy = legacyCore
        // Модуль ядра, если поставлен (см. CoreModules). Задаём до первой
        // загрузки библиотеки в этом процессе.
        com.zen.blaster.core.CoreModules.active(this, slot).let {
            AmneziaCore.modulePath = it?.path
            AmneziaCore.moduleVersion = it?.version
        }
        com.zen.blaster.core.Blackbox.initRemote(this)
        // Физическая сеть (для встряски радио и выбора сокета) ищется через
        // Resolver — в этом процессе его тоже надо привязать к контексту,
        // иначе physical() всегда пуст и встряска молча не срабатывает.
        Resolver.attach(this)
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        val prefs = com.zen.blaster.core.Prefs(this)
        val json = intent?.getStringExtra(EXTRA_PROFILE) ?: prefs.lastAwg
        if (json.isBlank()) {
            say(log = "⚠️ Профиль Amnezia не задан — импортируйте конфиг.")
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notif("Подключение…"))
        kotlin.concurrent.thread(name = "awg-start", isDaemon = true) { startBlocking(json) }
        return START_STICKY
    }

    private fun startBlocking(json: String) {
        try {
            // Новый старт поверх работающего ядра (смена профиля, повторное
            // нажатие) — сперва гасим прежнее. Иначе в процессе оставались
            // два живых устройства AWG, и старое продолжало слать пакеты.
            if (AmneziaCore.running || tunFd != null) {
                statsRunning = false
                unwatchNetwork()
                com.zen.blaster.core.RadioKeeper.stop()
                AmneziaCore.down()
                try { tunFd?.close() } catch (_: Exception) {}
                tunFd = null
                com.zen.blaster.core.Blackbox.abandon("переключение профиля")
            }
            val p = AwgProfile.fromJson(JSONObject(json))
            profile = p
            val coreName = if (legacyCore) "запасное ${AmneziaCore.label()} (AWG 1.x)"
                           else AmneziaCore.label()
            // Сеанс в журнале — как у sing-box и Xray. Раньше попытки Amnezia
            // в журнал не попадали вовсе: 14 октября 2026 от пяти провалов
            // осталось пять одинаковых строк «сервер не ответил» без единой
            // подробности, и причину пришлось искать в чужом приложении.
            com.zen.blaster.core.Blackbox.session(p.name, listOf(
                "сеть: ${com.zen.blaster.core.NetInfo.line(this)}",
                "нода: «${p.name}» [${p.badge()}] ${p.host}:${p.port}",
                "ядро: AmneziaWG $coreName",
                // Только НАЗВАНИЯ параметров: значения — это ключи и подпись
                // маскировки, журнал уходит наружу.
                "параметры: " + (p.junk.keys.joinToString(" ").ifEmpty { "нет (чистый WireGuard)" }) +
                    (if (p.keepaliveRange.isNotEmpty()) " keepalive-диапазон" else ""),
                "MTU ${p.mtu} · DNS ${p.dns.joinToString(", ").ifEmpty { "не задан" }}" +
                    (if (p.raw.isEmpty()) " · профиль из старой версии (без исходника)" else "")
            ))
            say(stage = "Поднимаю ядро ${p.badge()}…",
                log = "🚀 Ядро AmneziaWG ${AmneziaCore.version()} · ${p.badge()}" +
                    (if (AmneziaCore.loadedPath != null) " · модуль" else ""))
            // Модуль был задан, но не загрузился — работаем на встроенном и
            // засчитываем отказ модулю (после трёх подряд он выключится).
            if (AmneziaCore.modulePath != null && AmneziaCore.loadedPath == null) {
                say(log = "⚠️ Модуль ядра не загрузился (${AmneziaCore.moduleError.take(120)}) — работаю на встроенном")
                com.zen.blaster.core.CoreModules.report(this, slot, false, "не загрузился: ${AmneziaCore.moduleError.take(80)}")
            }

            // Имя сервера разрешаем сами: внутри Go-ядра системного резолвера
            // может не оказаться, а системный DNS оператор ещё и подменяет.
            say(stage = "Ищу сервер…")
            val ip = Resolver.resolve(p.host)
            if (ip == null) { fail("не удалось разрешить ${p.host}"); return }
            if (ip != p.host) say(log = "🔎 ${p.host} → $ip")

            val b = Builder()
            b.setSession("ZEN NITRO · ${p.badge()}")
            b.setMtu(p.mtu)
            for (a in p.addresses) {
                val slash = a.indexOf('/')
                val addr = if (slash < 0) a else a.substring(0, slash)
                val len = if (slash < 0) (if (addr.contains(':')) 128 else 32)
                          else a.substring(slash + 1).toIntOrNull() ?: 32
                try { b.addAddress(addr, len) } catch (_: Exception) {}
            }
            for (r in p.allowedIps) {
                val slash = r.indexOf('/')
                val addr = if (slash < 0) r else r.substring(0, slash)
                val len = if (slash < 0) (if (addr.contains(':')) 128 else 32)
                          else r.substring(slash + 1).toIntOrNull() ?: 0
                try { b.addRoute(addr, len) } catch (_: Exception) {}
            }
            for (d in p.dns) try { b.addDnsServer(d) } catch (_: Exception) {}
            // Раздельное туннелирование.
            for (pkg in p.includeApps) try { b.addAllowedApplication(pkg) } catch (_: Exception) {}
            for (pkg in p.excludeApps) try { b.addDisallowedApplication(pkg) } catch (_: Exception) {}

            val pfd = b.establish() ?: run { fail("нет разрешения VPN"); return }
            tunFd = pfd

            // Ядро забирает дескриптор себе — отдаём его detachFd(), иначе
            // ParcelFileDescriptor закроет его у нас под ногами.
            val fd = pfd.detachFd()
            val uapi = AmneziaCore.buildUapi(p, ip, legacyCore)
            say(stage = "Устанавливаю соединение…")
            val h = AmneziaCore.up("zen0", fd, uapi)
            if (h < 0) {
                // Причину говорит само ядро: «invalid UAPI device key: …»,
                // «S1 must be more then 12 to use headerProtection» и т.п.
                val why = AmneziaCore.lastError().ifEmpty { "без объяснений" }
                if (AmneziaCore.loadedPath != null &&
                    com.zen.blaster.core.CoreModules.report(this, slot, false, "не принял конфиг: ${why.take(80)}"))
                    say(log = "↩️ Модуль ядра трижды не принял конфиг — выключен, дальше встроенное")
                fail("ядро AmneziaWG не приняло конфиг: $why")
                return
            }

            // Сокет наружу обязан идти мимо туннеля, иначе трафик зациклится.
            for (s in AmneziaCore.sockets()) protect(s)

            if (!waitHandshake()) {
                if (!legacyCore && p.legacyCapable()) {
                    handOverToLegacy(json); return
                }
                fail("сервер не ответил на рукопожатие за 15 с" +
                    (if (p.raw.isEmpty() && p.badge() != "WireGuard")
                        " — профиль добавлен старой версией Zen и мог потерять параметры AWG 3: добавьте конфиг заново"
                     else " — сервер выключен, ключи не те или UDP до него режут"))
                return
            }
            com.zen.blaster.core.Blackbox.outcome(true, "рукопожатие прошло")
            if (AmneziaCore.loadedPath != null) com.zen.blaster.core.CoreModules.report(this, slot, true)
            if (legacyCore) com.zen.blaster.core.Prefs(this).awgLegacyRunning = true
            say(connected = true, stage = "")
            startStats()
            updateNotif(null)
        } catch (t: Throwable) {
            fail(t.message ?: "ошибка ядра")
        }
    }

    // Основное ядро не дозвалось сервера старой ветки — отдаём попытку
    // запасному. Туннель гасим полностью: VPN у Android один, и запасная
    // служба поднимет свой.
    @Volatile private var handedOver = false

    private fun handOverToLegacy(json: String) {
        handedOver = true
        say(log = "↻ Основное ядро не дозвалось — пробую запасное ядро AWG 1.x")
        com.zen.blaster.core.Blackbox.outcome(false, "не ответил на основном ядре — передаю запасному")
        statsRunning = false
        AmneziaCore.down()
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        try {
            androidx.core.content.ContextCompat.startForegroundService(this,
                Intent(this, ZenAwgLegacyService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PROFILE, json)
                })
        } catch (t: Throwable) {
            fail("запасное ядро не запустилось: ${t.message}")
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Хендшейк WireGuard молчаливый: факт соединения виден только по счётчикам
    // и времени последнего рукопожатия в UAPI-дампе.
    private fun waitHandshake(): Boolean {
        for (i in 1..30) {
            val cfg = AmneziaCore.config()
            val hs = cfg.lines().firstOrNull { it.startsWith("last_handshake_time_sec=") }
                ?.substringAfter('=')?.toLongOrNull() ?: 0
            if (hs > 0) { say(log = "✅ Рукопожатие прошло за ${"%.1f".format(i * 0.5)} с"); return true }
            val (rx, _) = AmneziaCore.traffic()
            if (rx > 0) return true
            if (i % 5 == 0) say(stage = "Жду ответа сервера… (${i / 5}/6)")
            Thread.sleep(500)
        }
        return false
    }

    // ПИНГ ЧЕРЕЗ ТУННЕЛЬ.
    //
    // У Amnezia его не было вовсе: пинг в Zen меряет сторож sing-box, а у
    // этой службы своего сторожа не было — в интерфейс уходила только
    // скорость, и на главном экране и в шторке вместо цифры стоял прочерк.
    //
    // Меряем время TCP-подключения к 1.1.1.1:80. Наш собственный трафик
    // Amnezia ведёт через туннель, так что это ровно один круг «телефон →
    // сервер AWG → Cloudflare → обратно», без данных и без DNS. Раз в 30
    // секунд; после неудачи — через 10.
    private fun pingOnce(): Int = try {
        val t0 = android.os.SystemClock.elapsedRealtime()
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("1.1.1.1", 80), 5000)
        }
        (android.os.SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
    } catch (_: Throwable) { -1 }

    @Volatile private var lastPing = -1

    // СТОРОЖ КАНАЛА AMNEZIA.
    //
    // 15.10.2026 канал Amnezia шесть раз молчал от полуминуты до пяти с
    // половиной минут — и не только при обрыве сети: в 09:08 сессия данных
    // жила, а туннель молчал четыре с половиной минуты и потом сам ожил.
    // Почерк фильтра, который держится за конкретный UDP-поток (адрес и
    // порт): новый поток он пропускает. Сторож при этом только писал «не
    // отвечает» и ждал.
    //
    // Теперь по ступеням, пока канал молчит и байты не идут:
    //   1-й провал  — встряска радио (модем мог уснуть или съехать на EDGE);
    //   2-й         — новый исходный порт: для оператора это новый поток;
    //   дальше      — новый порт раз в ~минуту тишины.
    // Полного переподъёма здесь нет сознательно: на плохой сети его
    // рукопожатие может не уложиться в 15 с, и тогда туннель погас бы
    // совсем — хуже, чем молчание. Само рукопожатие WireGuard повторяет
    // каждые 5 с, пока есть что слать, — ему нужен только живой путь.
    private fun startPing() {
        Thread({
            var misses = 0
            while (statsRunning) {
                val ms = pingOnce().let { if (it > 0) it else pingOnce() }
                lastPing = ms
                // В шторке — та же «прибитая» цифра, что у sing-box: лучшая
                // за полчаса, а не каждый замер (у этого процесса свой Stats).
                com.zen.blaster.core.Stats.feedPing(ms)
                say(ping = ms)
                if (ms > 0) {
                    if (misses >= 2) say(log = "✅ Канал Amnezia снова отвечает: $ms мс")
                    misses = 0
                } else if (rxMovingRecently()) {
                    // Замер не прошёл, а байты из туннеля идут — канал жив,
                    // просто служебный запрос встал в очередь на узком канале.
                    misses = 0
                } else {
                    misses++
                    if (misses == 1) com.zen.blaster.core.RadioKick.kick(this, "Amnezia молчит") { say(log = it) }
                    if (misses == 2 || (misses > 2 && (misses - 2) % 3 == 0))
                        renewPort("канал молчит ~${misses * 20} с")
                    if (misses == 3) {
                        say(log = "⚠️ Канал Amnezia не отвечает уже ~1 мин — сервер молчит или сеть пропала")
                        com.zen.blaster.core.Blackbox.note(
                            "⚠️ Amnezia «${profile?.name}»: канал молчит · " +
                            com.zen.blaster.core.NetInfo.line(this))
                    }
                }
                updateNotif(null)
                try { Thread.sleep(if (ms > 0) 30_000L else 10_000L) } catch (_: InterruptedException) { break }
            }
        }, "awg-ping").apply { isDaemon = true }.start()
    }

    // Когда из туннеля последний раз пришли байты (пишет поток статистики).
    @Volatile private var rxAt = 0L
    private fun rxMovingRecently() =
        rxAt > 0 && android.os.SystemClock.elapsedRealtime() - rxAt < 20_000L

    // Новый исходный порт — и сразу защитить новые сокеты от собственного
    // туннеля. Не чаще раза в 15 с: смена сети и сторож могут прийти разом.
    @Volatile private var renewedAt = 0L
    @Synchronized private fun renewPort(why: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - renewedAt < 15_000L) return
        renewedAt = now
        if (!AmneziaCore.rebind()) {
            say(log = "… сменить порт Amnezia не вышло (${AmneziaCore.lastError().ifEmpty { "старое ядро" }})")
            return
        }
        for (sock in AmneziaCore.sockets()) protect(sock)
        val msg = "🔀 Amnezia: новый порт — $why"
        say(log = msg)
        com.zen.blaster.core.Blackbox.note(msg)
    }

    // СМЕНА ФИЗИЧЕСКОЙ СЕТИ — СРАЗУ НОВЫЙ ПОРТ.
    //
    // За день 15.10 сессия данных пересоздавалась десятки раз: оператор
    // гасил её сам (NO_GPRS_CONTEXT, EMM_DETACHED), данные переезжали между
    // симками. Каждый раз сокет ядра оставался старым, и туннель ждал, пока
    // сторож заметит тишину. Теперь меняем порт в момент смены сети: пакеты
    // сразу уходят новым путём, а сервер узнаёт новый адрес из первого же.
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            @Volatile var current: android.net.Network? = null
            override fun onAvailable(network: android.net.Network) {
                val was = current
                current = network
                if (was != null && was != network && statsRunning)
                    kotlin.concurrent.thread(isDaemon = true) { renewPort("сеть сменилась") }
            }
        }
        val req = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(req, cb, h)
            else cm.requestNetwork(req, cb, h)
            netCb = cb
        } catch (_: Throwable) {}
    }

    private fun unwatchNetwork() {
        val cb = netCb ?: return
        netCb = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
    }

    private fun startStats() {
        if (statsRunning) return
        statsRunning = true
        com.zen.blaster.core.Stats.reset()       // окно «лучшего пинга» — с нуля
        rxAt = 0L
        startPing()
        watchNetwork()
        com.zen.blaster.core.RadioKeeper.start(this) { say(log = it) }
        Thread({
            var pRx = -1L; var pTx = -1L; var pT = 0L
            while (statsRunning) {
                try {
                    val (rx, tx) = AmneziaCore.traffic()
                    val now = System.currentTimeMillis()
                    if (pRx in 0 until rx) rxAt = android.os.SystemClock.elapsedRealtime()
                    if (pRx >= 0 && now > pT) {
                        val dt = (now - pT) / 1000.0
                        say(down = (((rx - pRx).coerceAtLeast(0)) / dt).toLong(),
                            up = (((tx - pTx).coerceAtLeast(0)) / dt).toLong())
                        updateNotif(null)
                    }
                    pRx = rx; pTx = tx; pT = now
                } catch (_: Throwable) {}
                Thread.sleep(1000)
            }
        }, "awg-stats").apply { isDaemon = true }.start()
    }

    private fun fail(msg: String) {
        say(log = "❌ $msg", stage = "")
        com.zen.blaster.core.Blackbox.outcome(false, msg)
        stop()
    }

    private fun stop() {
        com.zen.blaster.core.Blackbox.abandon("туннель остановлен")
        if (legacyCore) com.zen.blaster.core.Prefs(this).awgLegacyRunning = false
        statsRunning = false
        unwatchNetwork()
        com.zen.blaster.core.RadioKeeper.stop()
        AmneziaCore.down()
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        // Передали попытку запасному ядру — для интерфейса туннель не
        // «отключился», он переезжает. Иначе кнопка мигнула бы в «выкл».
        if (!handedOver) say(connected = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Запасное ядро нужно редко, а рантайм Go держит ~120 МБ памяти и
        // после остановки: процесс :awg1 так и висел в фоне. Гасим его сами.
        // Тот же приём, если сменили модуль ядра: библиотека уже сидит в этом
        // процессе, и подменить её можно только новым процессом.
        val stale = AmneziaCore.loadedPath != com.zen.blaster.core.CoreModules.active(this, slot)?.path
        if (legacyCore || stale) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { android.os.Process.killProcess(android.os.Process.myPid()) }, 1500)
    }

    override fun onDestroy() { stop(); super.onDestroy() }
    override fun onRevoke() { stop(); super.onRevoke() }

    // ------------------------------------------------------- связь с UI

    @Volatile private var lastUp = 0L
    @Volatile private var lastDown = 0L

    private fun say(connected: Boolean? = null, stage: String? = null, log: String? = null,
                    up: Long? = null, down: Long? = null, ping: Int? = null) {
        up?.let { lastUp = it }; down?.let { lastDown = it }
        // Флаг в настройках читают UI и плитка в шторке (мы в другом процессе).
        connected?.let { com.zen.blaster.core.Prefs(this).awgRunning = it }
        val i = Intent(BROADCAST).setPackage(packageName)
        connected?.let { i.putExtra(B_CONNECTED, it) }
        stage?.let { i.putExtra(B_STAGE, it) }
        log?.let { i.putExtra(B_LOG, it); android.util.Log.i("ZenAwg", it) }
        up?.let { i.putExtra(B_UP, it) }
        down?.let { i.putExtra(B_DOWN, it) }
        ping?.let { i.putExtra(B_PING, it) }
        try { sendBroadcast(i) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------- уведомление

    private fun notif(overrideText: String?): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "AmneziaWG", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
        }
        val pi = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val stopPi = android.app.PendingIntent.getService(this, 1,
            Intent(this, ZenAwgService::class.java).apply { action = ACTION_STOP },
            android.app.PendingIntent.FLAG_IMMUTABLE)

        val p = profile
        val text = overrideText ?: ("↓ ${fmt(lastDown)}   ↑ ${fmt(lastUp)}" +
            com.zen.blaster.core.Stats.shownPing.value.let { if (it > 0) "   · $it мс" else "" })
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(p?.name ?: "ZEN NITRO · AmneziaWG")
            .setContentText(text)
            .setSubText(p?.badge() ?: "AmneziaWG")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Отключить", stopPi)
            .build()
    }

    private fun fmt(b: Long): String = when {
        b >= 1024 * 1024 -> "%.1f МБ/с".format(b / 1048576.0)
        b >= 1024 -> "%.0f КБ/с".format(b / 1024.0)
        else -> "$b Б/с"
    }

    private fun updateNotif(text: String?) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notif(text))
        } catch (_: Throwable) {}
    }
}

// Запасное ядро старой ветки AWG 1.x (libwg1.so) — в своём процессе :awg1.
// Два рантайма Go в одном процессе не уживаются, поэтому не «переключатель
// ядра», а отдельная служба.
class ZenAwgLegacyService : ZenAwgService() {
    override val legacyCore = true
}
