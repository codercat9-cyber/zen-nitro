package com.zen.blaster.core

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

// ============================================================================
//  СОКЕТ МИМО НАШЕГО ЖЕ ТУННЕЛЯ.
//
//  Пинги нод, проверка линии, выбор запасной ноды, скачивание обновления —
//  всё это обязано идти по настоящей сети оператора, а не через туннель.
//  Раньше для этого сокет привязывали к физической сети (Network.bindSocket).
//
//  Работало это только тогда, когда приложение само выведено из туннеля, —
//  а выведено оно лишь при Xray и DPI-щите. На чистом sing-box (Hysteria2,
//  TUIC и т. п.) приложение сидит ВНУТРИ своего туннеля, и Android такую
//  привязку запрещает: «Binding socket to network failed: EPERM». Журнал
//  09.11.2026: в 07:56 «замер задержки: отвечают 0 из 134» — ни одна нода
//  не «ответила», хотя все были живы; проверка линии писала «TCP тишина 1 мс,
//  сеть сама не работает» и жгла живые ноды в конец каскада; обновление «не
//  качалось, пока не выключишь VPN».
//
//  Правильно — как делает само ядро: VpnService.protect(). Владелец VPN
//  вправе вывести из туннеля любой свой сокет. Нет туннеля — привязываем к
//  физической сети, как раньше.
// ============================================================================
object Bypass {

    // Чем защищать: служба VPN кладёт сюда свой protect(), пока жива.
    @Volatile var protector: ((Socket) -> Boolean)? = null
    @Volatile var protectorUdp: ((DatagramSocket) -> Boolean)? = null

    // Вывести TCP-сокет из туннеля. Сокет ещё не соединён.
    fun arm(s: Socket): Socket {
        // Дескриптора у нового Socket() ещё нет — любой запрос свойства его
        // заводит (так делает и сам Network.bindSocket).
        try { s.reuseAddress } catch (_: Exception) {}
        protector?.let { p -> if (try { p(s) } catch (_: Throwable) { false }) return s }
        Resolver.physical()?.let { n -> try { n.bindSocket(s) } catch (_: Throwable) {} }
        return s
    }

    fun socket(): Socket = arm(Socket())

    fun datagram(): DatagramSocket {
        val d = DatagramSocket()
        protectorUdp?.let { p -> if (try { p(d) } catch (_: Throwable) { false }) return d }
        Resolver.physical()?.let { n -> try { n.bindSocket(d) } catch (_: Throwable) {} }
        return d
    }

    // Для OkHttp: он берёт несоединённый сокет и соединяет сам.
    val factory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = socket()
        override fun createSocket(host: String, port: Int): Socket =
            socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket =
            socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
    }

    // Готовый клиент мимо туннеля (на базе своего, если он есть).
    fun http(base: okhttp3.OkHttpClient? = null, timeoutMs: Long = 4000): okhttp3.OkHttpClient =
        (base?.newBuilder() ?: okhttp3.OkHttpClient.Builder()
            .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
            .socketFactory(factory).dns(dns).proxy(java.net.Proxy.NO_PROXY).build()

    // Имя — шифрованным DNS (он сам ходит мимо туннеля); не вышло — DNS
    // физической сети. Системный DNS у приложения внутри туннеля ушёл бы в
    // туннель.
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (Resolver.isIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))
            Resolver.resolve(hostname)?.let { return listOf(InetAddress.getByName(it)) }
            Resolver.physical()?.let { n -> try { return n.getAllByName(hostname).toList() } catch (_: Exception) {} }
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }
}
