package com.zen.blaster.core

// ============================================================================
//  БАЙТЫ НЕ ВРУТ.
//
//  «Канал не отвечает» — это наш пересказ. Что на самом деле пришло с
//  провода — ServerHello, тревога TLS, страница-заглушка оператора, сброс
//  или тишина — видно только в самих байтах. Поэтому в журнал идёт дамп:
//  по 16 байт в строке, hex и рядом ASCII, как в hexdump -C, — и сразу же
//  расшифровка того, что эти байты значат.
// ============================================================================
object Hex {

    // «16 03 03 00 7a 02 00 00 76 03 03 9b 3c 11 a0 5e |....z...v...<..^|»
    fun row(b: ByteArray, from: Int = 0, n: Int = 16): String {
        val end = minOf(b.size, from + n)
        if (end <= from) return "(пусто)"
        val hex = (from until end).joinToString(" ") { "%02x".format(b[it]) }
        val asc = (from until end).map { val c = b[it].toInt() and 0xff; if (c in 0x20..0x7e) c.toChar() else '.' }
            .joinToString("")
        return "$hex |$asc|"
    }

    // Несколько строк по 16 байт; дальше — сколько ещё осталось.
    fun rows(b: ByteArray, len: Int = b.size, max: Int = 32): List<String> {
        val n = minOf(len, b.size)
        val shown = minOf(n, max)
        val out = (0 until shown step 16).map { "%04x  %s".format(it, row(b, it, minOf(16, shown - it))) }
            .toMutableList()
        if (n > shown) out += "      … ещё ${n - shown} Б"
        return out
    }

    // ------------------------------------------------------- расшифровка

    private val ALERTS = mapOf(
        0 to "close_notify", 10 to "unexpected_message", 20 to "bad_record_mac",
        22 to "record_overflow", 40 to "handshake_failure", 42 to "bad_certificate",
        43 to "unsupported_certificate", 44 to "certificate_revoked", 45 to "certificate_expired",
        46 to "certificate_unknown", 47 to "illegal_parameter", 48 to "unknown_ca",
        49 to "access_denied", 50 to "decode_error", 51 to "decrypt_error",
        70 to "protocol_version", 71 to "insufficient_security", 80 to "internal_error",
        86 to "inappropriate_fallback", 90 to "user_canceled", 109 to "missing_extension",
        110 to "unsupported_extension", 112 to "unrecognized_name",
        116 to "certificate_required", 120 to "no_application_protocol")

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    // HelloRetryRequest — это ServerHello с особым «случайным» числом (RFC 8446 4.1.3).
    private val HRR = byteArrayOf(0xcf.toByte(), 0x21, 0xad.toByte(), 0x74, 0xe5.toByte(), 0x9a.toByte(), 0x61, 0x11)

    // Что это за ответ на TCP — по первым байтам.
    fun explainTcp(b: ByteArray, n: Int): String {
        if (n <= 0) return "пусто"
        val t = b[0].toInt() and 0xff
        if (n >= 5 && t in 0x14..0x17 && b[1].toInt() == 3) {
            val ver = "%02x%02x".format(b[1], b[2])
            val len = u16(b, 3)
            return when (t) {
                0x15 -> if (n >= 7) {
                    val lvl = if (b[5].toInt() == 2) "фатальная" else "предупреждение"
                    val d = b[6].toInt() and 0xff
                    "TLS-тревога $lvl: ${ALERTS[d] ?: "код $d"} ($d) — сервер ответил сам, но наше приветствие ему не понравилось"
                } else "TLS-тревога, запись $ver обрезана"
                0x16 -> if (n >= 6) when (b[5].toInt() and 0xff) {
                    2 -> if (n >= 19 && (0 until 8).all { b[11 + it] == HRR[it] })
                            "TLS HelloRetryRequest (запись $ver, $len Б) — сервер живой, просит другую группу ключей"
                         else "TLS ServerHello (запись $ver, $len Б) — сервер живой и отвечает по TLS"
                    else -> "TLS handshake тип ${b[5].toInt() and 0xff} (запись $ver, $len Б)"
                } else "TLS handshake (запись $ver)"
                0x17 -> "TLS application data ($len Б) — шифрованные данные"
                else -> "TLS ChangeCipherSpec"
            }
        }
        val text = String(b, 0, minOf(n, 512), Charsets.ISO_8859_1)
        if (text.startsWith("HTTP/")) {
            val status = text.lineSequence().first().trim()
            val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)
            val stub = loc != null && Regex("(?i)block|warning|zapret|rkn|eais|stop|forbidden").containsMatchIn(loc)
            return "HTTP вместо TLS: «$status»" + (loc?.let { " → $it" } ?: "") +
                if (stub) " — заглушка оператора/РКН" else " — отвечает не TLS-сервер"
        }
        if (text.startsWith("SSH-")) return "SSH-сервер: «${text.lineSequence().first().trim()}»"
        return "неизвестный протокол (первый байт 0x%02x)".format(t)
    }

    // Ответ QUIC на нашу проверку версии.
    // Version Negotiation: старший бит 1, версия 00000000, затем DCID/SCID и
    // список версий сервера по 4 байта (RFC 9000 17.2.1).
    class Vn(val ok: Boolean, val text: String)

    fun explainVn(b: ByteArray, n: Int, ourScid: ByteArray): Vn {
        if (n < 7) return Vn(false, "обрывок ($n Б) — не QUIC")
        val first = b[0].toInt() and 0xff
        if (first and 0x80 == 0) return Vn(false, "короткий заголовок 0x%02x — не ответ на нашу проверку".format(first))
        if ((1..4).any { b[it].toInt() != 0 })
            return Vn(false, "длинный заголовок, версия %02x%02x%02x%02x — не Version Negotiation".format(b[1], b[2], b[3], b[4]))
        var i = 5
        val dl = b[i].toInt() and 0xff; i++
        if (i + dl > n) return Vn(false, "Version Negotiation обрезан")
        val dcid = b.copyOfRange(i, i + dl); i += dl
        val sl = b[i].toInt() and 0xff; i++
        i += sl
        val vers = mutableListOf<String>()
        while (i + 4 <= n) {
            val v = "%02x%02x%02x%02x".format(b[i], b[i + 1], b[i + 2], b[i + 3])
            vers += when (v) {
                "00000001" -> "v1"; "6b3343cf" -> "v2"; "709a50c4" -> "v2-draft"
                "ff00001d" -> "draft-29"
                // ?a?a?a?a — «смазка» (RFC 9000 15): сервер нарочно называет
                // несуществующую версию, чтобы клиенты не ломались на чужих.
                else -> if (Regex("([0-9a-f]a){4}").matches(v)) "$v (смазка)" else v
            }
            i += 4
        }
        val ours = dcid.contentEquals(ourScid)
        return Vn(true, "QUIC Version Negotiation, версии сервера: ${vers.joinToString(", ").ifEmpty { "—" }}" +
            if (ours) " · ID наш — ответ настоящий, от сервера" else " · ID чужой — ответил кто-то посередине")
    }

    // Текст ошибки сокета → короткий код для машинной строки.
    fun errno(e: Throwable?): String {
        val m = (e?.message ?: "") + " " + (e?.cause?.message ?: "")
        Regex("E[A-Z]{3,}").find(m)?.value?.let { return it }
        if (m.contains("reset", true)) return "ECONNRESET"
        if (m.contains("broken pipe", true)) return "EPIPE"
        return when (e) {
            is java.net.SocketTimeoutException -> "ETIMEDOUT"
            is java.net.ConnectException -> "ECONNREFUSED"
            is java.net.NoRouteToHostException -> "EHOSTUNREACH"
            null -> ""
            else -> e.javaClass.simpleName
        }
    }
}
