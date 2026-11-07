package com.zen.blaster.core

// ============================================================================
//  «Как у Amnezia» — шум перед рукопожатием для UDP-нод через Xray.
//
//  Журналы Xiaomi на Tele2, 31.10–06.11: первый подъём на мобильной сети —
//  AWG 57 %, VLESS/Reality 33 %, Hysteria2/TUIC 33 %. AmneziaWG берёт не
//  шифрованием (оно у всех есть), а началом сессии: перед рукопожатием
//  уходят мусорные пакеты случайной длины (Jc/Jmin/Jmax) и пакеты-приманки
//  I1–I5, похожие на чужой протокол. Классификатор оператора решает по
//  первым пакетам потока — и видит не «QUIC на 443 в зарубежный дата-центр»,
//  а мешанину.
//
//  Xray 26 умеет ровно это на нашей стороне: маска finalmask «noise» шлёт
//  описанные пакеты перед первым пакетом к адресу и снова после простоя, а
//  её выражения — тот же синтаксис, что I1–I5 у AWG 1.5: <b 0x…>, <r N>,
//  <rc N>, <rd N>, <t>, <c>. Сервер Hysteria2 такой мусор просто
//  выбрасывает (проверено 06.11: клиент Hysteria2 из Xray 26.10.10 и
//  26.10.31 с шумом и Salamander — HTTP 200 через обычный сервер sing-box).
//
//  Приманки берём из AWG-профилей самого человека: их подобрал провайдер
//  Amnezia, и на этом операторе они уже проходят. Битая приманка роняет весь
//  конфиг Xray, поэтому каждую проверяем по той же грамматике, что и ядро.
// ============================================================================
object AwgBait {

    private val TAG = Regex("""<\s*([a-z]+)(?:\s+([^>]*?))?\s*>""")
    private val SIZE = Regex("""^\d{1,5}(-\d{1,5})?$""")

    // Выражение, которое Xray примет: только теги, без текста между ними.
    fun valid(exp: String): Boolean {
        if (exp.isBlank() || exp.length > 8192) return false
        var last = 0
        var count = 0
        for (m in TAG.findAll(exp)) {
            if (exp.substring(last, m.range.first).isNotBlank()) return false
            last = m.range.last + 1
            count++
            val key = m.groupValues[1]
            val arg = m.groupValues[2].trim()
            when (key) {
                "b" -> {
                    val hex = arg.replace(Regex("\\s"), "").removePrefix("0x").removePrefix("0X")
                    if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" })
                        return false
                }
                "r", "rc", "rd" -> {
                    if (!SIZE.matches(arg)) return false
                    val parts = arg.split('-').map { it.toInt() }
                    if (parts.last() > 65535 || parts.first() > parts.last()) return false
                }
                "t", "c", "n" -> if (arg.isNotEmpty()) return false
                else -> return false
            }
        }
        return count > 0 && exp.substring(last).isBlank()
    }

    // Приманки I1–I5 из AWG-профилей (без повторов, не больше трёх).
    fun fromProfiles(profiles: List<com.zen.blaster.model.AwgProfile>): List<String> =
        profiles.flatMap { p ->
            (1..5).mapNotNull { i -> p.junk["i$i"] ?: p.junk["I$i"] }
        }.map { it.trim() }.filter { valid(it) }.distinct().take(3)

    // Маска finalmask для Xray: приманки, затем три мусорных пакета
    // 40–120 байт с паузами 5–15 мс (как Jc/Jmin/Jmax у AWG); после 30–60 с
    // тишины — снова, как AWG при новом рукопожатии.
    fun noiseMask(baits: List<String>): org.json.JSONObject {
        val items = org.json.JSONArray()
        baits.forEach { items.put(org.json.JSONObject().put("type", "exp").put("packet", it).put("delay", "5-15")) }
        repeat(3) { items.put(org.json.JSONObject().put("rand", "40-120").put("delay", "5-15")) }
        return org.json.JSONObject().put("type", "noise")
            .put("settings", org.json.JSONObject().put("reset", "30-60").put("noise", items))
    }
}
