package com.zen.blaster.core

// ============================================================================
//  СЛЕД ЯДРА — что говорили Xray и sing-box, пока канал жил и умирал.
//
//  Проверка линии смотрит снаружи, а ядро видит изнутри: «dial tcp … i/o
//  timeout», «reality verification failed», «connection reset». 09.11.2026
//  «АВТО ВЫБОР» Пантеры висел две минуты, и вся правда была в логе Xray:
//  двадцать «dialing TCP to 103.76.130.95:445» подряд и ни одного «tunneling
//  request» — соединение с сервером даже не открывалось. В журнал это не
//  попадало (уровень Info), и мы гадали.
//
//  Здесь копятся последние жалобы ядра (без меток времени и номеров
//  соединений, с подсчётом повторов) и счёт дозвонов до сервера против
//  открывшихся соединений. Разбор обрыва печатает это рядом с дампом.
// ============================================================================
object CoreTrail {

    private class Hit(val text: String, var count: Int, var last: Long)

    private val hits = LinkedHashMap<String, Hit>()
    private val dials = ArrayDeque<Long>()
    private val opened = ArrayDeque<Long>()
    private const val KEEP = 10
    private const val WINDOW = 120_000L

    private val NOISE = Regex(
        "\\u001B\\[[0-9;]*m|^\\S+ \\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d(\\.\\d+)? |" +
        "^\\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d(\\.\\d+)? |\\[\\d{5,}\\] |\\[Info\\] |\\[Warning\\] |\\[Error\\] |" +
        "^\\+\\d{4} \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d |\\[\\d+ \\d+ms\\] |ERROR\\[\\d+\\] |WARN\\[\\d+\\] |" +
        "app/proxyman/outbound: |\\[Xray\\] ")
    private val BAD = Regex("(?i)fail|error|timeout|timed out|refused|reset|eof|unreachable|rejected|" +
        "no route|deadline|denied|verification|handshake|closed pipe|broken pipe|no available|missing default")
    private val MUTE = Regex("(?i)use of closed network connection|context canceled|Connection closed immediately|deprecated")

    @Synchronized fun reset() { hits.clear(); dials.clear(); opened.clear() }

    // Каждая строка ядра. Дёшево: сотни строк в минуту.
    fun feed(raw: String) {
        if (raw.contains("dialing TCP to") || raw.contains("dialing UDP to")) { mark(dials); return }
        if (raw.contains("tunneling request to")) { mark(opened); return }
        if (!BAD.containsMatchIn(raw) || MUTE.containsMatchIn(raw)) return
        val text = NOISE.replace(raw, "").trim().take(160)
        if (text.isEmpty()) return
        // Ключ без чисел: «dial tcp 1.2.3.4:445: i/o timeout» с разными
        // портами источника — одна и та же беда.
        val key = text.replace(Regex("\\d+"), "#")
        synchronized(this) {
            val now = System.currentTimeMillis()
            val h = hits.remove(key)?.also { it.count++; it.last = now } ?: Hit(text, 1, now)
            hits[key] = h
            while (hits.size > KEEP) hits.remove(hits.keys.first())
        }
    }

    @Synchronized private fun mark(q: ArrayDeque<Long>) {
        val now = System.currentTimeMillis()
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > WINDOW) q.removeFirst()
    }

    // Строки для журнала: счёт дозвонов и последние жалобы, свежие — внизу.
    @Synchronized fun lines(maxAgeMs: Long = 180_000L): List<String> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<String>()
        dialLine()?.let { out += it }
        hits.values.filter { now - it.last <= maxAgeMs }.takeLast(6).forEach {
            out += "ядро ×${it.count} ${(now - it.last) / 1000} с назад: ${it.text}"
        }
        return out
    }

    @Synchronized private fun dialLine(): String? {
        val now = System.currentTimeMillis()
        val d = dials.count { now - it <= WINDOW }
        val o = opened.count { now - it <= WINDOW }
        if (d == 0 && o == 0) return null
        return "ядро за 2 мин: дозвонов до сервера $d, открылось соединений $o" +
            if (d >= 3 && o == 0) " — до сервера НЕ ДОЗВАНИВАЕТСЯ (SYN без ответа или рукопожатие виснет)" else ""
    }

    // Для машинной строки #DIAG.
    @Synchronized fun code(): String {
        val now = System.currentTimeMillis()
        val d = dials.count { now - it <= WINDOW }
        val o = opened.count { now - it <= WINDOW }
        val top = hits.values.lastOrNull()?.text?.let { t ->
            when {
                Regex("(?i)i/o timeout|timed out|deadline").containsMatchIn(t) -> "timeout"
                Regex("(?i)refused").containsMatchIn(t) -> "refused"
                Regex("(?i)reset").containsMatchIn(t) -> "reset"
                Regex("(?i)reality verification").containsMatchIn(t) -> "reality_verify"
                Regex("(?i)authentication|auth").containsMatchIn(t) -> "auth"
                Regex("(?i)no available|available destination").containsMatchIn(t) -> "no_dest"
                Regex("(?i)eof").containsMatchIn(t) -> "eof"
                Regex("(?i)unreachable|no route|missing default").containsMatchIn(t) -> "unreachable"
                else -> "other"
            }
        } ?: "-"
        return "dial=$d/open=$o/last=$top"
    }
}
