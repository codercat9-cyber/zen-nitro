package com.zen.blaster.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

// ============================================================================
//  Управление DNS-кешами и проверка, не врёт ли DNS.
//
//  ТСПУ отравляет DNS: перехватывает открытые запросы на 53-й порт и отвечает
//  раньше настоящего сервера — заглушкой, NXDOMAIN или чужим адресом, с
//  большим TTL. Устройство запоминает мусор и держит его часами.
//
//  ЧТО МЫ МОЖЕМ И ЧЕГО НЕ МОЖЕМ — честно, чтобы кнопка не обещала лишнего.
//
//   • Свой кеш (Resolver) — чистим полностью. Это адреса нод, которые мы
//     разрешаем ДО туннеля. Нужно, когда провайдер переехал на новый IP или
//     когда сменилась сеть и на ней ожил DoH.
//
//   • Кеш ядра sing-box — через управляющий API НЕ чистится: в нашей версии
//     ядра такого адреса нет вовсе (проверено по бинарнику 1 октября 2026).
//     Первая версия этого модуля стучалась в несуществующий /dns/flush,
//     получала отказ — и всё равно писала «кеш сброшен». Единственный
//     настоящий сброс кеша ядра — поднять ядро заново. Кнопка так и делает,
//     если туннель поднят.
//
//   • Системный кеш Android — без root не чистится никак. Но он нам и не
//     мешает: пока туннель поднят, все приложения спрашивают имена у ядра
//     (172.19.0.2), а система при каждом подъёме туннеля заводит новую сеть с
//     чистым кешем. Первая версия модуля дёргала reportBadNetwork() на
//     «активную сеть» — а под туннелем активная сеть это САМ ТУННЕЛЬ, то
//     есть мы жаловались системе на собственный VPN. Убрано.
// ============================================================================
object DnsCacheManager {

    private const val TAG = "DnsCacheManager"

    @Volatile var lastFlushAt: Long = 0L; private set

    // 0 — не проверяли, 1 — здоров, 2 — медленный, 3 — DoH не отвечает.
    @Volatile var healthStatus: Int = 0; private set
    @Volatile var healthCheckedAt: Long = 0L; private set

    @Volatile var onLog: ((String) -> Unit)? = null

    private fun say(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    // ======================================================================
    //  СБРОС
    // ======================================================================
    fun flush() {
        Resolver.forgetAll()
        // Метка «DoH мёртв» держится минуту. После смены сети или ручного
        // сброса её надо снимать: иначе клиент ещё минуту ходит в системный
        // резолвер оператора, хотя DoH уже снова доступен.
        Resolver.resetDeadMark()
        lastFlushAt = System.currentTimeMillis()
        say("🧹 Кеш адресов сброшен — имена нод разрешатся заново")
    }

    private fun flushQuiet() {
        Resolver.forgetAll()
        Resolver.resetDeadMark()
        lastFlushAt = System.currentTimeMillis()
    }

    // ======================================================================
    //  ПРОВЕРКА: РАБОТАЕТ ЛИ НАШ DNS И ВРЁТ ЛИ DNS ОПЕРАТОРА
    //
    //  Два разных вопроса, и раньше они были свалены в один.
    //
    //  1. Работает ли НАШ шифрованный резолвер. От него зависит, встанут ли
    //     ноды с доменным адресом. Это и есть цвет индикатора: если он не
    //     отвечает — беда настоящая, человеку надо менять резолвер.
    //
    //  2. Подменяет ли ответы DNS ОПЕРАТОРА. Это справка, а не тревога: мы
    //     этим резолвером не пользуемся именно потому, что он врёт. Красить
    //     из-за этого индикатор в красный значило бы пугать человека, у
    //     которого всё работает.
    //
    //  Первая версия проверяла оператора на example.com, cloudflare.com и
    //  google.com. Их никто не блокирует — ТСПУ на них не врёт, и проверка не
    //  могла найти подмену никогда. Вдобавок «системный» запрос под поднятым
    //  туннелем уходил в наше же ядро, а не к оператору. Теперь спрашиваем
    //  оператора напрямую, по физической сети, и про заведомо
    //  заблокированные домены — там, где подменять есть что.
    // ======================================================================

    // Домены, которые наш DoH ДОЛЖЕН разрешать всегда.
    private val ALIVE = listOf("example.com", "wikipedia.org", "github.com")

    // Заблокированные в РФ давно и прочно — ровно на них оператор подменяет,
    // если подменяет вообще.
    private val BLOCKED = listOf("rutracker.org", "linkedin.com", "facebook.com")

    data class Report(
        val status: Int,            // 1 / 2 / 3, см. healthStatus
        val dohMs: Long,
        val dohOk: Int,
        val operatorLies: Int,      // на скольких заблокированных доменах оператор подменил ответ
        val operatorChecked: Int    // сколько удалось спросить у оператора
    )

    fun checkHealth(): Report {
        // Спрашиваем мимо кеша: из него ответы были бы мгновенными и ничего
        // бы не доказали. Но сам кеш не трогаем — проверка теперь ходит и
        // автоматически, после подъёма, и стирать адреса нод ей незачем.
        val t0 = System.currentTimeMillis()
        val dohOk = ALIVE.count { Resolver.resolveFresh(it) != null }
        val dohMs = System.currentTimeMillis() - t0

        var lies = 0
        var checked = 0
        val net = Resolver.physical()
        if (net != null) for (host in BLOCKED) {
            val real = Resolver.resolveFresh(host) ?: continue    // сравнить не с чем
            checked++
            val op = operatorResolve(net, host)
            // Подмена — это служебный адрес вместо публичного, либо «такого
            // домена нет» при том, что он есть. Просто ДРУГОЙ публичный адрес
            // подменой не считаем: у крупных сервисов адресов сотни, и разные
            // резолверы честно отдают разные.
            if (op == null || isServiceAddress(op)) {
                lies++
                Log.w(TAG, "оператор: $host → ${op ?: "нет ответа"} (DoH: $real)")
            }
        }

        val status = when {
            dohOk == 0 -> 3
            dohOk < ALIVE.size || dohMs > 3000 -> 2
            else -> 1
        }
        healthStatus = status
        healthCheckedAt = System.currentTimeMillis()

        when (status) {
            1 -> say("🟢 Шифрованный DNS работает: ${ALIVE.size} из ${ALIVE.size} за $dohMs мс")
            2 -> say("🟡 Шифрованный DNS отвечает с трудом: $dohOk из ${ALIVE.size} за $dohMs мс")
            else -> {
                say("🔴 Шифрованный DNS не отвечает — ноды с доменным адресом не встанут.")
                say("   Смените резолвер: Настройки → Резолвер DNS → «Проверить все».")
            }
        }
        when {
            checked == 0 -> say("   DNS оператора проверить не удалось")
            lies > 0 -> say("   DNS оператора подменяет ответы ($lies из $checked) — " +
                "поэтому мы им и не пользуемся")
            else -> say("   DNS оператора отвечает честно — блокируют не через DNS, " +
                "а по адресу и имени в рукопожатии")
        }
        return Report(status, dohMs, dohOk, lies, checked)
    }

    // Служебные и частные адреса: на публичный домен их честный резолвер не
    // отдаст никогда. Ровно такими и бывают заглушки — 127.0.0.1, 0.0.0.0,
    // 10.10.34.x у НСДИ и прочие внутренние адреса оператора.
    private fun isServiceAddress(ip: String) = Resolver.isServiceAddress(ip)

    // Спросить DNS ОПЕРАТОРА — именно той сети, по которой ходит телефон, в
    // обход любого туннеля. null — домена «нет» или ответа нет.
    private fun operatorResolve(net: Network, host: String): String? = try {
        net.getAllByName(host).firstOrNull { it is java.net.Inet4Address }?.hostAddress
    } catch (_: Throwable) { null }

    // ======================================================================
    //  АВТОСБРОС ПРИ СМЕНЕ НАСТОЯЩЕЙ СЕТИ
    //
    //  Слушаем ФИЗИЧЕСКИЕ сети, а не «сеть по умолчанию». Под туннелем сеть
    //  по умолчанию — это сам туннель, и первая версия считала каждый наш же
    //  подъём VPN «сменой сети»: сбрасывала кеш ровно в тот момент, когда он
    //  нужнее всего, и жаловалась системе на собственный туннель.
    // ======================================================================
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastFlushMono = 0L
    @Volatile private var mainNet: Network? = null
    @Volatile private var enabled = true

    fun setAutoFlush(on: Boolean) { enabled = on }

    fun startWatching(ctx: Context) {
        stopWatching(ctx)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        // Сразу после подписки система ОТЧИТЫВАЕТСЯ о текущем положении: по
        // событию на каждую живую сеть. У телефона на Wi-Fi со включённой
        // передачей данных их две — и первая версия принимала этот отчёт за
        // пересадку, сбрасывая кеш на каждом подъёме туннеля (проверено на
        // телефоне 1 октября 2026). Поэтому первые секунды только слушаем.
        val armedAt = android.os.SystemClock.elapsedRealtime() + 3_000
        mainNet = Resolver.physical()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            private fun changed() {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!enabled || now < armedAt) return
                // Событие — ещё не пересадка. Android, убедившись, что Wi-Fi
                // жив, отпускает фоновую сотовую: приходит «сеть пропала», а
                // телефон как ходил по Wi-Fi, так и ходит. На проверке
                // 1 октября 2026 это давало «сеть сменилась» через девять
                // секунд после подъёма. Сбрасываем, только если сменилась
                // сеть, по которой телефон реально ходит.
                val cur = Resolver.physical()
                if (cur == mainNet) return
                mainNet = cur
                // Пересадка — это пачка событий за пару секунд: одна сеть
                // ушла, другая пришла. Сбрасываем один раз на пачку.
                if (now - lastFlushMono < 5_000) return
                lastFlushMono = now
                flushQuiet()
                say("🧹 Сеть сменилась — кеш адресов сброшен")
            }
        }
        netCallback = cb
        try { cm.registerNetworkCallback(req, cb) }
        catch (e: Throwable) { Log.w(TAG, "registerNetworkCallback: ${e.message}") }
    }

    fun stopWatching(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
    }
}
