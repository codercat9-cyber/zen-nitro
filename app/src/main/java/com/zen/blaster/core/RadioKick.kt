package com.zen.blaster.core

import android.content.Context

// ============================================================================
//  «Встряска радио» — приём из keepmts.sh оператора (Termux-скрипт для МТС).
//
//  Сотовый модем экономит батарею: без трафика он уводит соединение в
//  спячку (RRC idle), а при слабом сигнале сота сбрасывает телефон с LTE на
//  3G/EDGE и держит там, пока по каналу не пойдёт заметный поток. Короткий
//  рывок трафика заставляет соту выдать ресурсы заново — канал «просыпается»
//  и возвращается на нормальную полосу. Туннель сам этого не делает: его
//  служебный замер — пара сотен байт, для соты это не повод.
//
//  Что делаем (по физической сети, мимо туннеля — будим именно радио):
//   • HTTP-рывок на ~256 КБ к speedtest Selectel. Он в белых списках
//     операторов: даже в день ограничений рывок дойдёт, а не упрётся в ТСПУ.
//   • Пара DNS-запросов к разрешённым именам — «стимуляция роутинга».
//
//  От скрипта сознательно НЕ берём: пинг каждые 2 секунды и мусор в UDP:53.
//  В фоне у тысяч людей это съест батарею и похоже на флуд. Здесь рывок
//  только по поводу (канал замолчал, пинг подскочил) и не чаще раза в три
//  минуты. Только на мобильной сети: на Wi-Fi будить нечего.
// ============================================================================
object RadioKick {

    // В keepmts.sh стоял «/10MB.bin» — на 14.10.2026 он отдаёт 404, и рывок
    // скрипта давно ничего не качал. Живой адрес — без «.bin». Запасной —
    // статика Яндекса: тоже в белых списках, ~85 КБ.
    private val URLS = listOf(
        "https://speedtest.selectel.ru/10MB",
        "https://yastatic.net/jquery/3.3.1/jquery.min.js"
    )
    private const val BYTES = 262_143
    private const val MIN_GAP = 3 * 60_000L

    @Volatile private var lastAt = 0L

    // true — рывок запущен; false — рано, не мобильная сеть или нет сети.
    fun kick(ctx: Context, why: String, onLog: (String) -> Unit = {}): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_GAP) return false
        val net = Resolver.physical() ?: return false
        val mobile = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.getNetworkCapabilities(net)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (_: Throwable) { false }
        if (!mobile) return false
        lastAt = now
        kotlin.concurrent.thread(name = "radio-kick", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            // DNS — параллельно и молча: важен сам факт запроса.
            for (h in listOf("ya.ru", "gosuslugi.ru"))
                kotlin.concurrent.thread(isDaemon = true) { try { net.getAllByName(h) } catch (_: Throwable) {} }
            var got = 0L
            for (u in URLS) {
                try {
                    val c = net.openConnection(java.net.URL(u)) as java.net.HttpURLConnection
                    c.connectTimeout = 4000
                    c.readTimeout = 4000
                    c.setRequestProperty("Range", "bytes=0-$BYTES")
                    if (c.responseCode in 200..299) c.inputStream.use { s ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = s.read(buf); if (n < 0) break
                            got += n
                            if (System.currentTimeMillis() - t0 > 5000) break
                        }
                    }
                    c.disconnect()
                } catch (_: Throwable) {}
                if (got > 0) break
            }
            val ms = System.currentTimeMillis() - t0
            val msg = if (got > 0) "📡 Встряхнул радио ($why): ${got / 1024} КБ за $ms мс"
                      else "📡 Встряхнул радио ($why): рывок не прошёл — сеть оператора сама молчит"
            onLog(msg)
            Blackbox.note(msg)
        }
        return true
    }
}
