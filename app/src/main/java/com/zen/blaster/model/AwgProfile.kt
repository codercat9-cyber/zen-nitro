package com.zen.blaster.model

import org.json.JSONObject

// ============================================================================
//  Профиль AmneziaWG (он же WireGuard, если обфускация пустая).
//
//  Маскировка AWG росла слоями, и у каждого поколения свои параметры:
//
//   • AWG 1.0 — Jc/Jmin/Jmax: мусорные пакеты перед хендшейком; S1/S2:
//     «раздутые» служебные пакеты; H1..H4: подменённые типы пакетов (у
//     обычного WG это 1,2,3,4, и DPI ловит его именно по ним).
//   • AWG 1.5 — I1..I5: пакеты-приманки, маскируют старт сессии под чужой
//     протокол (DNS, QUIC…). В ранних 1.5 ещё J1..J3 и Itime.
//   • AWG 2.0 — S3/S4 (паддинг cookie и транспортных пакетов) и H1..H4
//     диапазонами («100-200» вместо одного числа).
//   • AWG 3 — HeaderProtectionKey (шифрование заголовков, ОБЯЗАТЕЛЕН на обеих
//     сторонах), ContentPaddingAddition, RandomTrailers, DisableCookies,
//     свои тайминги WireGuard и PersistentKeepalive диапазоном.
//
//  До 3.4 мы понимали только первые три поколения: параметры AWG 3 молча
//  выкидывались при импорте, и сервер на AWG 3 не отвечал на рукопожатие
//  (14 октября 2026, конфиг оператора — официальный клиент Amnezia с ним
//  же подключался за 0.2 с).
//
//  Поэтому теперь профиль хранит ещё и исходный текст конфига (raw): когда
//  выйдет AWG 4, его параметры не придётся выпрашивать у человека заново —
//  разбор просто повторится по сохранённому тексту.
// ============================================================================
data class AwgProfile(
    val name: String,
    // [Interface]
    val privateKey: String,                 // base64
    val addresses: List<String>,            // 10.8.1.2/32
    val dns: List<String> = emptyList(),
    val mtu: Int = 1280,
    val listenPort: Int = 0,
    // Маскировка и всё прочее из [Interface], что понимает только AWG:
    // ключи как в .conf, но в нижнем регистре (jc, s3, headerprotectionkey…).
    val junk: Map<String, String> = emptyMap(),
    // [Peer]
    val peerPublicKey: String = "",         // base64
    val presharedKey: String = "",          // base64
    val endpoint: String = "",              // host:port
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val keepalive: Int = 25,
    // AWG 3 разрешает интервал диапазоном: «25-35». Пусто — как keepalive.
    val keepaliveRange: String = "",
    // раздельное туннелирование
    val includeApps: List<String> = emptyList(),
    val excludeApps: List<String> = emptyList(),
    // Исходный текст конфига — чтобы пере-разобрать его новой версией.
    val raw: String = ""
) {
    val host: String get() = endpoint.substringBeforeLast(':', endpoint).trim('[', ']')
    val port: Int get() = endpoint.substringAfterLast(':', "51820").toIntOrNull() ?: 51820

    // Поколение по самому новому параметру в конфиге.
    fun badge(): String = when {
        junk.keys.any { it in AWG3_KEYS } -> "AWG 3"
        junk.keys.any { it == "s3" || it == "s4" } ||
            junk.any { (k, v) -> k.length == 2 && k[0] == 'h' && v.contains('-') } -> "AWG 2.0"
        junk.keys.any { it.startsWith("i") || it in LEGACY_ONLY } -> "AWG 1.5"
        junk.isNotEmpty() -> "AWG 1.0"
        else -> "WireGuard"
    }

    // Годится ли конфиг запасному ядру старой ветки (AWG 1.x). Всё, что
    // моложе 1.5, оно не поймёт — пробовать на нём бессмысленно.
    fun legacyCapable(): Boolean = badge().let { it == "WireGuard" || it == "AWG 1.0" || it == "AWG 1.5" }

    fun display(): String = "[${badge()}]  $name  ·  $host"

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("privateKey", privateKey)
        .put("addresses", addresses.joinToString(","))
        .put("dns", dns.joinToString(","))
        .put("mtu", mtu).put("listenPort", listenPort)
        .put("junk", JSONObject(junk as Map<*, *>))
        .put("peerPublicKey", peerPublicKey).put("presharedKey", presharedKey)
        .put("endpoint", endpoint).put("allowedIps", allowedIps.joinToString(","))
        .put("keepalive", keepalive).put("keepaliveRange", keepaliveRange)
        .put("includeApps", includeApps.joinToString(","))
        .put("excludeApps", excludeApps.joinToString(","))
        .put("raw", raw)

    companion object {
        // Параметры AWG 3: ни одно ядро старше v3.1 их не примет.
        val AWG3_KEYS = setOf(
            "headerprotectionkey", "contentpaddingaddition",
            "rekeyaftertime", "rekeytimeout", "rejectaftertime",
            "keepalivetimeout", "maxhandshakeattempts",
            "randomtrailers", "disablecookies"
        )

        // Параметры ранних AWG 1.5, которые v3 выбросил. Это мусор на стороне
        // клиента, сервер их не сверяет — новому ядру их можно не передавать.
        val LEGACY_ONLY = setOf("j1", "j2", "j3", "itime")

        private fun split(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        fun fromJson(o: JSONObject): AwgProfile {
            val j = o.optJSONObject("junk") ?: JSONObject()
            val junk = j.keys().asSequence().associateWith { j.getString(it) }
            val p = AwgProfile(
                name = o.optString("name"), privateKey = o.optString("privateKey"),
                addresses = split(o.optString("addresses")), dns = split(o.optString("dns")),
                mtu = o.optInt("mtu", 1280), listenPort = o.optInt("listenPort", 0),
                junk = junk,
                peerPublicKey = o.optString("peerPublicKey"), presharedKey = o.optString("presharedKey"),
                endpoint = o.optString("endpoint"),
                allowedIps = split(o.optString("allowedIps")).ifEmpty { listOf("0.0.0.0/0", "::/0") },
                keepalive = o.optInt("keepalive", 25),
                keepaliveRange = o.optString("keepaliveRange"),
                includeApps = split(o.optString("includeApps")),
                excludeApps = split(o.optString("excludeApps")),
                raw = o.optString("raw")
            )
            // Есть исходник — разбираем его заново нынешним разборщиком: так
            // профили, сохранённые старой версией, получают новые параметры
            // сами. Имя и раздельное туннелирование — выбор человека, их
            // оставляем как были.
            if (p.raw.isEmpty()) return p
            val fresh = com.zen.blaster.core.AwgParser.parseAny(p.raw, p.name) ?: return p
            return fresh.copy(name = p.name, includeApps = p.includeApps, excludeApps = p.excludeApps)
        }
    }
}
