package com.zen.blaster.core

import android.content.Context

// ============================================================================
//  «Подогрев» радио на узком канале — пока человек смотрит в экран.
//
//  На 3G (H/H+) и EDGE модем без трафика за секунды уходит в экономное
//  состояние, и каждое новое действие — тап по ссылке, следующее сообщение
//  — сначала ждёт, пока радио проснётся: на 3G это 1,5–2,5 секунды, на EDGE
//  заново поднимается канал передачи. Со стороны выглядит как «VPN
//  буксует», хотя туннель жив. Туннель сам радио не держит: его служебный
//  трафик — пакет раз в десятки секунд.
//
//  Приём из keepmts.sh оператора (там пинг каждые 2 секунды круглые сутки),
//  но бережно: крошечный DNS-запрос по ФИЗИЧЕСКОЙ сети раз в 4 секунды и
//  только когда всё сразу —
//    • туннель поднят (держать радио ради ничего незачем);
//    • экран включён (человек пользуется прямо сейчас);
//    • сотовая сеть и канал узкий по оценке системы.
//  Погас экран, ожил канал, отключили туннель — подогрев засыпает сам.
//  Цена — ~100 байт в 4 секунды при горящем экране: на фоне экрана заряда
//  не видно.
// ============================================================================
object RadioKeeper {

    private const val PERIOD_MS = 4_000L
    // DNS Яндекса: в белых списках у всех операторов, отвечает из России.
    private val TARGET = java.net.InetSocketAddress("77.88.8.8", 53)

    @Volatile private var thread: Thread? = null
    @Volatile private var active = false

    // Зовут сервисы туннеля: при подъёме — start, при остановке — stop.
    @Synchronized
    fun start(ctx: Context, onLog: (String) -> Unit) {
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        active = true
        thread = kotlin.concurrent.thread(name = "radio-keeper", isDaemon = true) {
            var warming = false
            while (active) {
                val need = needed(app)
                if (need != warming) {
                    warming = need
                    if (need) onLog("🔆 Канал узкий — держу радио бодрым, пока горит экран")
                }
                if (need) poke()
                try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    @Synchronized
    fun stop() {
        active = false
        thread?.interrupt()
        thread = null
    }

    private fun needed(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive && NetInfo.slowMobile(ctx)
    } catch (_: Throwable) { false }

    // Один DNS-запрос «ya.ru A» мимо туннеля. Ответ ждём недолго: важен
    // сам обмен — он и держит модем в рабочем состоянии.
    private fun poke() {
        val net = Resolver.physical() ?: return
        try {
            java.net.DatagramSocket().use { s ->
                net.bindSocket(s)
                s.soTimeout = 1500
                val q = query()
                s.send(java.net.DatagramPacket(q, q.size, TARGET))
                try { s.receive(java.net.DatagramPacket(ByteArray(512), 512)) } catch (_: Exception) {}
            }
        } catch (_: Throwable) {}
    }

    private fun query(): ByteArray {
        val id = (0..0xFFFF).random()
        val head = byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        val name = byteArrayOf(2, 'y'.code.toByte(), 'a'.code.toByte(), 2, 'r'.code.toByte(), 'u'.code.toByte(), 0)
        return head + name + byteArrayOf(0, 1, 0, 1)
    }
}
