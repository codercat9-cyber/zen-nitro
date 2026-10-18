package com.zen.blaster.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.zen.blaster.MainActivity
import com.zen.blaster.core.ByeDpi
import com.zen.blaster.core.Stats
import com.zen.blaster.core.XrayEngine
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File

// ============================================================================
//  ZenVpnService — VpnService + встроенное ядро sing-box (libbox).
//  Реализация PlatformInterface сверена с реально собранным libbox.aar
//  (io.nekohasekai.libbox, javap-контракт).
// ============================================================================
class ZenVpnService : VpnService(), PlatformInterface {

    companion object {
        const val ACTION_START = "com.zen.blaster.START"
        const val ACTION_STOP = "com.zen.blaster.STOP"
        // Пометить момент в журнале отладки — кнопкой из шторки.
        const val ACTION_MARK = "com.zen.blaster.MARK"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_DPI = "dpi"            // включать ли ByeDPI
        const val EXTRA_DPI_STRATEGY = "dpiStrategy"
        const val EXTRA_XRAY = "xrayConfig"    // конфиг Xray, если нода его требует
        private const val CHANNEL = "zen_vpn"
        private const val NOTIF_ID = 1

        @Volatile var running: Boolean = false; private set

        // Kill switch держит трафик закрытым: туннеля нет, но и наружу
        // ничего не уходит. Интерфейсу нужно это показать — иначе человек
        // видит «нет интернета» и не понимает, почему.
        @Volatile var blocked: Boolean = false

        var onLog: ((String) -> Unit)? = null
        var onState: ((Boolean) -> Unit)? = null
        // Сервер отказал ядру sing-box в рукопожатии Reality — подъёму наверху
        // нет смысла ждать таймаута, второе ядро можно пробовать сразу.
        @Volatile var onCoreReject: ((String) -> Unit)? = null
        // Канал этой попытки уже доказан (замер через ядро прошёл), идут
        // только дополнительные проверки. Таймер отката наверху в этот
        // момент рвать туннель не должен.
        @Volatile var channelProven = false
    }

    private var boxService: BoxCore? = null

    // ОСТАНОВКА ЯДРА sing-box НЕ ДОЛЖНА ВЕШАТЬ ВЫЗЫВАЮЩИЙ ПОТОК.
    //
    // BoxService.close() ждёт, пока закроются все соединения, и на мёртвом
    // канале это секунды, а то и навсегда. Системный архив телефона за
    // 12.10.2026 — три зависания интерфейса («не отвечает»), и во всех трёх
    // главный поток стоял именно здесь: «отключить» звал close() прямо на
    // нём. Сервис живёт в одном процессе с экраном, поэтому замерзал весь
    // экран — отсюда «кнопки пинг, проверка, обновить срываются».
    //
    // Теперь ядро закрывается в своём потоке; ждём его не дольше waitMs
    // (с главного потока — не ждём вовсе). Зависшее ядро бросаем: новый
    // подъём всё равно берёт свободные порты.
    private fun takeBox(): BoxCore? { val b = boxService; boxService = null; return b }

    private fun closeBox(b: BoxCore?, waitMs: Long) {
        b ?: return
        // Сервер управления закрываем сразу и здесь же: это быстро, а уходящий
        // сервер удаляет файл своего сокета — пусть сделает это до того, как
        // следующий запуск заведёт свой (см. BoxCore.detach).
        b.detach()
        val t = kotlin.concurrent.thread(name = "box-close", isDaemon = true) {
            try { b.close() } catch (_: Throwable) {}
        }
        if (waitMs > 0) try { t.join(waitMs) } catch (_: InterruptedException) {}
    }
    private var tunFd: ParcelFileDescriptor? = null
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    // Номер поколения туннеля. Подъём ядра идёт в фоновом потоке и длится
    // секунды (проверки канала, DNS, обхода). Если за это время пользователь
    // сменил сервер, старый поток продолжал работу: перерисовывал уведомление
    // именем ПРОШЛОГО сервера, писал «сервер не отвечает» и мог погасить уже
    // поднятый новый туннель. Теперь каждый старт помнит своё поколение и,
    // увидев чужое, молча выходит.
    private val epoch = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var statsRunning = false
    @Volatile private var xrayConfig: String? = null
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()
    // Один замер живости через ядро. -1, если не ответило.
    private fun probeOnce(proxyTag: String, budget: Int): Int = try {
        val enc = java.net.URLEncoder.encode(anyProbe(), "UTF-8")
        val req = com.zen.blaster.core.ClashApi.req("/proxies/$proxyTag/delay" +
            "?url=$enc&timeout=$budget").build()
        // Своё ожидание тоже растягиваем: ядру дали больше времени, а обрывали
        // его по прежнему сроку — ответ не успевал прийти.
        probeClient(budget.toLong()).newCall(req).execute().use { resp ->
            org.json.JSONObject(resp.body?.string().orEmpty()).optInt("delay", -1)
        }
    } catch (t: Throwable) {
        android.util.Log.w("ZenStats", "ping: ${t.message}")
        -1
    }

    // Клиент под КОНКРЕТНЫЙ замер.
    //
    // Общий http живёт с readTimeout 6 с, и этого хватало, чтобы обрубать
    // собственные проверки раньше, чем ядро успеет ответить. Ядру даём его
    // бюджет, себе — тот же бюджет с запасом на разбор ответа.
    // newBuilder() наследует пул соединений и диспетчер, поэтому это дёшево.
    private fun probeClient(budgetMs: Long) = http.newBuilder()
        .readTimeout(budgetMs + 3_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(budgetMs + 5_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Всё, что ядро говорит человеку, одновременно ложится в чёрный ящик.
    //
    // Раньше сообщение уходило только в onLog, то есть в список на вкладке
    // LOGS, — а он живёт в памяти открытого приложения. Между тем ядро чаще
    // всего работает как раз тогда, когда приложение закрыто: обрывы, kill
    // switch, переподключения. Именно эти строки и надо разбирать потом.
    private fun say(msg: String) {
        com.zen.blaster.core.Blackbox.event(msg)
        onLog?.invoke(msg)
    }

    // То же, но для потока логов самих ядер: там сотни строк в минуту, и в
    // файл идёт лишь то, в чём есть жалоба (см. Blackbox.core).
    private fun sayCore(msg: String) {
        com.zen.blaster.core.Blackbox.core(msg)
        // На экран — без терминальной раскраски: иначе вместо «ERROR» человек
        // читает «[31mERROR[0m» и принимает 31 за код ошибки.
        val clean = com.zen.blaster.core.Blackbox.readable(msg)
        onLog?.invoke(clean)
        catchCertMismatch(clean)
        catchDeadInterface(clean)
        if (!realityRejectSaid && clean.contains("reality verification failed")) {
            realityRejectSaid = true
            onCoreReject?.invoke("reality")
        }
    }
    @Volatile private var realityRejectSaid = false

    // СТРАХОВКА: ЯДРО ЗВОНИТ ЧЕРЕЗ ИНТЕРФЕЙС, КОТОРОГО БОЛЬШЕ НЕТ.
    //
    // Монитор сети (startDefaultInterfaceMonitor) сообщает ядру о смене
    // интерфейса. Но если сообщение всё же разминётся с ядром, оно залипает
    // на исчезнувшем rmnet_dataN и валит каждое соединение: «dial
    // rmnet_data3: network is unreachable» десятки раз в секунду — так было
    // 14.10.2026 в 18:47 на МТС. Человек при этом сидит без связи, пока не
    // нажмёт кнопку сам. Ловим пачку таких строк про интерфейс, которого в
    // системе уже нет, и переподнимаем туннель сами — не чаще раза в минуту.
    private val deadIfaceHits = ArrayDeque<Long>()
    @Volatile private var deadIfaceFixAt = 0L
    private fun catchDeadInterface(line: String) {
        if (!line.contains("network is unreachable") && !line.contains("no available network interface")) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(deadIfaceHits) {
            deadIfaceHits.addLast(now)
            while (deadIfaceHits.isNotEmpty() && now - deadIfaceHits.first() > 5_000) deadIfaceHits.removeFirst()
            if (deadIfaceHits.size < 15 || now - deadIfaceFixAt < 60_000 || !everUp) return
            deadIfaceHits.clear()
        }
        val iface = Regex("dial (\\S+) \\(").find(line)?.groupValues?.get(1)
        val live = com.zen.blaster.core.Resolver.physical()?.let { cm.getLinkProperties(it)?.interfaceName }
        if (iface != null && live != null && iface == live) return   // интерфейс жив — дело не в нём
        deadIfaceFixAt = now
        say("🔁 Ядро звонит через пропавший интерфейс ${iface ?: "?"} (сейчас ${live ?: "сети нет"}) — переподнимаю туннель")
        com.zen.blaster.core.Blackbox.note("🔁 ядро залипло на интерфейсе ${iface ?: "?"}, живой — ${live ?: "нет"}: переподъём")
        reconnect()
    }

    // СЕРВЕР ПРЕДСТАВЛЯЕТСЯ НЕ ТЕМ ИМЕНЕМ, КОТОРОЕ ДАЛА ПОДПИСКА.
    //
    // Ядро повторяет эту жалобу десятки раз в секунду, а человек видит стену
    // одинаковых строк и ничего в ней не понимает. Ловим её один раз, говорим
    // по-русски и запоминаем ноду: со следующей попытки проверку внешнего
    // имени для неё снимем, и она поднимется.
    @Volatile private var certSaid = false
    private fun catchCertMismatch(line: String) {
        if (certSaid || !line.contains("certificate is valid for")) return
        // Ошибка сертификата DNS-РЕЗОЛВЕРА — не беда ноды. 14.10.2026 с
        // ControlD по неверному адресу каждая строка «dns: exchange failed …
        // certificate is valid for 76.76.2.22…» снимала проверку имени с
        // ни в чём не повинной ноды. Нода — это outbound-соединение, а не DNS.
        if (line.contains("dns:") || line.contains("exchange failed") ||
            line.contains("process packet")) { dnsCertWarn(line); return }
        certSaid = true
        // Ядро перечисляет все имена из сертификата, а строку журнала мы
        // обрезаем — получалось «*.iperf.uk, i». Берём первое имя целиком.
        val real = line.substringAfter("certificate is valid for ")
            .substringBefore(',').take(60).trim().trimEnd('.', ' ')
        val want = line.substringAfter("Post \"https://").substringBefore('/')
            .ifEmpty { Stats.server.value }
        say("🔐 Сервер представляется как $real, а подписка ведёт на $want —")
        say("   это поломка у провайдера. Снимаю проверку имени для этой ноды:")
        say("   данные внутри всё равно зашифрованы ключами ноды.")
        com.zen.blaster.core.Blackbox.note(
            "🔐 «${Stats.server.value}»: сертификат на $real вместо $want — " +
            "снимаю проверку внешнего имени")
        // Помечаем по адресу, а не по имени: имя провайдер меняет когда
        // захочет, и пометка перестаёт относиться к той же ноде.
        val prefs = com.zen.blaster.core.Prefs(this)
        prefs.rememberCertMismatch(prefs.selectedKey.substringBefore('|'))
    }

    // Резолвер внутри туннеля отвечает сертификатом не на тот адрес — значит,
    // в туннеле не разрешается НИ ОДНО имя: канал живой, а сайты не
    // открываются. Говорим человеку прямо и один раз за сеанс.
    @Volatile private var dnsCertSaid = false
    private fun dnsCertWarn(line: String) {
        if (dnsCertSaid) return
        dnsCertSaid = true
        val ip = Regex("not ([0-9a-fA-F.:]+)").find(line)?.groupValues?.get(1) ?: "?"
        say("⚠️ DNS-резолвер $ip отвечает чужим сертификатом — имена в туннеле не разрешаются.")
        say("   Смените резолвер: Настройки → Резолвер DNS → «Проверить все».")
    }

    // Сколько туннель продержался до обрыва. «Сорвалось через 8 секунд» и
    // «сорвалось через два часа» — это две разные болезни: первое про ноду и
    // блокировку, второе про сеть, сон модема или оператора.
    private fun uptimeText(): String {
        val since = Stats.since.value
        if (since <= 0) return "неизвестно сколько"
        val s = (System.currentTimeMillis() - since) / 1000
        return when {
            s < 60 -> "$s с"
            s < 3600 -> "${s / 60} мин ${s % 60} с"
            else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.zen.blaster.core.Blackbox.init(this)
        com.zen.blaster.core.ConfigBuilder.deviceFp = com.zen.blaster.core.Prefs(this).deviceFingerprint()
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (intent?.action == ACTION_MARK) {
            // Метка ставится вместе с обстановкой: что за нода, что с каналом,
            // какая сеть. Через сутки «тут отвалилось» без этого не разобрать.
            com.zen.blaster.core.Blackbox.mark(
                "${Stats.server.value.ifEmpty { "без сервера" }} · " +
                (if (Stats.ping.value >= 0) "пинг ${Stats.ping.value} мс" else "пинга нет") +
                " · ${com.zen.blaster.core.NetInfo.line(this)}")
            say("★ Момент помечен в журнале — ищите ★★★ по времени")
            // Кнопка живёт в уведомлении работающего туннеля, так что обычно мы
            // здесь при живом сервисе — тогда просто ничего не трогаем. Но если
            // метку прислали в неработающий сервис, оставаться поднятым нельзя:
            // сервис без startForeground система убьёт с падением.
            if (!running) { stopSelf(); return START_NOT_STICKY }
            return START_STICKY
        }

        // intent может прийти пустым: система сама поднимает сервис в режиме
        // «Постоянная VPN» (always-on) или после перезапуска. Тогда берём
        // последний рабочий конфиг из настроек.
        val prefs = com.zen.blaster.core.Prefs(this)
        val config = intent?.getStringExtra(EXTRA_CONFIG) ?: prefs.lastConfig
        if (config.isBlank()) {
            say("⚠️ Нет сохранённого конфига — откройте приложение и подключитесь один раз.")
            stopSelf()
            return START_NOT_STICKY
        }
        val dpi = intent?.getBooleanExtra(EXTRA_DPI, prefs.lastDpi) ?: prefs.lastDpi
        val strat = intent?.getIntExtra(EXTRA_DPI_STRATEGY, prefs.dpiStrategy) ?: prefs.dpiStrategy
        xrayConfig = intent?.getStringExtra(EXTRA_XRAY) ?: prefs.lastXray.ifEmpty { null }
        // Резолверу нужен доступ к списку сетей: имя ноды разрешается ровно в
        // тот момент, когда системный DNS уже смотрит в наш недоделанный
        // туннель. Подъём из плитки или always-on идёт мимо приложения, поэтому
        // привязку повторяем и здесь.
        com.zen.blaster.core.Resolver.attach(this)
        com.zen.blaster.core.Resolver.preferred = prefs.preDnsUrl()
        // Авто-сброс DNS-кеша при смене сети: ТСПУ отравляет кеш, и при
        // переходе Wi-Fi → сотовая мусор едет с собой.
        com.zen.blaster.core.DnsCacheManager.onLog = { msg -> say(msg) }
        com.zen.blaster.core.DnsCacheManager.setAutoFlush(prefs.autoFlushDns)
        com.zen.blaster.core.DnsCacheManager.startWatching(this)
        // Запуск из плитки или always-on идёт мимо приложения, и Stats.server
        // остаётся пустым — в шторке висело безликое «ZEN NITRO». Подставляем
        // имя ноды из настроек, если его никто не выставил.
        if (Stats.server.value.isEmpty() && prefs.lastServerName.isNotEmpty())
            Stats.server.value = prefs.lastServerName
        // Подъём мимо приложения — из плитки в шторке или системой (always-on,
        // перезагрузка). Обстановку записать больше некому: приложения нет.
        if (!com.zen.blaster.core.Blackbox.inSession)
            com.zen.blaster.core.Blackbox.session(
                prefs.lastServerName.ifEmpty { "(сервер из настроек)" },
                listOf(
                    "подъём мимо приложения: плитка, always-on или перезагрузка",
                    "сеть: ${com.zen.blaster.core.NetInfo.line(this)}",
                    "нода: «${prefs.lastServerName}» · ядро: " +
                        (if (xrayConfig != null) "Xray + sing-box" else "sing-box") +
                        " · DPI-щит: ${if (dpi) "вкл" else "выкл"}"
                )
            )
        startVpn(config, dpi, strat)
        return START_STICKY
    }

    private fun startVpn(config: String, dpi: Boolean, dpiStrategy: Int) {
        // ВАЖНО: флаг взводим СИНХРОННО, до ухода в поток.
        // Раньше проверялся `running`, который выставляется уже внутри фонового
        // потока, — два быстрых нажатия успевали проскочить оба и поднимали два
        // ядра. Android отзывал первый TUN, onRevoke закрывал дескриптор
        // второго, интерфейс исчезал, а ядро продолжало жить и рапортовать
        // «Подключено» — весь трафик при этом шёл мимо туннеля, с настоящим IP.
        certSaid = false
        dnsCertSaid = false
        realityRejectSaid = false
        channelProven = false
        starting.set(true)
        // Поколение поднимаем ПЕРВЫМ делом: с этой секунды любой поток прошлого
        // подъёма видит, что он больше не нужен, и не лезет ни в уведомление,
        // ни в состояние.
        val gen = epoch.incrementAndGet()
        startForeground(NOTIF_ID, notif("Подключение…"))
        // ВАЖНО: подъём ядра (Libbox.newService/start, ByeDPI) — тяжёлый и
        // блокирующий. Сервис живёт в том же процессе, что и UI, поэтому на
        // главном потоке он замораживает анимацию кольца. Уводим в фон.
        kotlin.concurrent.thread(name = "zen-start", isDaemon = true) {
            // СМЕНА СЕРВЕРА — ЭТО ПОСЛЕДОВАТЕЛЬНОСТЬ, А НЕ ГОНКА.
            //
            // Раньше брошенный поток просто выходил по проверке поколения,
            // оставив своё ядро жить. Новое ядро натыкалось на занятый порт
            // clash_api, ждало его до шести секунд, не дожидалось и стартовало
            // поверх — два экземпляра дрались за один порт, а человек эти
            // секунды просто сидел и смотрел на кольцо.
            quiesce()
            if (!alive(gen)) return@thread
            startVpnBlocking(config, dpi, dpiStrategy, gen)
        }
    }

    // Погасить прошлое ядро целиком и убедиться, что порт освободился.
    //
    // Быстро: закрываем всё разом и ждём порт короткими шагами, а не ровными
    // паузами по 200 мс. В обычном случае укладывается в десятки миллисекунд.
    private fun quiesce() {
        closeBox(takeBox(), 4_000)
        try { ByeDpi.stop() } catch (_: Throwable) {}
        try { XrayEngine.stop() } catch (_: Throwable) {}
        running = false
        // Ждём ТОТ порт, который новому экземпляру действительно нужен.
        //
        // Здесь была ошибка ценой в три секунды на каждой смене сервера, и
        // ровно она отвечает за жалобу «опять предыдущее ядро не отпустило
        // порт»: код ждал освобождения 9095 — номера, от которого мы давно
        // отказались. Порт clash_api с тех пор берётся свободным (ClashApi
        // .claim), и ждать его не нужно вовсе: занят 9095 — возьмём 9096.
        // Сообщение в журнале ругалось на порт, до которого никому нет дела,
        // а человек эти секунды смотрел на кольцо.
        //
        // SOCKS Xray с 3.4 на случайном порту — ждать его освобождения не
        // нужно вовсе. Но брошенный прошлый Xray всё равно гасим: он жрёт
        // память и продолжает ходить в старую ноду.
        if (xrayConfig == null) return
        val n = com.zen.blaster.core.XrayEngine.reapOrphans()
        if (n > 0) say("♻️ Погасил брошенный Xray от прошлого запуска ($n)")
    }

    // Наше ли ещё поколение? Если нет — этот поток уже никому не нужен.
    private fun alive(gen: Int) = epoch.get() == gen

    private fun startVpnBlocking(config: String, dpi: Boolean, dpiStrategy: Int, gen: Int) {
        try {
            // Кеш адресов перед подъёмом НЕ сбрасываем: в нём лежат только
            // ответы шифрованного DoH, а их не подменить (см. Resolver.resolve).
            // Сброс сделал бы кеш бесполезным — каждый подъём заново ходил бы
            // за адресом ноды.

            // DPI-щит поднимаем ПЕРВЫМ: ядро сразу пойдёт через его SOCKS.
            // Ошибку ByeDPI НЕ считаем фатальной — VPN должен подняться и без него.
            if (dpi) {
                try { ByeDpi.start(this, dpiStrategy) { msg -> say(msg) } }
                catch (t: Throwable) { say("❌ ByeDPI не стартовал: ${t.message}") }
            }

            // Xray-ядро: нужно для транспортов, которых нет в sing-box (XHTTP, kcp).
            // Поднимаем ДО sing-box — тот сразу пойдёт в его SOCKS 127.0.0.1:10809.
            // Отдельный процесс, потому что два gomobile-рантайма в одном не живут.
            xrayConfig?.let { cfg ->
                Stats.stage.value = "Поднимаю ядро Xray…"
                say("🚀 Ядро Xray ${XrayEngine.version(this)}" +
                    (if (com.zen.blaster.core.CoreModules.active(this, com.zen.blaster.core.CoreModules.XRAY) != null)
                        " (модуль)" else "") + " — поднимаю…")
                if (!XrayEngine.start(this, cfg) { msg -> sayCore(msg) }) {
                    if (!alive(gen)) return
                    // Без Xray нода просто не заработает — честно падаем, а не
                    // делаем вид, что подключились в никуда.
                    say("❌ Xray не стартовал: ${XrayEngine.lastError}")
                    com.zen.blaster.core.Blackbox.outcome(false,
                        "ядро Xray не поднялось: ${XrayEngine.lastError.take(160)}")
                    Stats.stage.value = ""
                    stopVpn()
                    return
                }
                say("✅ Xray слушает SOCKS ${XrayEngine.port} (с паролем)")
            }

            if (!alive(gen)) return          // сервер уже сменили — уходим тихо

            // Управляющий порт ядра берём СВОБОДНЫЙ, а не зашитый.
            //
            // При смене сервера старое ядро гасится и тут же поднимается новое,
            // а Go-рантайм отпускает сокет clash_api когда сочтёт нужным —
            // иногда через секунды. Пока номер был один на всех, новый
            // экземпляр либо ждал его впустую, либо стартовал с чужим API:
            // ядро вроде живое, а проверка канала и статистика уходили к
            // ОСТАТКАМ прежнего. Теперь драться не за что.
            val port = com.zen.blaster.core.ClashApi.claim()
            com.zen.blaster.core.LocalProbe.claim()
            // upgrade — первым: конфиг мог быть сохранён прошлой версией под
            // ядро 1.11 (плитка, always-on), а 1.14 старый вид не примет.
            val cfg = freshCacheFile(com.zen.blaster.core.ClashApi.retarget(retune(cloudflareSafe(
                preresolve(com.zen.blaster.core.SingBoxCompat.upgrade(config))))))
            // Сосед по порту — это прошлое ядро, которое ещё закрывается в фоне
            // (см. closeBox). Это нормально и ничему не мешает, а строка в
            // журнале 190 раз за день читалась как «ядро не отпускает порт».
            // Тревожно только когда занято почти всё окно — значит, ядра
            // копятся и не закрываются вовсе.
            if (port >= 9095 + 15) say("⚠️ Управляющий порт ядра: $port — прошлые ядра не закрываются")

            BoxCore.setup(filesDir, cacheDir)
            val service = BoxCore(this) { line ->
                android.util.Log.i("ZenCore", line)   // дублируем в logcat — видно через adb
                sayCore(line)
            }
            // Держим ссылку ДО старта: если ядро повиснет внутри подъёма,
            // отключение всё равно найдёт его и закроет.
            boxService = service
            try { service.start(cfg) } catch (t: Throwable) {
                if (boxService === service) boxService = null
                closeBox(service, 0)
                throw t
            }
            if (!alive(gen)) { if (boxService === service) boxService = null; closeBox(service, 0); return }
            running = true
            say("✅ sing-box запущен")
            val tag = if (dpi && !hasVpnOutbound(config)) "dpi-socks" else "proxy"

            // ВАЖНО: состояние «Подключено» ставим ТОЛЬКО после проверки.
            // Пока идут замеры — кольцо крутится, а не рапортует об успехе заранее.
            if (!verifyTunnel(tag, gen)) {
                if (!alive(gen)) return
                // Ядро поднялось, а канала нет: сервер не ответил на рукопожатие
                // или ответ до нас не дошёл. Самый частый вид отказа в блокировку.
                com.zen.blaster.core.Blackbox.outcome(false,
                    "ядро поднялось, но канал не отвечает (сервер молчит либо ответ режут)")
                Stats.stage.value = ""
                // Туннель уже работал — значит человек подключён и ждёт связи, а
                // не объяснений. Продолжаем долбиться под капотом; kill switch
                // всё это время держит трафик закрытым.
                if (everUp) {
                    say("⚠️ Канал не отозвался — держу туннель и пробую снова")
                    reconnect()
                    return
                }
                // ПЕРВЫЙ ПОДЪЁМ: РЕШЕНИЕ ЗА ВЕРХОМ, А НЕ ЗА НАМИ.
                //
                // Здесь стояло «отключаюсь» — и это рвало связь на ровном
                // месте. Наша проверка канала укладывается примерно в десять
                // секунд, а перебор стратегий наверху отводит ноде от двадцати
                // до сорока пяти: XHTTP через CDN и аварийные ноды медленные по
                // своей природе. Погасив туннель на десятой секунде, мы
                // обрывали этот перебор на полпути — человек видел «отключаюсь»
                // там, где через пару секунд всё бы поднялось.
                //
                // Теперь молча держим защиту и ждём: наверху либо сменят ядро,
                // либо снимут подмену имени, либо опросят сеть. Гасит туннель
                // только человек.
                say("⚠️ Канал пока не отозвался — держу защиту, пробую дальше")
                if (com.zen.blaster.core.Prefs(this).killSwitch)
                    raiseBlackhole("канал не отозвался")
                // Туннель держим, а вот ядро гасим обязательно.
                //
                // Оставленное ядро продолжает жить и держать порт clash_api:
                // следующая попытка потом девять секунд ждёт его впустую и
                // всё равно стартует поверх. «Не рвать связь» — это про
                // интерфейс и про статус, а не про брошенное ядро.
                closeBox(takeBox(), 4_000)
                try { ByeDpi.stop() } catch (_: Throwable) {}
                try { XrayEngine.stop() } catch (_: Throwable) {}
                running = false
                return
            }
            if (!alive(gen)) return

            Stats.stage.value = ""
            everUp = true
            reconnects = 0                 // связь есть — счётчик неудач обнуляем
            onState?.invoke(true)          // ← теперь «Подключено» честное
            askWhereWeAre(gen)
            checkDnsQuietly()
            com.zen.blaster.core.Blackbox.outcome(true, "канал проверен")
            startStats(tag)
            updateNotif(null)
        } catch (t: Throwable) {
            // Throwable, а не Exception: UnsatisfiedLinkError и прочие Error
            // иначе улетают наверх и убивают сервис молча.
            if (!alive(gen)) return
            say("❌ Ошибка ядра: ${t.message}")
            com.zen.blaster.core.Blackbox.outcome(false,
                "ядро упало с исключением: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            // Падение ядра под работающим туннелем — повод поднять его заново,
            // а не бросать человека без связи и без защиты.
            if (everUp) { reconnect(); return }
            stopVpn()
        }
    }

    // ПОДГОНКА КОНФИГА ПОД ТОТ КАНАЛ, КОТОРЫЙ ЕСТЬ ПРЯМО СЕЙЧАС.
    //
    // Конфиг собирается в приложении и сохраняется: переподключение, подъём
    // из плитки и always-on берут готовый. А сеть под ним к этому моменту уже
    // другая — человек вышел из дома, Wi-Fi сменился на сотовый, сотовый упал
    // с LTE на EDGE. И как раз в такие моменты переподключение и случается.
    //
    // Для Hysteria2 это решает судьбу ноды. Заявленная полоса включает режим
    // Brutal: ядро держит темп ЖЁСТКО и на потерях его не снижает. Цифра,
    // честная для домашнего Wi-Fi, на EDGE означает просьбу лить в сотни раз
    // больше, чем канал уносит — буфер модема забивается за секунды, и
    // соединение умирает. Проверено живьём 6 сентября 2026: после пересадки
    // с Wi-Fi на сотовую систему оценила канал в 14 Кбит/с, а в сохранённом
    // конфиге лежало 54 Мбит/с.
    //
    // Поэтому перед каждым стартом пересматриваем полосу по текущей сети:
    // на медленном мобильном канале убираем её вовсе (ядро уходит на BBR),
    // иначе обрезаем оценкой системы.
    private fun retune(config: String): String = try {
        val root = org.json.JSONObject(config)
        val outs = root.optJSONArray("outbounds") ?: return config
        val slow = com.zen.blaster.core.NetInfo.slowMobile(this)
        val kbps = com.zen.blaster.core.NetInfo.downKbps(this)
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("type") != "hysteria2") continue
            val was = o.optInt("down_mbps", 0)
            if (was <= 0) continue                     // и так на BBR — не трогаем
            when {
                slow -> {
                    o.remove("down_mbps"); o.remove("up_mbps")
                    say("🌊 Канал узкий — Hysteria2 перевожу на BBR вместо Brutal")
                    changed = true
                }
                kbps > 0 -> {
                    val cap = (kbps * 4 / 5 / 1000).coerceAtLeast(1)
                    if (cap < was) {
                        val up = o.optInt("up_mbps", 0)
                        o.put("down_mbps", cap)
                        if (up > 0) o.put("up_mbps", (up * cap / was).coerceAtLeast(1))
                        say("⚡ Hysteria2: темп снижен до $cap↓ Мбит/с — столько даёт канал")
                        changed = true
                    }
                }
            }
        }
        if (changed) root.toString() else config
    } catch (_: Throwable) { config }

    // Подставляет в outbound уже разрешённый IP сервера.
    //
    // Зачем: пока туннеля нет, имя сервера приходится резолвить мимо него —
    // и этот единственный запрос выдаёт оператору, к какому VPN идёт абонент,
    // а резолверу — настоящий IP и страну. Спрашиваем через DoH и дальше
    // ядро вообще не резолвит. SNI при этом остаётся доменом (tls.server_name
    // прописан отдельно), так что TLS не ломается.
    private fun preresolve(config: String): String = try {
        val root = org.json.JSONObject(config)
        val outs = root.optJSONArray("outbounds") ?: return config
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") != "proxy") continue
            val server = o.optString("server")
            if (server.isEmpty() || com.zen.blaster.core.Resolver.isIpLiteral(server)) continue
            // Сервер должен предъявлять сертификат на своё имя — если SNI не
            // задан, подменять адрес на IP нельзя, порвётся проверка TLS.
            val sni = o.optJSONObject("tls")?.optString("server_name").orEmpty()
            if (o.has("tls") && sni.isEmpty()) continue
            val ip = com.zen.blaster.core.Resolver.resolve(server) ?: continue
            if (ip == server) continue
            o.put("server", ip)
            say("🔎 $server → $ip")
            changed = true
        }
        if (changed) root.toString() else config
    } catch (_: Throwable) { config }

    // СВОЙ ФАЙЛ КЕША НА КАЖДЫЙ ПОДЪЁМ.
    //
    // Ядро всегда открывает базу кеша (cache.db) и запирает её на время
    // работы. Если прошлое ядро зависло при закрытии (мёртвый канал — см.
    // closeBox), замок остаётся у него, а новое ждёт его 10 раз по секунде
    // и падает с «initialize cache-file: timeout». Со стороны — «прошлое
    // ядро держит, новое не поднимается». На стенде с тем же libbox: при
    // зависшем прошлом ядре подъём 9,5 с и отказ; с отдельным файлом —
    // 60 мс. Содержимое кеша нам не нужно (селекторов и FakeIP нет), так
    // что старые файлы просто удаляем: у зависшего владельца файл живёт,
    // пока он его держит, а новому ядру он не мешает.
    private fun freshCacheFile(config: String): String = try {
        val dir = File(filesDir, "work").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("cache") && f.name.endsWith(".db") }
            ?.forEach { it.delete() }
        val root = org.json.JSONObject(config)
        val exp = root.optJSONObject("experimental") ?: org.json.JSONObject().also { root.put("experimental", it) }
        exp.put("cache_file", org.json.JSONObject().put("enabled", true)
            .put("path", File(dir, "cache-${System.currentTimeMillis()}.db").absolutePath))
        root.toString()
    } catch (_: Throwable) { config }

    // Нода за Cloudflare — отпечаток без постквантового ключа.
    //
    // Зовётся ПОСЛЕ preresolve: судим по адресу, а он известен только там.
    // Отпечаток устройства (chrome) к Cloudflare ТСПУ глушит — подробности и
    // замеры в core/Cloudflare.kt. Reality не трогаем: там отпечаток часть
    // маскировки, а за Cloudflare Reality не живёт.
    private fun cloudflareSafe(config: String): String = try {
        val root = org.json.JSONObject(config)
        val outs = root.optJSONArray("outbounds") ?: return config
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tls = o.optJSONObject("tls") ?: continue
            if (!tls.optBoolean("enabled")) continue
            if (tls.optJSONObject("reality")?.optBoolean("enabled") == true) continue
            if (!com.zen.blaster.core.Cloudflare.owns(o.optString("server"))) continue
            val utls = tls.optJSONObject("utls")
            val fp = if (utls?.optBoolean("enabled") == true) utls.optString("fingerprint") else ""
            if (!com.zen.blaster.core.Cloudflare.risky(fp)) continue
            tls.put("utls", org.json.JSONObject().put("enabled", true)
                .put("fingerprint", com.zen.blaster.core.Cloudflare.SAFE_FP))
            say("☁️ Нода за Cloudflare — отпечаток ${com.zen.blaster.core.Cloudflare.SAFE_FP} " +
                "вместо ${fp.ifEmpty { "стандартного" }}: chrome к Cloudflare ТСПУ глушит")
            changed = true
        }
        if (changed) root.toString() else config
    } catch (_: Throwable) { config }

    // Есть ли в конфиге VLESS/Hy2/… outbound с тегом proxy (т.е. включён VPN).
    private fun hasVpnOutbound(config: String): Boolean =
        config.contains("\"tag\":\"proxy\"") || config.contains("\"tag\": \"proxy\"")

    private fun stopVpn() {
        // Незакрытая запись означала бы «процесс умер на середине». Если сеанс
        // ещё открыт, значит туннель гасят до исхода — так и пишем, иначе в
        // журнале останется поклёп на ноду.
        com.zen.blaster.core.Blackbox.abandon("туннель остановлен")
        // Сразу помечаем поколение устаревшим: поток, который сейчас поднимает
        // туннель, увидит это и выйдет, не трогая ни уведомление, ни состояние.
        epoch.incrementAndGet()
        stopStats()
        // Здесь чаще всего ГЛАВНЫЙ поток (кнопка, шторка, система). Быстрое —
        // сразу, тяжёлое (закрытие ядра, ожидание выхода Xray) — в фоне.
        val box = takeBox()
        val xray = XrayEngine.detach()
        try { ByeDpi.stop() } catch (_: Throwable) {}
        // TUN закрываем ДО ядра: читающая его горутина сразу получает ошибку
        // и выходит — закрытию ядра остаётся меньше, чего ждать.
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        kotlin.concurrent.thread(name = "zen-teardown", isDaemon = true) {
            closeBox(box, 5_000)
            XrayEngine.kill(xray)
        }
        tunUsed = false
        // Явное отключение выпускает трафик: это решение человека, а не сбой.
        blackholeUp = false
        blocked = false
        Stats.blocked.value = false
        running = false
        starting.set(false)
        reconnects = 0
        // Человек отключился сам — со следующего запуска отсчёт с чистого листа.
        everUp = false
        lastNotifKey = ""
        // Снимаем авто-сброс DNS при смене сети: без туннеля он ни к чему.
        com.zen.blaster.core.DnsCacheManager.stopWatching(this)
        onState?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { stopVpn(); super.onRevoke() }

    // ------------------------------------------------- маскировка проверок
    //
    //  Служебные запросы клиента — тоже отпечаток. Раньше туннель раз в 5 секунд
    //  ровно дёргал ОДИН и тот же адрес: содержимое зашифровано, но ритм виден
    //  снаружи как пульс, и по нему соединение опознаётся без разбора пакетов.
    //  Проверка после подключения ходила по фиксированному списку в неизменном
    //  порядке — такой же подарок для анализа.
    //
    //  Теперь: адрес выбирается из пула, порядок проверок случайный, интервал
    //  пинга гуляет. Это не делает нас невидимыми, но убирает ровный рисунок,
    //  за который проще всего зацепиться.
    private val probePool = listOf(
        "http://cp.cloudflare.com/generate_204",
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://www.msftconnecttest.com/connecttest.txt",
        "http://detectportal.firefox.com/success.txt"
    )

    private fun anyProbe() = probePool.random()

    // 5 секунд ровно → 4…9 секунд вразнобой.
    private fun pingDelay() = 4000L + (0..5000).random()

    // ---------------------------------------------------------- статистика

    // Скорость берём из clash_api (/traffic отдаёт поток JSON), пинг — через
    // /proxies/<tag>/delay: замер делает САМО ядро через туннель, поэтому
    // цифра честная (наше приложение исключено из TUN и мерило бы прямой канал).
    private fun startStats(proxyTag: String) {
        if (statsRunning) return
        statsRunning = true
        Stats.since.value = System.currentTimeMillis()
        com.zen.blaster.core.RadioKeeper.start(this) { say(it) }

        // Скорость: опрашиваем /connections и считаем дельту.
        // (/traffic в sing-box 1.11 — websocket, обычным чтением не берётся.)
        Thread({
            var prevUp = -1L; var prevDown = -1L; var prevT = 0L
            while (statsRunning) {
                try {
                    val req = com.zen.blaster.core.ClashApi.req("/connections").build()
                    val body = http.newCall(req).execute().use { it.body?.string().orEmpty() }
                    val o = org.json.JSONObject(body)
                    val u = o.optLong("uploadTotal"); val d = o.optLong("downloadTotal")
                    val now = System.currentTimeMillis()
                    if (prevUp >= 0 && now > prevT) {
                        val dt = (now - prevT) / 1000.0
                        Stats.up.value = (((u - prevUp).coerceAtLeast(0)) / dt).toLong()
                        Stats.down.value = (((d - prevDown).coerceAtLeast(0)) / dt).toLong()
                        updateNotif(null)
                    }
                    // ---- БАЙТЫ, ПРИШЕДШИЕ ИМЕННО ЧЕРЕЗ ПРОКСИ ----
                    //
                    // Самое надёжное доказательство, что канал жив: через него
                    // прямо сейчас идут чужие байты — человек листает ленту,
                    // играет музыка, качается обновление.
                    //
                    // Но общие uploadTotal/downloadTotal для этого не годятся.
                    // При включённом обходе РУ в них валится и всё, что ушло
                    // напрямую, мимо туннеля: банк, Госуслуги, любое российское
                    // приложение в фоне. Туннель мог бы лежать, а счётчик рос
                    // бы вечно — и сторож не заметил бы отвала никогда.
                    // Поэтому считаем ровно те соединения, чья цепочка идёт
                    // через прокси.
                    //
                    // И считаем ПРИЁМ, а не отдачу: отдача растёт и в мёртвый
                    // сокет — ядро успевает сложить данные в буфер и слать
                    // повторы раньше, чем узнает об обрыве. Обратный путь
                    // доказывают только принятые байты.
                    proxyRx(o, proxyTag)?.let { rx ->
                        if (rx > trafficTotal) {
                            trafficTotal = rx
                            trafficAt = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    prevUp = u; prevDown = d; prevT = now
                } catch (t: Throwable) {
                    android.util.Log.w("ZenStats", "traffic: ${t.message}")
                }
                // Сторож утечки: если TUN отвалился (система отозвала VPN), нельзя
                // молча оставаться в состоянии «Подключено» — весь трафик пойдёт
                // мимо туннеля с настоящим IP. Лучше честно отключиться.
                // В режиме прокси TUN не создаётся вовсе — сторожить нечего.
                if (tunUsed && tunFd?.fileDescriptor?.valid() != true) {
                    say("❌ Туннель отозван системой — отключаюсь, чтобы не светить IP")
                    com.zen.blaster.core.Blackbox.note(
                        "❌ TUN отобрала система после ${uptimeText()} работы " +
                        "(другой VPN, «Постоянная VPN» у соседа или запрет фона) · " +
                        com.zen.blaster.core.NetInfo.line(this))
                    stopVpn()
                    return@Thread
                }
                Thread.sleep(1000)
            }
        }, "zen-traffic").apply { isDaemon = true }.start()

        // Периодический пинг. Он же — сторож живости канала: подряд провалившиеся
        // замеры означают, что туннель фактически мёртв, хотя формально поднят.
        Thread({
            var misses = 0
            var noNetLogged = false
            var trafficLogged = false
            while (statsRunning) {
                // Терпение под канал, а не одно на всех.
                //
                // Замер — это полноценный HTTP-запрос наружу через ядро, и на
                // EDGE он не укладывается в пять секунд НИКОГДА. Раньше цифра
                // была зашита, и на медленной сотовой связи клиент гарантированно
                // набирал пять промахов и рвал живой туннель.
                val pat = com.zen.blaster.core.NetInfo.patience(this)
                val budget = 5000 * pat                    // 5 / 10 / 20 секунд
                // ЛУЧШИЙ ИЗ ДВУХ.
                //
                // Приём подсмотрен у Happ (у него это отдельный режим пинга
                // «Double»): промахнулся первый запрос — тут же переспрашиваем,
                // не дожидаясь следующего круга. Одиночный затык на сотовой
                // связи — обычное дело: модем вышел из спячки, сота
                // перегружена, случилась пересадка. Второй запрос через
                // мгновение почти всегда проходит, и такой промах вообще не
                // должен доходить до счётчика.
                Stats.ping.value = probeOnce(proxyTag, budget)
                if (Stats.ping.value <= 0) Stats.ping.value = probeOnce(proxyTag, budget)
                Stats.feedPing(Stats.ping.value)

                if (Stats.ping.value > 0) {
                    // Один промах, тут же закрытый вторым замером, — не событие.
                    // За 14.10.2026 журнал набрал 57 «восстановился» и 45 «не
                    // отозвался»: почти всё это — одиночные осечки сотовой
                    // связи, и за ними терялись настоящие провалы.
                    if (misses >= 2) say("✅ Канал восстановился после $misses промахов")
                    misses = 0
                    // Пинг через туннель вдруг втрое хуже обычного — похоже, сота
                    // сбросила на медленную полосу. Встряхиваем радио (keepmts).
                    val best = Stats.shownPing.value
                    if (best > 0 && Stats.ping.value > 500 && Stats.ping.value > best * 3)
                        com.zen.blaster.core.RadioKick.kick(this,
                            "пинг ${Stats.ping.value} мс при обычных $best") { say(it) }
                    noNetLogged = false
                    trafficLogged = false
                } else if (statsRunning && movingRecently(pat)) {
                    // ТРАФИК ВАЖНЕЕ ЗАМЕРА.
                    //
                    // Счётчики ядра растут — значит через туннель прямо сейчас
                    // ходят чужие байты: человек листает ленту, играет музыка,
                    // качается обновление. Канал живой, что бы ни говорил наш
                    // служебный запрос: на забитом узком канале он встаёт в
                    // очередь последним и промахивается именно тогда, когда
                    // туннелем активнее всего пользуются. Рвать в этот момент —
                    // худшее, что можно сделать.
                    if (!trafficLogged && misses > 0) {
                        say("… замер не прошёл, но трафик идёт — туннель не трогаю")
                        trafficLogged = true
                    }
                    misses = 0
                    noNetLogged = false
                } else if (statsRunning) {
                    // СНАЧАЛА смотрим, есть ли у телефона сеть вообще.
                    //
                    // Раньше промахи считались вслепую, и туннель рвался на
                    // ровном месте: в лифте, в метро, при пересадке Wi-Fi→LTE
                    // и просто когда модем засыпает, замер не проходит — но
                    // виноват в этом не сервер. Переподключение в такой момент
                    // только вредит: оно гасит рабочий туннель и поднимает
                    // новый в сети, которой ещё нет.
                    if (!hasUnderlyingNet()) {
                        if (!noNetLogged) {
                            say("… сети нет — жду, туннель не трогаю")
                            noNetLogged = true
                        }
                        misses = 0
                    } else {
                        noNetLogged = false
                        misses++
                        // Второй промах подряд при живой сети — пора будить
                        // радио: на сотовой связи чаще всего это модем уснул
                        // или сота увела на EDGE, а не сервер умер.
                        if (misses == 2) com.zen.blaster.core.RadioKick.kick(this,
                            "канал молчит") { say(it) }
                        // МЯГКИЙ СБРОС — между встряской и переподключением.
                        //
                        // Раньше после встряски сторож просто ждал до десяти
                        // промахов (на медленной сети — двадцати), и 15.10
                        // Hysteria2 на МТС дважды «буксовала» по 9 и 6
                        // промахов, минуты без связи. Сброс соединений ядра
                        // дешёвый: туннель остаётся, а QUIC-нода заводит
                        // новое подключение с нового порта — ровно то, что
                        // нужно, если оператор зарезал старый поток.
                        // Вместе со встряской: промах тут — это уже два
                        // двойных замера и ни байта трафика, около минуты.
                        if (misses == 2 || misses == 5) {
                            boxService?.resetNetwork()
                            say("♻️ Сбросил соединения ядра — канал молчит ($misses промаха подряд)")
                        }
                        // Сколько промахов терпим, прежде чем поверить в отвал.
                        //
                        // Порог тоже привязан к каналу: на проводе и уверенном
                        // Wi-Fi пять промахов подряд — действительно отвал, а на
                        // сотовой связи это будни, и раньше клиент на них
                        // покупался. Теперь на медленном мобильном канале нужно
                        // двадцать промахов подряд, и при этом ни одного байта
                        // трафика между ними — только тогда туннель правда мёртв.
                        val limit = 5 * pat
                        if (misses >= limit) {
                            say("⚠️ Канал не отвечает при живой сети — переподключаюсь")
                            com.zen.blaster.core.Blackbox.note(
                                "💔 «${Stats.server.value}» (${Stats.mode.value}) сорвалась " +
                                "после ${uptimeText()} работы: $limit замеров подряд мимо " +
                                "и ни байта трафика · " + com.zen.blaster.core.NetInfo.line(this))
                            misses = 0
                            reconnect()
                            return@Thread
                        }
                    }
                }
                updateNotif(null)
                // Пока через туннель идут чужие байты, канал доказан и без нас —
                // служебный замер раз в несколько секунд ничего не добавляет,
                // а нагрузку даёт. Реже — раз в полминуты. Замолчит трафик —
                // вернёмся к частым замерам: тогда сторож и нужен.
                Thread.sleep(if (movingRecently(pat)) 30_000L else pingDelay())
            }
        }, "zen-ping").apply { isDaemon = true }.start()
    }

    private fun stopStats() {
        statsRunning = false; Stats.reset()
        com.zen.blaster.core.RadioKeeper.stop()
        trafficTotal = 0L; trafficAt = 0L
    }

    // Сколько байт принято через прокси и когда счётчик последний раз вырос.
    // Пишет поток статистики, читает сторож живости.
    //
    // Время — elapsedRealtime, а не стенные часы: перевод времени, поправка
    // по NTP и смена пояса не должны выглядеть как обрыв связи. Оно же
    // единственное правильно идёт, пока телефон спит.
    @Volatile private var trafficTotal = 0L
    @Volatile private var trafficAt = 0L

    // Сумма принятых байт по соединениям, идущим через прокси.
    //
    // Соединения приходят и уходят, поэтому сумма живых может и упасть —
    // тогда мы просто не считаем это ростом. Нам нужен не точный объём, а
    // сам факт: пришло ли через туннель хоть что-то с прошлого раза.
    private fun proxyRx(o: org.json.JSONObject, tag: String): Long? = try {
        val arr = o.optJSONArray("connections") ?: return null
        var sum = 0L
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val chains = c.optJSONArray("chains") ?: continue
            var mine = false
            for (j in 0 until chains.length())
                if (chains.optString(j) == tag) { mine = true; break }
            if (mine) sum += c.optLong("download")
        }
        sum
    } catch (_: Throwable) { null }

    // Шли ли через туннель байты только что.
    //
    // Окно привязано к терпению: на медленном канале между двумя порциями
    // данных законно проходит больше времени, а на проводе тишина дольше
    // десяти секунд уже подозрительна. Пока трафик идёт, сторож молчит —
    // даже если его собственный служебный замер не проходит.
    private fun movingRecently(patience: Int): Boolean {
        if (trafficAt == 0L) return false
        return android.os.SystemClock.elapsedRealtime() - trafficAt < 10_000L * patience
    }

    // Есть ли у телефона рабочая сеть под нами.
    //
    // Наше приложение исключено из TUN, поэтому его «активная сеть» — это
    // настоящий физический канал. Если сети нет вовсе (доза, лифт, метро,
    // пересадка Wi-Fi→LTE), провалившийся замер ничего не говорит о сервере,
    // и рвать из-за него живой туннель нельзя.
    private fun hasUnderlyingNet(): Boolean = try {
        val n = cm.activeNetwork
        val c = n?.let { cm.getNetworkCapabilities(it) }
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        true    // не смогли выяснить — ведём себя как раньше, не глушим сторожа
    }

    // Поднять туннель заново тем же конфигом, не трогая выбор пользователя.
    //
    // Сеть на телефоне рвётся постоянно: пересадка с Wi-Fi на LTE, смена
    // соты, засыпание модема. Раньше после такого туннель оставался «поднят»,
    // но трафик не шёл, и приходилось вручную тыкать кнопку.
    @Volatile private var reconnects = 0

    // Туннель хотя бы раз доходил до проверенного состояния «Подключено».
    //
    // С этого момента гасить его по своему усмотрению НЕЛЬЗЯ ни при каких
    // обстоятельствах: человек нажал «Подключить» и ждёт, что связь будет
    // восстанавливаться сама, сколько бы это ни заняло. Отключение — только
    // по его команде. Раньше клиент сдавался сам: не уложился в проверку за
    // 20 секунд или исчерпал пять попыток — и туннель падал вместе с защитой.
    @Volatile private var everUp = false

    // ------------------------------------------------------------ kill switch
    //
    // Глухой туннель: интерфейс поднят, маршруты забирают весь трафик, а за
    // ним никого нет — пакеты уходят в дескриптор, который никто не читает.
    // Снаружи это «интернета нет», и ровно это нам и нужно: пока настоящий
    // туннель лежит, ни один пакет не должен уйти в открытую сеть с настоящим
    // адресом.
    //
    // Поднимаем ДО закрытия старого дескриптора: establish() заменяет прежний
    // интерфейс, поэтому щели без защиты не возникает. Приложения, выведенные
    // из туннеля намеренно (банки, госуслуги), остаются исключёнными и здесь —
    // иначе kill switch отрубал бы связь как раз тем, ради кого делался обход.
    @Volatile private var blackholeUp = false

    private fun raiseBlackhole(reason: String): Boolean {
        return try {
            val prefs = com.zen.blaster.core.Prefs(this)
            val b = Builder()
            b.setSession("ZEN NITRO — трафик заблокирован")
            b.setMtu(1420)
            b.addAddress("172.19.0.1", 30)
            b.addRoute("0.0.0.0", 0)
            b.addRoute("::", 0)
            // Себя не блокируем: иначе не поднять туннель заново и не сходить
            // за подписками — приложение осталось бы без рук.
            try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
            if (prefs.geoRouting) for (p in prefs.splitApps())
                try { b.addDisallowedApplication(p) } catch (_: Exception) {}
            b.setBlocking(false)
            val pfd = b.establish() ?: return false
            try { tunFd?.close() } catch (_: Exception) {}
            tunFd = pfd
            blackholeUp = true
            blocked = true
            Stats.blocked.value = true
            say("🔒 Kill switch: $reason — трафик заблокирован")
            updateNotif("🔒 Трафик заблокирован — туннель лежит")
            true
        } catch (t: Throwable) {
            say("❌ Kill switch не поднялся: ${t.message}")
            false
        }
    }

    private fun reconnect() {
        val prefs = com.zen.blaster.core.Prefs(this)
        // СБРОС КЕША АДРЕСОВ ПРИ ПЕРЕПОДКЛЮЧЕНИИ.
        //
        // Обрыв часто совпадает с тем, что нода переехала: провайдеры крутят
        // адреса, и кеш на пять минут держал бы старый — переподъём стучался
        // бы туда, где сервера уже нет. Заодно снимаем метку «DoH мёртв»:
        // обрыв бывает от смены сети, а на новой DoH может быть жив.
        com.zen.blaster.core.Resolver.forgetAll()
        com.zen.blaster.core.Resolver.resetDeadMark()
        // Пока переподключаемся, дыры быть не должно: между падением старого
        // туннеля и подъёмом нового трафик иначе уходит в открытую сеть.
        if (prefs.killSwitch) raiseBlackhole("переподключение")
        if (!prefs.autoReconnect) {
            if (prefs.killSwitch) {
                say("↺ Автоподключение выключено, но kill switch держит трафик закрытым")
                say("   Отключить и выпустить трафик — кнопкой в приложении.")
                return
            }
            say("↺ Автоподключение выключено — отключаюсь"); stopVpn(); return
        }
        // РАНЬШЕ ЗДЕСЬ БЫЛА КАПИТУЛЯЦИЯ: после пяти неудач подряд клиент
        // переставал пытаться. Для человека это выглядело как «сам отвалился»:
        // он нажимал «Подключить», видел «Подключено», а через минуту связи не
        // было и никто её не восстанавливал. Пока туннель хоть раз поднялся,
        // мы пробуем бесконечно — решение прекратить принимает только человек.
        if (reconnects >= 5 && !everUp) {
            say("❌ Пять неудачных попыток подряд — сдаюсь, смените сервер")
            if (prefs.killSwitch) {
                say("🔒 Kill switch включён — трафик остаётся заблокированным")
                return
            }
            stopVpn(); return
        }
        if (reconnects == 5 && everUp)
            say("↻ Связь не возвращается — продолжаю пробовать, пока не отключите")
        reconnects++

        // Разбор почерка обрыва. На мобильной сети пачка обрывов подряд — это
        // почти всегда не «сервер плохой», а погашенная оператором сессия.
        // Менять в такой ситуации ноды по кругу бесполезно, и человек должен
        // это видеть, а не гадать.
        val mobile = try {
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (_: Throwable) { false }
        val badge = Stats.mode.value
        val quicNode = badge.contains("Hy2") || badge.contains("Hysteria") || badge.contains("TUIC")
        if (com.zen.blaster.core.PdpGuard.onDrop(mobile, System.currentTimeMillis())) {
            say("📵 " + com.zen.blaster.core.PdpGuard.lastVerdict)
            say("   " + com.zen.blaster.core.PdpGuard.advice(quicNode))
        }

        // Возвращаемся с НОВЫМ отпечатком TLS. Готовый конфиг несёт тот, что
        // выбрали при сборке, и без подмены мы стучались бы одним и тем же
        // приветствием после каждого обрыва.
        val cfg = com.zen.blaster.core.PdpGuard.freshenFingerprint(prefs.lastConfig)
        val dpi = prefs.lastDpi
        val strat = prefs.dpiStrategy
        xrayConfig = prefs.lastXray.ifEmpty { null }

        kotlin.concurrent.thread(name = "zen-reconnect", isDaemon = true) {
            // Гасим всё старое и держим паузу: сети нужно время встать.
            statsRunning = false
            epoch.incrementAndGet()
            closeBox(takeBox(), 4_000)
            ByeDpi.stop(); XrayEngine.stop()
            // Старый дескриптор TUN обязательно закрыть: иначе система отзовёт
            // его уже ПОСЛЕ того, как поднимется новый, и убьёт свежий туннель.
            //
            // Исключение — поднятый глухой туннель kill switch: его закрывать
            // нельзя, он и есть защита на время паузы. Его сменит establish()
            // настоящего туннеля, а замена интерфейса щели не оставляет.
            if (!blackholeUp) {
                try { tunFd?.close() } catch (_: Exception) {}
                tunFd = null
            }
            running = false
            Stats.stage.value = "Переподключаюсь (попытка $reconnects)…"
            // Пауза с разбросом, а не ровно 1,5 / 3 / 4,5 секунды.
            //
            // Оператор, который гасит сессию за туннель, видит и то, КАК мы
            // возвращаемся. Ответ через одинаковый интервал — сам по себе
            // почерк: ровный ритм переподключений не встречается у обычного
            // приложения. Разброс до половины паузы стоит нам ничего, а
            // ритм ломает.
            //
            // Шаг растёт, но не бесконечно: попытки теперь не кончаются, и без
            // потолка пауза дошла бы до часов — человек остался бы с закрытым
            // трафиком и без связи, formально «на связи». Потолок 8 шагов ≈ 12 с
            // ожидания плюс разброс.
            val step = reconnects.coerceAtMost(8)
            Thread.sleep(1500L * step + (0..750L * step).random())

            starting.set(false)
            val gen = epoch.incrementAndGet()
            // Возврат после обрыва — такая же попытка, как первая, и разбирать
            // её надо отдельной записью: у неё своя обстановка (сеть уже могла
            // смениться) и свой исход.
            com.zen.blaster.core.Blackbox.session(
                Stats.server.value,
                listOf(
                    "возврат после обрыва · попытка $reconnects из 5",
                    "сеть: ${com.zen.blaster.core.NetInfo.line(this)}",
                    "нода: «${Stats.server.value}» (${Stats.mode.value})" +
                        " · отпечаток TLS прежний (${com.zen.blaster.core.ConfigBuilder.pickFingerprint()})" +
                        (if (com.zen.blaster.core.PdpGuard.lastVerdict.isNotEmpty())
                            " · ${com.zen.blaster.core.PdpGuard.lastVerdict}" else "")
                )
            )
            startVpnBlocking(cfg, dpi, strat, gen)
            if (running) reconnects = 0
        }
    }

    // Проверка канала перед тем, как объявить «Подключено».
    // Замер делает САМО ядро через туннель (/proxies/<tag>/delay), поэтому
    // результат честный: если прокси мёртв — узнаем сразу, а не «через 5 минут».
    private fun verifyTunnel(tag: String, gen: Int): Boolean {
        // 1. ждём, пока поднимется локальный API ядра
        Stats.stage.value = "Поднимаю ядро…"
        var apiUp = false
        for (i in 1..25) {
            if (!alive(gen)) return false
            if (!running && boxService == null) return false
            try {
                val r = com.zen.blaster.core.ClashApi.req("/proxies").build()
                http.newCall(r).execute().use { if (it.isSuccessful) apiUp = true }
            } catch (_: Throwable) {}
            if (apiUp) break
            Thread.sleep(200)
        }
        if (!apiUp) { say("⚠️ API ядра не поднялся"); return false }

        // 2. живой ли канал вообще (замер до Cloudflare)
        Stats.stage.value = "Проверяю канал…"
        var delay = -1
        for (attempt in 1..3) {
            if (!alive(gen)) return false
            delay = probeDelay(tag, anyProbe())
            if (delay > 0) break
            Stats.stage.value = "Проверяю канал… ($attempt/3)"
            Thread.sleep(600)
        }
        if (delay <= 0) return false
        Stats.ping.value = delay
        Stats.feedPing(delay)
        channelProven = true
        say("✅ Канал живой: $delay мс")
        Stats.stage.value = "Канал живой · $delay мс"
        Thread.sleep(400)

        // 3. САМОЕ ГЛАВНОЕ: пробивается ли блокировка. Дёргаем заблокированный
        //    ресурс через туннель — если ок, значит обход реально работает.
        if (!alive(gen)) return false
        Stats.stage.value = "Проверяю обход блокировок…"
        val yt = probeDelay(tag, "https://www.youtube.com/generate_204")
        if (yt > 0) {
            say("✅ Обход блокировок работает (YouTube: $yt мс)")
            Stats.stage.value = "Блокировки пробиты · $yt мс"
        } else {
            say("⚠️ Канал есть, но YouTube не ответил — смените сервер или стратегию DPI.")
            Stats.stage.value = "Канал есть, обход под вопросом"
        }
        Thread.sleep(500)

        // 4. DNS через туннель.
        //
        // Проверяем НЕ по dns.google: с августа 2026 ТСПУ бьёт точечно по
        // Google и Cloudflare, и проба падала всегда — даже когда имена
        // прекрасно разрешались. Берём заведомо нейтральный адрес, который
        // никому не мешает, и пробуем несколько: одна осечка ничего не значит.
        //
        // Проба важна не сама по себе. Если канал живой, а имена не
        // разрешаются, человек видит худший из возможных отказов: «интернет
        // есть, но ничего не открывается». Значит об этом надо сказать прямо
        // и подсказать, что делать.
        Stats.stage.value = "Проверяю DNS…"
        val dnsProbes = listOf(
            "https://one.one.one.one/",          // без SNI-имени Cloudflare
            "https://checkip.amazonaws.com/",
            "https://api.ipify.org/"
        )
        var dns = -1
        for (p in dnsProbes) {
            if (!alive(gen)) return false
            dns = probeDelay(tag, p)
            if (dns > 0) break
        }
        if (dns > 0) {
            say("✅ DNS через туннель: $dns мс")
        } else {
            say("⚠️ Имена через туннель не разрешаются — канал есть, а сайты не откроются.")
            say("   Смените резолвер в настройках: Cloudflare и Google сейчас режут.")
            com.zen.blaster.core.Blackbox.note(
                "🚫 DNS через туннель не работает (резолвер " +
                com.zen.blaster.core.DnsChoice.tunnelName(
                    com.zen.blaster.core.Prefs(this).dnsProvider,
                    com.zen.blaster.core.Prefs(this).dnsCustom) + ")")
        }
        Stats.stage.value = "Готово"
        Thread.sleep(350)
        return true
    }

    // ГДЕ МЫ ВЫШЛИ НА САМОМ ДЕЛЕ.
    //
    // Имя ноды пишет провайдер, и оно врёт чаще, чем кажется: каскадные ноды
    // называют точку ВХОДА, а выходят в третьей стране — выбранная «🇫🇮 Каскад
    // NoV-FI» 27.08.2026 вышла в Румынии. Человек выбирает страну не просто
    // так, поэтому спрашиваем у внешней службы и показываем ответ рядом с
    // именем: выбрал Финляндию — видишь, что попал в Румынию.
    //
    // Спрашиваем в фоне и не мешаем подключению: не ответили — ну и ладно,
    // просто не покажем.
    // ПРОВЕРКА DNS — САМА, БЕЗ КНОПКИ.
    //
    // Детекция отравления, которую никто не нажимает, бесполезна: человек
    // узнаёт о подмене, только когда сайт уже не открылся. Поэтому после
    // каждого подъёма — но не чаще раза в час, она стоит десятка запросов —
    // проверяем молча в фоне и пишем в журнал итог. Отдельно говорим
    // громко, если оператор подменяет ответы, а человек сидит на
    // «Системном»: адрес ноды при этом спрашивается у того, кто врёт.
    @Volatile private var dnsCheckedAt = 0L
    private fun checkDnsQuietly() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (dnsCheckedAt != 0L && now - dnsCheckedAt < 60 * 60_000L) return
        dnsCheckedAt = now
        kotlin.concurrent.thread(name = "zen-dns-check", isDaemon = true) {
            Thread.sleep(4000)             // дать туннелю разогнаться
            // Писать — в журнал сервиса. Кнопка в приложении перенаправляет
            // этот вывод на экран, и последний назначивший побеждает.
            com.zen.blaster.core.DnsCacheManager.onLog = { msg -> say(msg) }
            val r = com.zen.blaster.core.DnsCacheManager.checkHealth()
            val prefs = com.zen.blaster.core.Prefs(this)
            if (r.operatorLies > 0 &&
                prefs.dnsProvider == com.zen.blaster.core.DnsChoice.SYSTEM) {
                say("⚠️ Оператор подменяет DNS, а у вас выбран «Системный».")
                say("   Адреса нод спрашиваются у него — выберите любой другой резолвер.")
            }
            val spoof = com.zen.blaster.core.Resolver.lastSpoof
            if (spoof.isNotEmpty()) {
                say("🛡 Поймана подмена адреса: $spoof — взял настоящий через шифрованный DNS")
                com.zen.blaster.core.Resolver.lastSpoof = ""
            }
        }
    }

    private fun askWhereWeAre(gen: Int) {
        Stats.exitPlace.value = ""
        Stats.exitIp.value = ""
        kotlin.concurrent.thread(name = "zen-where", isDaemon = true) {
            // Ядро только что поднялось: локальному входу нужно мгновение.
            Thread.sleep(1200)
            if (!alive(gen) || !running) return@thread
            val w = com.zen.blaster.core.LocalProbe.where(viaTunnel = true) ?: return@thread
            if (!alive(gen) || !running) return@thread
            Stats.exitPlace.value = w.short()
            Stats.exitIp.value = w.ip
            say("🌍 Фактический выход: ${w.short()} · ${w.ip}" +
                (if (w.isp.isNotEmpty()) " · ${w.isp}" else ""))
            com.zen.blaster.core.Blackbox.note(
                "🌍 «${Stats.server.value}» вышла в ${w.short()} (${w.ip})")
        }
    }

    // Замер задержки до конкретного URL силами ядра (через туннель).
    private fun probeDelay(tag: String, target: String): Int = try {
        val enc = java.net.URLEncoder.encode(target, "UTF-8")
        // 8 секунд, а не 4: у XHTTP/gRPC первый запрос поднимает мультиплексную
        // сессию и на мобильной сети легко занимает больше пяти секунд —
        // с прежним лимитом рабочие ноды браковались как мёртвые.
        //
        // И столько же надо дать СЕБЕ. Здесь была тихая беда: ядру мы просили
        // восемь секунд, а общий клиент бросал вызов на шестой — то есть
        // цифра 8000 была мёртвой буквой, и всё, что честно отвечало дольше
        // шести секунд, записывалось в мёртвые ноды. В журнале это видно по
        // отсутствию: за весь день нет НИ ОДНОГО замера длиннее 5887 мс, хотя
        // сотовая связь регулярно выдаёт больше.
        val budget = 8000L * com.zen.blaster.core.NetInfo.patience(this)
        val r = com.zen.blaster.core.ClashApi.req("/proxies/$tag/delay?url=$enc&timeout=$budget").build()
        val body = probeClient(budget).newCall(r).execute().use { it.body?.string().orEmpty() }
        org.json.JSONObject(body).optInt("delay", -1)
    } catch (_: Throwable) { -1 }

    // ---------------------------------------------------------- PlatformInterface
    // Контракт libbox 1.14 (27 методов). Сверка: javap -cp classes.jar
    // io.nekohasekai.libbox.PlatformInterface. SSH, Tailscale, мосты и
    // уведомления ядра нам не нужны — честно отказываем.

    // Создавался ли TUN в этой сессии (в режиме прокси — нет).
    @Volatile private var tunUsed = false

    override fun openTun(options: TunOptions): Int {
        tunUsed = true
        val b = Builder()
        b.setSession("ZEN NITRO")
        b.setMtu(options.getMTU())

        val v4 = options.getInet4Address()
        while (v4.hasNext()) { val p = v4.next(); b.addAddress(p.address(), p.prefix()) }
        val v6 = options.getInet6Address()
        while (v6.hasNext()) { val p = v6.next(); b.addAddress(p.address(), p.prefix()) }

        if (options.getAutoRoute()) {
            b.addRoute("0.0.0.0", 0)
            b.addRoute("::", 0)
            // В 1.14 адресов DNS у туннеля может быть несколько — отдаём все.
            try {
                val dns = options.getDNSServerAddress()
                while (dns.hasNext()) { val d = dns.next(); if (d.isNotEmpty()) b.addDnsServer(d) }
            } catch (_: Exception) {}
        }

        val inc = options.getIncludePackage()
        while (inc.hasNext()) { try { b.addAllowedApplication(inc.next()) } catch (_: Exception) {} }
        val exc = options.getExcludePackage()
        while (exc.hasNext()) { try { b.addDisallowedApplication(exc.next()) } catch (_: Exception) {} }

        b.setBlocking(false)
        val pfd = b.establish() ?: throw IllegalStateException("establish() null — нет разрешения VPN?")
        // Настоящий туннель заменил глухой — защита больше не «висит».
        if (blackholeUp) {
            blackholeUp = false; blocked = false; Stats.blocked.value = false
            say("🔓 Туннель поднят — трафик снова идёт")
        }
        tunFd = pfd
        return pfd.fd
    }

    // Защита исходящего сокета ядра от зацикливания в туннеле.
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}
    override fun cancelNotification(identifier: String?, typeID: Int) {}
    override fun readWIFIState(): WIFIState = WIFIState("", "")
    override fun registerMyInterface(name: String?) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    // Владельца соединения ядро 1.14 на Android спрашивает у КАЖДОГО
    // соединения, даже без правил по приложениям. Отказ исключением стоил
    // строки «failed to search process» в журнале на каждое соединение и
    // лишнего перехода через JNI. Правил по приложениям у нас нет (раздельное
    // туннелирование делает сам VpnService) — отвечаем «неизвестен» и всё.
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
                                     destinationAddress: String?, destinationPort: Int): ConnectionOwner =
        ConnectionOwner().apply { userId = -1 }
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() { throw UnsupportedOperationException("shell") }
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?,
                                  term: String?, rows: Int, cols: Int): ShellSession =
        throw UnsupportedOperationException("shell")
    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("ssh")
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("ssh")
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("ssh")
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = throw UnsupportedOperationException("bridge")

    // СИСТЕМНЫЙ РЕЗОЛВЕР ДЛЯ ЯДРА («DNS: системный», ru-dns, DoH по имени).
    //
    // В 1.11 ядро ходило к резолверу оператора само. В 1.14 «local» на
    // Android обязано идти через приложение — иначе ядро ищет
    // /etc/resolv.conf, которого на телефоне нет, и имена не разрешаются.
    // Спрашиваем ФИЗИЧЕСКУЮ сеть, а не сеть по умолчанию: пока поднят
    // туннель, сеть по умолчанию для нас — он сам, и запрос ушёл бы по кругу.
    private val localDns = object : LocalDNSTransport {
        override fun raw(): Boolean = false
        override fun exchange(ctx: ExchangeContext?, message: ByteArray?) {
            throw UnsupportedOperationException("raw")
        }
        override fun lookup(ctx: ExchangeContext?, network: String?, domain: String?) {
            ctx ?: return
            val name = domain.orEmpty().trimEnd('.')
            // Физической сети нет — честно «сервер недоступен» (SERVFAIL).
            // Спросить сеть по умолчанию нельзя: при туннеле это он сам, и
            // запрос вернулся бы в ядро по кругу.
            val net = com.zen.blaster.core.Resolver.physical()
            if (net == null) { ctx.errorCode(2); return }
            val all = try {
                net.getAllByName(name)
            } catch (_: java.net.UnknownHostException) {
                ctx.errorCode(3)                       // NXDOMAIN
                return
            }
            val want = all.filter {
                when (network) {
                    "ip4" -> it is java.net.Inet4Address
                    "ip6" -> it is java.net.Inet6Address
                    else -> true
                }
            }
            ctx.success(want.joinToString("\n") { it.hostAddress?.substringBefore('%').orEmpty() })
        }
    }
    override fun localDNSTransport(): LocalDNSTransport = localDns

    // Перечисление сетевых интерфейсов для ядра.
    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = ArrayList<io.nekohasekai.libbox.NetworkInterface>()
        // Что о каждом интерфейсе знает система: тип связи, DNS, платный ли.
        // Ядро 1.14 этим пользуется при выборе сети и для «local»-DNS.
        class Meta(val type: Int, val dns: List<String>, val metered: Boolean)
        val meta = HashMap<String, Meta>()
        try {
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val lp = cm.getLinkProperties(n) ?: continue
                val name = lp.interfaceName ?: continue
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                meta[name] = Meta(type,
                    lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        } catch (_: Exception) {}
        try {
            for (ni in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp) continue
                val box = io.nekohasekai.libbox.NetworkInterface()
                box.name = ni.name
                box.index = ni.index
                box.setMTU(try { ni.mtu } catch (_: Exception) { 1500 })
                // Go парсит адреса через netip.ParsePrefix — нужен ИМЕННО CIDR
                // ("10.1.2.3/24"), причём без зоны IPv6 ("%rmnet_data0"),
                // иначе ядро паникует: ParsePrefix(...): no '/'.
                val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val host = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    val len = ia.networkPrefixLength.toInt()
                    if (len < 0) null else "$host/$len"
                }
                box.addresses = StringList(addrs)
                // Флаги в формате Go net.Flags — БЕЗ FlagUp(1) ядро считает
                // интерфейс нерабочим → "no available network interface".
                var flags = 0
                if (ni.isUp) flags = flags or 0x01            // FlagUp
                if (ni.supportsMulticast()) flags = flags or 0x10 // FlagMulticast
                if (ni.isLoopback) flags = flags or 0x04      // FlagLoopback
                if (ni.isPointToPoint) flags = flags or 0x08  // FlagPointToPoint
                if (ni.isUp) flags = flags or 0x20            // FlagRunning
                box.flags = flags
                val m = meta[ni.name]
                box.type = m?.type ?: Libbox.InterfaceTypeOther
                box.setDNSServer(StringList(m?.dns ?: emptyList()))
                box.metered = m?.metered ?: false
                list.add(box)
            }
        } catch (_: Exception) {}
        return NetIfaceList(list)
    }

    // Монитор дефолтной сети — сообщает ядру активный интерфейс.
    //
    // ГЛАВНОЕ: спрашивать систему про лучшую сеть БЕЗ VPN, а не про «сеть по
    // умолчанию» приложения. Пока туннель поднят, сеть по умолчанию для нас
    // самих — это наш же туннель (приложение исключено из него не всегда, а
    // только в связке с Xray или щитом). Раньше монитор видел tun0, пропускал
    // его — и больше НИЧЕГО ядру не сообщал. Стоило оператору перевесить
    // мобильный интерфейс (rmnet_data1 → rmnet_data2, у Tele2 и МТС это
    // будни на слабом сигнале), ядро продолжало звонить в исчезнувший:
    // «dial rmnet_data1: network is unreachable», «no available network
    // interface» — каждое соединение мимо, хотя сеть жива. В журнале за
    // 14.10.2026 таких строк сотни; со стороны — «интернета нет весь день».
    //
    // registerBestMatchingNetworkCallback (Android 12+) отдаёт ровно одну
    // сеть — ту, которую система сама выбрала бы для запроса «интернет, не
    // VPN», то есть настоящий физический канал, и сообщает о его смене. На
    // старых Android то же делает requestNetwork. Так устроено и в
    // официальном клиенте sing-box.
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile var current: String = ""
            override fun onAvailable(network: Network) = report(network, cm.getLinkProperties(network))
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = report(network, lp)
            override fun onLost(network: Network) {
                // Сеть ушла — пусть ядро не держится за мёртвый интерфейс.
                current = ""
                currentNet = null
                try { listener.updateDefaultInterface("", -1, false, false) } catch (_: Exception) {}
            }
            @Volatile var currentNet: Network? = null
            private fun report(network: Network, lp: LinkProperties?) {
                val name = lp?.interfaceName ?: return
                if (name.startsWith("tun")) return          // это наш же туннель — не годится
                val idx = try { java.net.NetworkInterface.getByName(name)?.index ?: -1 } catch (_: Exception) { -1 }
                if (idx <= 0) return
                if (name != current && current.isNotEmpty())
                    com.zen.blaster.core.Blackbox.note("🔁 Физическая сеть сменилась: $current → $name — сообщил ядру")
                // Та же сеть по имени, но НОВАЯ сессия данных. Оператор
                // пересоздаёт сессию на том же rmnet_dataN с новым адресом
                // (15.10.2026 — десятки раз за день: NO_GPRS_CONTEXT,
                // EMM_DETACHED). Ядро видит прежние имя и индекс и соединения
                // не сбрасывает: Hysteria2 висит на мёртвом QUIC до тайм-аута,
                // для человека — «лаги» на полминуты и дольше. Сбрасываем сами.
                val renewed = currentNet != null && currentNet != network && name == current
                currentNet = network
                current = name
                try { listener.updateDefaultInterface(name, idx, false, false) } catch (_: Exception) {}
                if (renewed) {
                    boxService?.resetNetwork()
                    com.zen.blaster.core.Blackbox.note("♻️ Новая сессия данных на $name — сбросил соединения ядра")
                }
            }
        }
        netCallback = cb
        val req = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(req, cb, h)
            else cm.requestNetwork(req, cb, h)
            // Первый отчёт — сразу, не дожидаясь системы: ядро стартует
            // прямо сейчас, и без интерфейса его первые соединения пропадут.
            com.zen.blaster.core.Resolver.physical()?.let { n -> cb.onAvailable(n) }
        } catch (_: Exception) {
            try { cm.registerDefaultNetworkCallback(cb); cm.activeNetwork?.let { n -> cb.onAvailable(n) } }
            catch (_: Exception) {}
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
    }

    // Итераторы для контракта libbox.
    private class StringList(private val items: List<String>) : StringIterator {
        private var i = 0
        override fun hasNext() = i < items.size
        override fun next(): String = items[i++]
        override fun len() = items.size
    }
    private class NetIfaceList(private val items: List<io.nekohasekai.libbox.NetworkInterface>) : NetworkInterfaceIterator {
        private var i = 0
        override fun hasNext() = i < items.size
        override fun next(): io.nekohasekai.libbox.NetworkInterface = items[i++]
    }

    // ---------------------------------------------------------- уведомление

    // Уведомление в шторке: сервер + режим, скорость ↓/↑, пинг, время сессии.
    @Volatile private var channelReady = false

    private fun notif(overrideText: String?): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelReady) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
            channelReady = true
        }
        // SINGLE_TOP + CLEAR_TOP: тап по уведомлению должен вернуть уже открытое
        // окно, а не создать второе — иначе экран стартует с нуля и показывает
        // «Подключиться» при работающем туннеле.
        val pi = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)

        // Кнопка «Отключить» прямо из шторки.
        val stopPi = android.app.PendingIntent.getService(this, 1,
            Intent(this, ZenVpnService::class.java).apply { action = ACTION_STOP },
            android.app.PendingIntent.FLAG_IMMUTABLE)

        // Кнопка «Метка» — она же мост к тому, кто будет разбирать журнал.
        //
        // Странность видна В МОМЕНТ, когда она случилась, и именно тогда до
        // приложения обычно не добраться: тестируют на ходу, экран занят
        // чем-то другим, а «сейчас отвалилось» через час уже не найти среди
        // сотен строк. Из шторки — два касания, не отрываясь от того, что
        // делаешь.
        val markPi = android.app.PendingIntent.getService(this, 2,
            Intent(this, ZenVpnService::class.java).apply { action = ACTION_MARK },
            android.app.PendingIntent.FLAG_IMMUTABLE)

        val srv = Stats.server.value.ifEmpty { "ZEN NITRO" }
        val pingTxt = if (Stats.shownPing.value > 0) "${Stats.shownPing.value} мс" else "—"
        val text = overrideText ?: buildString {
            append("↓ ").append(Stats.fmtSpeed(Stats.down.value))
            append("   ↑ ").append(Stats.fmtSpeed(Stats.up.value))
            append("   •   ").append(pingTxt)
        }
        val sub = if (overrideText != null) Stats.mode.value
                  else "${Stats.mode.value} · ${Stats.fmtDuration()}"

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(srv)
            .setContentText(text)
            .setSubText(sub)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Отключить", stopPi)
            .addAction(0, "Метка", markPi)
            .build()
    }

    @Volatile private var lastNotifKey = ""
    @Volatile private var lastNotifAt = 0L

    // Перерисовываем уведомление ТОЛЬКО когда изменилось что-то значимое.
    //
    // Защита «обновлять при изменении» тут уже стояла, но не работала: в ключ
    // входила Stats.fmtDuration() — время подключения, которое тикает КАЖДУЮ
    // секунду. Ключ поэтому отличался всегда, и MIUI по-прежнему пересобирал
    // карточку раз в секунду. Отсюда подвисания шторки, мигание и пустые
    // карточки. Длительность из ключа убрана: она всё равно обновится вместе
    // со следующим изменением скорости или пинга.
    //
    // Второй слой — ограничение частоты: не чаще раза в 2 секунды. Скорость
    // скачет постоянно, и без этого ключ менялся бы почти каждую секунду.
    private fun updateNotif(text: String?) {
        try {
            // Скорости огрубляем до 8 КБ, пинг — до 25 мс: рябь в младших
            // разрядах не должна дёргать шторку.
            val key = text ?: "${Stats.server.value}|${Stats.mode.value}|" +
                "${Stats.down.value / 8192}|${Stats.up.value / 8192}|${Stats.shownPing.value}"
            val now = System.currentTimeMillis()
            val forced = text != null                     // явный текст — показываем сразу
            if (!forced && key == lastNotifKey) return
            if (!forced && now - lastNotifAt < 2000) return
            lastNotifKey = key
            lastNotifAt = now
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notif(text))
        } catch (_: Throwable) {}
    }
}
