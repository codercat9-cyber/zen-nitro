package com.zen.blaster.vpn

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File

// ============================================================================
//  Один запуск ядра sing-box 1.14.
//
//  В 1.11 было просто: Libbox.newService(config) → start() → close(), а
//  журнал ядро само отдавало через PlatformInterface.writeLog. В 1.14
//  ядро живёт внутри CommandServer — сервера управления на unix-сокете
//  (files/command.sock, доступ только у нашего приложения), а журнал
//  уходит подписчикам этого сервера. Поэтому на каждый запуск у нас пара:
//  сервер с ядром и свой клиент, который читает журнал и кормит им
//  sayCore — разбор ошибок сертификата, мёртвого интерфейса и прочего.
//
//  Экземпляр одноразовый, как прежний BoxService: новый подъём — новый
//  BoxCore. Тогда зависшее при закрытии ядро держит только свой сервер и
//  не мешает следующему (см. ZenVpnService.closeBox).
// ============================================================================
class BoxCore(
    private val platform: PlatformInterface,
    private val onLog: (String) -> Unit,
) : CommandServerHandler {

    companion object {
        @Volatile private var setupDone = false

        // Глобальная настройка libbox — раз на процесс: Setup каждый раз
        // заново перенаправляет stderr в файл, и повтор копил бы дескрипторы.
        @Synchronized
        fun setup(filesDir: File, cacheDir: File) {
            if (setupDone) return
            val o = SetupOptions()
            o.basePath = filesDir.absolutePath
            o.workingPath = File(filesDir, "work").apply { mkdirs() }.absolutePath
            o.tempPath = cacheDir.absolutePath
            // Вызовы из потоков Java ядро уводит в свои горутины: у потоков
            // Android маленький стек, и Go на нём однажды падал (go#68760).
            o.fixAndroidStack = true
            // Сколько строк журнала сервер помнит для подписчика, который
            // подключился позже первых строк. Мы подключаемся сразу, но
            // запас нужен на случай переподключения клиента.
            o.logMaxLines = 300
            o.crashReportSource = "zen"
            Libbox.setup(o)
            setupDone = true
        }
    }

    @Volatile private var server: CommandServer? = null
    @Volatile private var client: CommandClient? = null
    private val detached = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    // Бросает исключение, если ядро конфиг не приняло или не поднялось:
    // текст ошибки — тот же, что раньше давал newService/start.
    fun start(config: String) {
        val s = Libbox.newCommandServer(this, platform)
        server = s
        s.start()
        s.startOrReloadService(config, OverrideOptions().apply {
            autoRedirect = false
            includePackage = Strings(emptyList())
            excludePackage = Strings(emptyList())
        })
        attachLog()
    }

    // Клиент журнала — ПОСЛЕ запуска ядра, не раньше.
    //
    // Подписка первым делом спрашивает у ядра уровень журнала, а пока ядра
    // нет, сервер отвечает «invalid argument» — и поток журнала молча
    // закрывается навсегда. Проверено на стенде с тем же libbox: при
    // подключении до старта журнал пуст в пяти подъёмах из шести. Строки
    // самого подъёма не теряются: сервер держит их (logMaxLines) и отдаёт
    // новому подписчику первой пачкой.
    private fun attachLog() {
        val opts = CommandClientOptions()
        opts.addCommand(Libbox.CommandLog)
        val c = Libbox.newCommandClient(LogSink(), opts)
        client = c
        try { c.connect() } catch (t: Throwable) {
            onLog("⚠️ Журнал ядра недоступен: ${t.message}")
        }
    }

    // Сбросить все соединения ядра и переоткрыть подключения выходов
    // (Hysteria2/TUIC заводят новый QUIC с нового порта). Туннель и TUN
    // остаются — это не переподъём, а «встряска» самого ядра.
    fun resetNetwork() {
        if (detached.get()) return
        try { server?.resetNetwork() } catch (_: Throwable) {}
    }

    // Быстрая часть закрытия: отключить журнал и сервер управления. Не
    // висит — это просто закрытие сокетов. Делать до подъёма нового ядра:
    // уходящий сервер при закрытии удаляет файл command.sock, и если это
    // случится ПОСЛЕ того, как новый сервер его создал, новый останется
    // без адреса.
    fun detach() {
        if (!detached.compareAndSet(false, true)) return
        try { client?.disconnect() } catch (_: Throwable) {}
        client = null
        try { server?.close() } catch (_: Throwable) {}
    }

    // Медленная часть: закрыть само ядро. Ждёт закрытия всех соединений —
    // на мёртвом канале это секунды, поэтому зовётся из своего потока.
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        detach()
        try { server?.closeService() } catch (_: Throwable) {}
        server = null
    }

    // ---- CommandServerHandler: внешнего управления у нас нет ----
    override fun serviceStop() {}
    override fun serviceReload() {}
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) { message?.let(onLog) }
    override fun connectSSHAgent(): Int = throw UnsupportedOperationException("ssh agent")

    private inner class LogSink : CommandClientHandler {
        override fun connected() {}
        // Обрыв потока журнала — не обрыв туннеля, но без журнала сторожа
        // (сертификат, мёртвый интерфейс) слепнут. Скажем об этом в журнал.
        override fun disconnected(message: String?) {
            if (!detached.get() && !message.isNullOrEmpty()) onLog("⚠️ Поток журнала ядра оборвался: $message")
        }
        override fun setDefaultLogLevel(level: Int) {}
        override fun clearLogs() {}
        // Сервер команд отдаёт ВСЕ уровни, вплоть до trace, — отбирать должен
        // клиент. В 1.11 ядро само слало только уровень из конфига (info), и
        // без этого фильтра журнал на телефоне захлёбывался DEBUG/TRACE по
        // нескольку строк на каждое соединение. Уровни sing-box: panic 0,
        // fatal 1, error 2, warn 3, info 4, debug 5, trace 6.
        override fun writeLogs(messageList: LogIterator?) {
            val it = messageList ?: return
            while (it.hasNext()) {
                val e = it.next() ?: continue
                if (e.level > 4) continue
                val m = e.message ?: continue
                try { onLog(m) } catch (_: Throwable) {}
            }
        }
        override fun writeStatus(message: StatusMessage?) {}
        override fun writeGroups(message: OutboundGroupIterator?) {}
        override fun writeOutbounds(message: OutboundGroupItemIterator?) {}
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
    }

    class Strings(private val items: List<String>) : StringIterator {
        private var i = 0
        override fun hasNext() = i < items.size
        override fun next(): String = items[i++]
        override fun len() = items.size
    }
}
