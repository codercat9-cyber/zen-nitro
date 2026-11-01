package com.zen.blaster.model

// Одна нода (сервер). Поля повторяют парсер из Linux/Windows-версий.
data class Node(
    val type: String,                 // VLESS | VMess | Hysteria2 | TUIC | Trojan | Shadowsocks
    val name: String,
    val addr: String,
    val port: Int,
    val uuid: String = "",
    // VLESS Encryption (mlkem768x25519plus…) — постквантовое шифрование самого
    // VLESS. Умеет только Xray; sing-box такую ноду не поднимет в принципе.
    val encryption: String = "",
    val auth: String = "",            // Hysteria2
    val password: String = "",        // Trojan/SS/TUIC
    val method: String = "",          // Shadowsocks
    val sni: String = "",
    val pbk: String = "",             // Reality public key
    val sid: String = "",             // Reality short id
    val flow: String = "",
    val transport: String = "tcp",    // tcp|ws|grpc|xhttp|http
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val alpn: String = "",
    val security: String = "tls",
    val fp: String = "",              // uTLS fingerprint
    val obfs: String = "",            // Hysteria2: salamander|gecko
    val obfsPw: String = "",
    val cc: String = "bbr",           // TUIC congestion control
    val mode: String = "",            // XHTTP: auto|packet-up|stream-up|stream-one
    val extra: String = "",           // XHTTP: сырой JSON (xmux, scMax*, xPaddingBytes)
    val fm: String = "",              // Xray Finalmask: сырой JSON {tcp:[…], udp:[…]} из параметра fm
    // Порт-хоппинг Hysteria2: «20000-50000» или «443,20000-50000». Нода
    // прыгает по диапазону, чтобы её не отсекли по одному номеру порта.
    val hopPorts: String = "",
    val seed: String = "",            // mKCP seed
    val headerType: String = "",      // mKCP маскировка: none|srtp|utp|wechat-video|dtls|wireguard
    // VMess: число «запасных» идентификаторов. У всего живого сегодня это 0
    // (режим AEAD), но панели изредка присылают старые ноды с ненулевым — и
    // с неверным числом сервер молча рвёт соединение.
    val alterId: Int = 0,
    val insecure: Boolean = false,
    // Из какой подписки пришла нода — нужно для группировки в списке.
    var sub: String = "",
    // Нода из аварийного (белосписочного) арсенала: к ней применяется
    // отдельный, максимально консервативный набор параметров.
    var wl: Boolean = false,
    var ping: Int = -1,
    // Точная ли цифра пинга. У QUIC-нод сам порт молчит, и задержку мы меряем
    // до соседнего открытого порта того же хоста — путь тот же, но цифра
    // приблизительная, и врать о её точности нельзя.
    var pingExact: Boolean = true,
    // Когда пинг был измерен УДАЧНО. По этому времени решаем, держать ли
    // цифру при осечке следующего замера: свежую держим, а нода, которая
    // молчит больше часа, честно показывает «нет ответа».
    var pingAt: Long = 0L,
    // СКОЛЬКО ЗАМЕРОВ ПОДРЯД НОДА ПРОМОЛЧАЛА.
    //
    // Часть QUIC-нод не отзывается ВООБЩЕ ничем: ни сам порт, ни соседние,
    // ни эхо-запрос — открыт ровно один UDP-порт, и тот отвечает только на
    // валидное рукопожатие. Раньше в списке у них стояло «QUIC»: слово вместо
    // цифры, которое ничего не говорит и никогда не менялось.
    //
    // Теперь на такую ноду просто вешается счётчик. Каждый замер — новая
    // попытка; отозвалась хоть чем-то — счётчик обнуляется и появляется
    // цифра. Молчит раз за разом — значит глухая, и место цифры занимает
    // честная красная заглушка.
    var silentTries: Int = 0,
    // Когда через ноду в последний раз реально поднялся туннель.
    //
    // Это единственное надёжное свидетельство, что сервер живой: молчание на
    // пробы у Hysteria2 — норма, а не приговор. Нода без пинга, но с отметкой
    // «работала» — рабочая, и прятать её нельзя.
    var lastOkAt: Long = 0L,
    // НОДА ПРОПАЛА ИЗ ПОДПИСКИ, НО МЫ ЕЁ ПОМНИМ.
    //
    // Провайдер убирает сервер из выдачи, когда тот лёг: авария в ЦОДе,
    // переезд, блокировка. Раньше такая нода просто исчезала из списка — и
    // это выглядело как «сервер испарился», а вернётся ли он и когда, узнать
    // было неоткуда. Теперь она остаётся видимой с датой пропажи: если
    // провайдер её вернёт, пометка снимется сама.
    var gone: Boolean = false,
    var goneAt: Long = 0L
) {
    // Короткий бейдж протокола/транспорта для списка.
    // Транспорт показываем ВСЕГДА, в том числе у Reality: раньше десяток
    // gRPC-нод выглядел одинаковыми «Reality», и понять, какая из них какая,
    // было невозможно.
    private fun transportName(): String = when (transport.lowercase()) {
        "ws" -> "WS"
        "grpc" -> "gRPC"
        "xhttp", "splithttp" -> "XHTTP"
        "http", "h2" -> "H2"
        "kcp", "mkcp" -> "mKCP"
        "httpupgrade" -> "HU"
        else -> ""
    }

    fun badge(): String = when (type) {
        "VLESS" -> {
            val tr = transportName()
            when {
                pbk.isNotEmpty() -> when {
                    flow.contains("vision") -> "Reality-Vision"
                    tr.isNotEmpty() -> "Reality-$tr"
                    else -> "Reality"
                }
                tr.isNotEmpty() -> "VLESS-$tr"
                else -> "VLESS-TLS"
            }
        }
        "VMess" -> {
            val tr = transportName()
            if (tr.isNotEmpty()) "VMess-$tr" else "VMess"
        }
        "Hysteria2" -> when (obfs) {
            "gecko" -> "Hy2-Gecko"
            "" -> "Hysteria2"
            else -> "Hy2-Salamander"
        }
        else -> type
    }

    // Транспорт тянет либо sing-box, либо Xray (тот идёт отдельным процессом,
    // см. XrayEngine). Неподдерживаемое честно помечаем, иначе нода молча не
    // подключается и выглядит как «сервер сдох».
    fun supported(): Boolean = when (type) {
        "VLESS", "VMess" -> transport.lowercase() in setOf(
            "tcp", "ws", "grpc", "http", "h2", "httpupgrade",   // sing-box
            "xhttp", "splithttp", "kcp", "mkcp"                 // Xray
        )
        else -> true
    }

    // Нода живёт поверх QUIC/UDP. Такие серверы сплошь и рядом НЕ слушают TCP
    // вообще (например XHTTP c alpn=h3), поэтому проверять их живость
    // TCP-коннектом бессмысленно — они выглядят мёртвыми, работая при этом
    // отлично.
    fun quic(): Boolean =
        type == "Hysteria2" || type == "TUIC" || alpn.split(",").any { it.trim() == "h3" }

    // Сохранение списка на диск: без кэша любая сетевая осечка обнуляла список
    // серверов, и приложение оставалось вообще без нод.
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("type", type).put("name", name).put("addr", addr).put("port", port)
        .put("uuid", uuid).put("encryption", encryption).put("auth", auth).put("password", password).put("method", method)
        .put("sni", sni).put("pbk", pbk).put("sid", sid).put("flow", flow)
        .put("transport", transport).put("path", path).put("host", host)
        .put("serviceName", serviceName).put("alpn", alpn).put("security", security)
        .put("fp", fp).put("obfs", obfs).put("obfsPw", obfsPw).put("cc", cc)
        .put("mode", mode).put("extra", extra).put("fm", fm).put("seed", seed).put("hopPorts", hopPorts)
        .put("headerType", headerType).put("alterId", alterId).put("insecure", insecure)
        .put("sub", sub).put("wl", wl).put("ping", ping)
        .put("pingExact", pingExact).put("pingAt", pingAt).put("gone", gone).put("goneAt", goneAt)
        .put("silentTries", silentTries).put("lastOkAt", lastOkAt)

    companion object {
        // Сколько замеров подряд нода должна промолчать, прежде чем её
        // признают глухой. Один-два промаха бывают от своей же сети — при
        // переключении Wi-Fi/сота или на секундной просадке связи.
        const val SILENT_LIMIT = 3

        fun fromJson(o: org.json.JSONObject) = Node(
            type = o.optString("type"), name = o.optString("name"),
            addr = o.optString("addr"), port = o.optInt("port"),
            uuid = o.optString("uuid"), encryption = o.optString("encryption"),
            auth = o.optString("auth"),
            password = o.optString("password"), method = o.optString("method"),
            sni = o.optString("sni"), pbk = o.optString("pbk"), sid = o.optString("sid"),
            flow = o.optString("flow"), transport = o.optString("transport", "tcp"),
            path = o.optString("path"), host = o.optString("host"),
            serviceName = o.optString("serviceName"), alpn = o.optString("alpn"),
            security = o.optString("security", "tls"), fp = o.optString("fp"),
            obfs = o.optString("obfs"), obfsPw = o.optString("obfsPw"),
            cc = o.optString("cc", "bbr"), mode = o.optString("mode"),
            extra = o.optString("extra"), fm = o.optString("fm"), seed = o.optString("seed"),
            hopPorts = o.optString("hopPorts"),
            headerType = o.optString("headerType"), alterId = o.optInt("alterId"), insecure = o.optBoolean("insecure"),
            sub = o.optString("sub"), wl = o.optBoolean("wl"), ping = o.optInt("ping", -1),
            pingExact = o.optBoolean("pingExact", true),
            pingAt = o.optLong("pingAt", 0L),
            silentTries = o.optInt("silentTries", 0), lastOkAt = o.optLong("lastOkAt", 0L),
            gone = o.optBoolean("gone"), goneAt = o.optLong("goneAt", 0L)
        )
    }

    // Домены, которые у операторов лежат в белых списках: раздатчики статики
    // VK и Яндекса, куда ходят все и постоянно.
    private val WHITELIST = listOf(
        "userapi.com", "vk.com", "vk.ru", "vkuser", "vkvideo", "mycdn.me",
        "yandex.ru", "yandex.net", "yastatic.net", "mail.ru", "gosuslugi.ru"
    )

    // Нода уже маскируется под разрешённый домен и НИЧЕГО править не надо.
    //
    // Это лучший вариант для белых списков: Reality честно предъявляет имя
    // vk.com и настоящий сертификат vk.com, потому что при чужом клиенте
    // сервер и правда проксирует туда. Отличить такое соединение от похода
    // на VK нельзя в принципе.
    fun whitelistNative(): Boolean {
        val name = (sni.ifEmpty { host }).lowercase()
        return name.isNotEmpty() && WHITELIST.any { name.contains(it) }
    }

    // Можно ли прикрыть ноду чужим доменом, если она сама этого не делает.
    //
    // Reality — НЕЛЬЗЯ: там имя жёстко связано с настройкой сервера, подмена
    // ломает рукопожатие. Без TLS прикрывать нечем — нет SNI. Транспорт при
    // этом неважен: SNI есть у любого TLS, хоть по голому TCP.
    fun frontable(): Boolean =
        type == "VLESS" && pbk.isEmpty() && security != "none"

    fun display(): String {
        val p = when {
            ping in 1..899 -> if (pingExact) "${ping}ms" else "~${ping}ms"
            ping < 0 && lastOkAt > 0L -> "работала"
            ping < 0 && quic() -> if (silentTries >= SILENT_LIMIT) "не отвечает" else "нет ответа"
            else -> "нет ответа"
        }
        return "[${badge()}]  $name  ·  $p"
    }
}

data class Subscription(val name: String, val url: String)
