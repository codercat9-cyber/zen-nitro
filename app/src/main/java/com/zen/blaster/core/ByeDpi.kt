package com.zen.blaster.core


// ============================================================================
//  ByeDPI — локальный SOCKS5 с десинхронизацией (аналог Zapret2, но без рута).
//
//  У нас он работает ТОЛЬКО как SOCKS-провайдер на 127.0.0.1:1080.
//  Единственный TUN держит sing-box — поэтому конфликта с VPN нет
//  (в оригинальном ByeByeDPI конфликт как раз из-за собственного TUN).
// ============================================================================
object ByeDpi {

    // Порт щита. Был зашит намертво — и это стоило нам подъёмов: прошлый
    // экземпляр ещё держит сокет, новый молча падает («ByeDPI завершился
    // (код 0)»), а нода идёт ЧЕРЕЗ щит и остаётся без выхода. Симптом при
    // этом самый обманчивый: сервер жив, отвечает на пинг, а туннель не
    // встаёт. Теперь берём свободный, как для остальных наших портов.
    private const val FIRST = 1080
    private const val LAST = 1100

    @Volatile
    var port: Int = FIRST
        private set

    fun claim(): Int {
        for (p in FIRST..LAST) if (ClashApi.free(p, reuse = true)) { port = p; return p }
        port = FIRST
        return FIRST
    }

    @Deprecated("Порт больше не постоянный — берите ByeDpi.port")
    const val PORT = 1080

    data class Strategy(val name: String, val args: List<String>)

    // Стратегии от мягкой к самой агрессивной.
    // -Kt = применять к TLS, -An/-As/-Am = авто-режимы, -s/-d/-o = split/disorder/oob,
    // -f = fake с TTL, -Ft = фейковый TLS ClientHello, -e = split по SNI.
    val STRATEGIES = listOf(
        Strategy("Бронебойная (авто)",        listOf("-Kt", "-An", "-s1+s", "-d1")),
        Strategy("Классическая (split SNI)",  listOf("-Kt", "-As", "-o1")),
        Strategy("Агрессивная (fake+split)",  listOf("-Kt", "-Ft", "-s2", "-d1", "-o1")),
        Strategy("Мульти-сплит",              listOf("-Kt", "-s1", "-s2", "-d1", "-o1")),
        Strategy("Мягкая (split method)",     listOf("-Kt", "-Am", "-s1")),
        Strategy("Максимальная (fake TTL)",   listOf("-Kt", "-Ft", "-f-1", "-s1+s", "-d1", "-o1"))
    )

    @Volatile var running: Boolean = false; private set

    @Volatile private var app: android.content.Context? = null
    @Volatile private var conn: android.content.ServiceConnection? = null
    @Volatile private var intent: android.content.Intent? = null
    @Volatile private var log: (String) -> Unit = {}

    // Перезапуски после падений — с пределом. Чужой C-код может падать
    // по кругу на одном и том же соединении; поднимать его бесконечно значит
    // жечь батарею и засыпать журнал. Пять раз за десять минут — и хватит.
    private val restarts = ArrayDeque<Long>()
    private const val RESTART_WINDOW = 10 * 60_000L
    private const val RESTART_MAX = 5

    // Запустить щит. Сам он живёт в отдельном процессе (vpn/DpiService) —
    // почему, подробно там. Здесь только привязка и ожидание порта.
    fun start(ctx: android.content.Context, strategyIndex: Int, onLog: (String) -> Unit) {
        if (running) return
        claim()
        val s = STRATEGIES.getOrElse(strategyIndex) { STRATEGIES[0] }
        val a = ctx.applicationContext
        app = a
        log = onLog
        restarts.clear()
        onLog("🛡️ DPI-щит (ByeDPI): ${s.name}")
        // ВАЖНО: ByeDPI ждёт СКЛЕЕННЫЕ короткие опции ("-i127.0.0.1", "-p1080"),
        // раздельные ("-i", "127.0.0.1") парсер отвергает → возврат -1.
        val args = (listOf("ciadpi", "-i127.0.0.1", "-p$port") + s.args).toTypedArray()
        intent = android.content.Intent(a, com.zen.blaster.vpn.DpiService::class.java)
            .putExtra(com.zen.blaster.vpn.DpiService.EXTRA_ARGS, args)
        running = true
        bind()
        // Ждём, пока щит начнёт принимать соединения: ядро пойдёт в его порт
        // сразу после нас. Раньше здесь была пауза вслепую на 400 мс.
        if (!waitPort()) onLog("⚠️ DPI-щит не открыл порт $port за 3 с")
    }

    private fun waitPort(): Boolean {
        for (i in 1..40) {
            if (!ClashApi.free(port, reuse = true)) return true
            Thread.sleep(75)
        }
        return false
    }

    private fun bind() {
        val a = app ?: return
        val i = intent ?: return
        val c = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {}
            // Процесс щита умер, а привязку мы не снимали — значит упал.
            // Туннель при этом жив: раньше в этот момент умирало всё
            // приложение вместе с ним.
            //
            // Сам Android такую привязку НЕ восстанавливает — проверено на
            // телефоне 1 октября 2026: после падения процесс щита не вернулся
            // и через полминуты, а привязка висела помеченной DEAD. Поэтому
            // поднимаем сами.
            override fun onServiceDisconnected(n: android.content.ComponentName?) = revive(this)
            override fun onBindingDied(n: android.content.ComponentName?) = revive(this)
        }
        conn = c
        a.bindService(i, c,
            android.content.Context.BIND_AUTO_CREATE or android.content.Context.BIND_IMPORTANT)
    }

    @Synchronized
    private fun revive(dead: android.content.ServiceConnection) {
        // Падение уже обработано (пришли оба вызова) или щит выключают сами.
        if (!running || conn !== dead) return
        conn = null
        try { app?.unbindService(dead) } catch (_: Throwable) {}

        val now = android.os.SystemClock.elapsedRealtime()
        while (restarts.isNotEmpty() && now - restarts.first() > RESTART_WINDOW) restarts.removeFirst()
        if (restarts.size >= RESTART_MAX) {
            running = false
            log("❌ DPI-щит падает раз за разом — оставляю его выключенным.")
            log("   Туннель работает и без него. Смените стратегию щита в настройках.")
            Blackbox.note("❌ DPI-щит упал $RESTART_MAX раз за 10 минут — выключен до переподключения")
            return
        }
        restarts.addLast(now)
        log("⚠️ DPI-щит упал — туннель жив, поднимаю щит заново")
        Blackbox.note("⚠️ процесс DPI-щита упал; туннель не пострадал, щит перезапускается")
        // Щит встаёт на тот же порт: на него уже смотрит конфиг ядра.
        kotlin.concurrent.thread(name = "dpi-revive", isDaemon = true) {
            Thread.sleep(500)
            if (!running) return@thread
            bind()
            if (waitPort()) log("✅ DPI-щит снова в строю")
        }
    }

    fun stop() {
        val c = conn
        running = false
        conn = null
        if (c != null) try { app?.unbindService(c) } catch (_: Throwable) {}
    }
}
