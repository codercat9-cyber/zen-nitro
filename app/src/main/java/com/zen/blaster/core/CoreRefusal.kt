package com.zen.blaster.core

// ============================================================================
//  «Сервер ответил отказом» — разбор строки ядра.
//
//  Журнал 03.11, FastLane «Sweden [Hysteria2]»: ядро десятки раз писало
//    connection: open connection to … using outbound/hysteria2[proxy]:
//    authentication failed, status code: 404
//  а итог блока был «канал не отвечает (сервер молчит либо ответ режут)».
//  Это неправда: сервер ОТВЕТИЛ — UDP до него проходит, — но пароль не
//  принял. Ждать и переподключаться бесполезно: ключ устарел или доступ
//  закрыт, лечится только обновлением подписки.
//
//  Отдельно и без Android — чтобы проверять тестом на настоящих строках.
// ============================================================================
object CoreRefusal {

    fun parse(line: String): String? {
        // DNS-резолвер со своей авторизацией — не беда ноды (см. CertMismatch).
        if (line.contains("dns:") || line.contains("exchange failed")) return null
        if (line.contains("hysteria2") && line.contains("authentication failed")) {
            val code = Regex("""status code: (\d+)""").find(line)?.groupValues?.get(1)
            return "сервер Hysteria2 ответил, но не принял пароль" +
                (code?.let { " (код $it)" } ?: "") +
                " — ключ устарел или доступ закрыт; UDP до него проходит, обновите подписку"
        }
        if (line.contains("tuic") && line.contains("authentication failed"))
            return "сервер TUIC ответил, но не принял ключ — обновите подписку"
        return null
    }

    // СБРОС СОЕДИНЕНИЯ ДО НОДЫ (RST) — отдельно: одна такая строка ещё не
    // приговор (сброс бывает и случайным), решает служба по нескольким.
    //
    // Журнал 04.11, Tele2, «EKB-FI»: «dial tcp 5.188.35.157:472: connect:
    // connection refused» на каждое соединение, а с домашнего компьютера
    // тот же порт открыт. Итог был «канал не отвечает (сервер молчит либо
    // ответ режут)» — а канал не молчал, его сбрасывали сразу. Сеть
    // называем прямо: с другой сети нода может работать.
    fun refusedTo(line: String): String? {
        if (line.contains("dns:") || line.contains("exchange failed")) return null
        if (!line.contains("connection refused")) return null
        // sing-box: «outbound/vless[proxy]»; Xray: «proxy/vless/outbound»
        // (прямой выход Xray — freedom — не про ноду).
        val xrayNode = Regex("proxy/(vless|vmess|trojan|shadowsocks|hysteria\\w*)/outbound").containsMatchIn(line)
        if (!line.contains("[proxy]") && !line.contains("process DNS packet") && !xrayNode) return null
        val target = Regex("""dial tcp ([0-9a-fA-F.:\[\]]+): connect: connection refused""")
            .find(line)?.groupValues?.get(1) ?: return null
        // Свой же локальный порт (Xray SOCKS, ByeDPI) — это не отказ сервера, а
        // наш упавший помощник. Журнал 09.11 с realme: «до 127.0.0.1:38614
        // сбрасывают (RST) — с этой сети к порту ноды не пускают» — неправда.
        val host = target.substringBeforeLast(':').trim('[', ']')
        if (host.startsWith("127.") || host == "::1" || host == "localhost") return null
        return target
    }

    fun refusedText(target: String): String =
        "до $target соединение сбрасывают сразу (RST) — с этой сети к порту ноды не пускают; " +
            "с другой сети она может работать — смените ноду или сеть"
}
