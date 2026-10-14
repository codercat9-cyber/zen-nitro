package com.zen.blaster.core

import com.zen.blaster.model.Node
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  Генерация конфига sing-box.
//
//  ⚠️ Ядро в APK — sing-box v1.14. DNS-серверы только нового вида
//     ({"type": "https", "server": …}), без "sniff" во входах и без
//     "domain_strategy" у выходов: всё это ядро 1.14 отвергает при старте.
//     Сохранённые конфиги старого вида переводит SingBoxCompat.upgrade.
//
//  Три режима (единственный TUN держит sing-box, ByeDPI — только SOCKS):
//    • только DPI  : TUN → socks(ByeDPI)          IP не меняется
//    • только VPN  : TUN → VLESS/Hy2/…            IP меняется
//    • параллельно : TUN → VLESS с detour→ByeDPI  IP меняется + маскировка
// ============================================================================
object ConfigBuilder {

    // Яндекс DoH по адресу: сертификат выписан на 77.88.8.8 напрямую
    // (проверено 1 октября 2026), имя резолвера разрешать не нужно.
    private const val RU_DNS = "https://77.88.8.8/dns-query"

    private val RU = listOf(".ru", ".рф", ".su", ".by", "yandex.net", "yandex.ru", "vk.com", "vk.ru",
        "mail.ru", "ok.ru", "gosuslugi.ru", "sberbank.ru", "tinkoff.ru", "avito.ru",
        "wildberries.ru", "ozon.ru", "2gis.com", "rutube.ru")

    // Имя пакета берём у сборки, а НЕ строкой.
    //
    // Строка «com.zen.blaster» пережила переименование в com.zen.nitro, и
    // exclude_package стал указывать на несуществующий пакет. Итог: свой же
    // трафик Xray заворачивался обратно в туннель, который от Xray и зависит.
    // Ядро дозванивалось вечно, ноды через Xray выглядели мёртвыми.
    private val SELF_PKG = com.zen.blaster.BuildConfig.APPLICATION_ID

    // ---------------------------------------------------------------- БРОНЯ
    // Защита от того, что оператор ВИДИТ факт туннеля и душит абонента (PDP).
    // Идея: трафик должен выглядеть как обычный HTTPS-сёрфинг, а не как VPN.
    //
    // Браузерный отпечаток TLS — ОДИН НА УСТРОЙСТВО, а не новый каждую сессию.
    //
    // До 3.4 отпечаток менялся при каждом подключении: chrome, firefox, edge,
    // safari вразнобой — чтобы «нельзя было вести по постоянному профилю».
    // Для ТСПУ это работает наоборот. Живой человек ходит с телефона одним
    // браузером; «один абонент → один сервер → четыре разных браузера за
    // вечер» — это и есть аномалия, по которой отличают программу от
    // человека. А safari бывает только на iPhone: на Android он выдаёт сам
    // себя. Поэтому отпечаток выбирается один раз (чаще всего chrome — самый
    // массовый на Android) и дальше не меняется.
    private val WEIGHTED = listOf("chrome", "chrome", "chrome", "chrome", "chrome",
        "chrome", "chrome", "firefox", "firefox", "edge")
    fun freshFingerprint(): String = WEIGHTED.random()

    // Отпечаток этого устройства. Ставит приложение/служба из Prefs при старте.
    @Volatile var deviceFp: String = ""
    fun pickFingerprint(): String = deviceFp.ifEmpty { "chrome" }

    // Последний использованный отпечаток — показываем в UI/логе.
    @Volatile var lastFingerprint: String = ""; private set

    fun build(
        n: Node?,
        geoRouting: Boolean,
        ipv6: Boolean,
        vpnEnabled: Boolean,
        dpiEnabled: Boolean,
        geoDir: String? = null,     // каталог с распакованными .srs (из assets)
        armor: Boolean = true,      // «Броня» от детекта туннеля оператором
        viaXray: Boolean = false,   // нода идёт через Xray (XHTTP и пр.)
        proxyMode: Boolean = false, // вместо TUN — локальный SOCKS/HTTP
        frontDomain: String = "",   // режим белых списков: чем прикрываться
        hyDown: Int = 0,            // Hysteria2: заявленная скорость приёма, Мбит/с
        hyUp: Int = 0,              // Hysteria2: заявленная скорость отдачи
        slowLink: Boolean = false,  // узкий мобильный канал: E / H / H+
        bypassApps: Set<String> = emptySet(), // приложения мимо туннеля
        lanProxy: Boolean = false,  // пускать в прокси из локальной сети (раздача на ПК)
        lanUser: String = "",       // имя для входа в этот прокси
        lanPass: String = "",       // пароль; пусто = вход без пароля
        dohUrl: String = "https://1.1.1.1/dns-query", // резолвер ВНУТРИ туннеля
        // Резолвер для всего, что идёт МИМО туннеля. Отдельный, потому что
        // требования противоположные: внутри туннеля запрос зашифрован до
        // сервера и годится любой, а снаружи его видит ТСПУ, и тот же
        // Cloudflare там просто рвут.
        directDohUrl: String = "https://9.9.9.9/dns-query",
        // Пускать ли трафик ноды ЧЕРЕЗ ByeDPI. Раньше это включалось само
        // вместе с щитом и делало VPN зависимым от него. Теперь — осознанный
        // выбор человека, по умолчанию выключено.
        chainViaDpi: Boolean = false
    ): JSONObject {
        // Под бронёй IPv6 принудительно выключен: утечка через него выдаёт туннель.
        val ds = if (ipv6 && !armor) "ipv6_only" else "ipv4_only"
        val fp = if (armor) pickFingerprint() else ""
        if (armor) lastFingerprint = fp
        val useVpn = vpnEnabled && n != null

        val outbounds = JSONArray()
        if (useVpn && viaXray) {
            // Ноду поднимает Xray, sing-box просто отдаёт ему трафик через SOCKS.
            outbounds.put(JSONObject()
                .put("type", "socks").put("tag", "proxy")
                .put("server", "127.0.0.1").put("server_port", 10809))
        } else if (useVpn) {
            outbounds.put(buildOutbound(n!!, ds, chainViaDpi = chainViaDpi, armor = armor, fp = fp,
                frontDomain = frontDomain, hyDown = hyDown, hyUp = hyUp,
                slowLink = slowLink))
        }
        if (dpiEnabled) {
            // SOCKS до локального ByeDPI.
            outbounds.put(JSONObject()
                .put("type", "socks").put("tag", "dpi-socks")
                .put("server", "127.0.0.1").put("server_port", ByeDpi.PORT))
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        // Куда идёт весь трафик по умолчанию.
        val finalTag = when {
            useVpn -> "proxy"
            dpiEnabled -> "dpi-socks"
            else -> "direct"
        }

        // ---- маршрутизация ----
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        // Фрагментацию TLS обеспечивает ByeDPI (DPI-щит), поэтому
        // здесь её не дублируем.
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        // РАНЬШЕ РУ-домены уходили мимо туннеля ВСЕГДА, даже с выключенной
        // гео-маршрутизацией — из-за этого сайты видели настоящий IP и страну.
        // Теперь это делается только по явному согласию (переключатель в
        // настройках), иначе весь трафик идёт в туннель без исключений.
        if (geoRouting)
            rules.put(JSONObject().put("domain_suffix", JSONArray(RU)).put("outbound", "direct"))

        // Гео-правила — ТОЛЬКО из локальных файлов (лежат в assets, распакованы в filesDir).
        // Удалённая загрузка на старте вешала ядро: DNS query loopback / context canceled,
        // потому что чтобы скачать через прокси, нужен DNS, а DNS идёт через прокси.
        val ruleSet = JSONArray()
        if (geoRouting && geoDir != null) {
            val have = mutableListOf<String>()
            for (tag in listOf("geosite-ru", "geoip-ru", "geosite-ads")) {
                val f = java.io.File(geoDir, "$tag.srs")
                if (!f.exists() || f.length() < 100) continue
                have.add(tag)
                ruleSet.put(JSONObject().put("type", "local").put("tag", tag)
                    .put("format", "binary").put("path", f.absolutePath))
            }
            val ru = have.filter { it == "geosite-ru" || it == "geoip-ru" }
            if (ru.isNotEmpty())
                rules.put(JSONObject().put("rule_set", JSONArray(ru)).put("outbound", "direct"))
            if (have.contains("geosite-ads"))
                rules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ads")).put("action", "reject"))
        }

        val route = JSONObject()
            .put("rules", rules)
            .put("final", finalTag)
            .put("auto_detect_interface", true)
            // Чем ядро разрешает имена для прямых выходов. В 1.14 без этого
            // поля конфиг с несколькими DNS-серверами не стартует.
            .put("default_domain_resolver", JSONObject()
                .put("server", SingBoxCompat.BOOT).put("strategy", ds))
        if (ruleSet.length() > 0) route.put("rule_set", ruleSet)

        // ---- DNS ----
        // DNS НИКОГДА не гоним через ByeDPI: он десинхронизирует TLS и ломает
        // само DoH-соединение → getaddrinfo падает, ничего не грузится.
        // В режиме «только DPI» резолвим напрямую.
        //
        // Адрес резолвера приходит снаружи: он выбирается человеком. Зашивать
        // один было ошибкой — в день, когда оператор прикрыл Cloudflare,
        // приложение осталось вообще без DNS.
        val dnsDetour = if (useVpn) "proxy" else "direct"
        // Без VPN (режим «только DPI-щит») основной резолвер тоже идёт мимо
        // туннеля — туннеля просто нет. Здесь стоял тот же адрес, что и для
        // туннеля, и при «Авто» это был 1.1.1.1 напрямую: ровно то, что ТСПУ
        // режет первым. В режиме щита без VPN имена не разрешались вовсе.
        val mainDns = if (useVpn) dohUrl else directDohUrl
        val dnsRules = JSONArray()
        // Домен самого VPN-сервера резолвим МИМО прокси — иначе замкнутый круг:
        // чтобы поднять прокси, нужен DNS, а DNS ходит через этот же прокси.
        // (Обычно сюда даже не доходит: адрес сервера подставляется уже
        // разрешённым — см. ZenVpnService.preresolve.)
        if (n != null && n.addr.isNotEmpty() && !isIpLiteral(n.addr))
            dnsRules.put(JSONObject().put("domain", JSONArray().put(n.addr)).put("server", "boot-dns"))
        // РОССИЙСКИЕ ДОМЕНЫ ПРИ ОБХОДЕ РУ — ЧЕРЕЗ ЯНДЕКС.
        //
        // Трафик к ним идёт мимо туннеля, с настоящим адресом, — человек сам
        // так захотел, чтобы банк и госуслуги видели обычное подключение. Для
        // таких адресов нужен резолвер, который отдаёт РОССИЙСКИЕ узлы CDN:
        // заграничный (Quad9, Cloudflare) честно отвечает адресом ближайшего
        // к себе узла — в Европе, и VK с Яндексом грузятся через полмира.
        // Яндекс внутри России, его DoH не режут, а сертификат у него выписан
        // прямо на адрес 77.88.8.8 — петли с разрешением имени нет.
        //
        // Видит он при этом ровно то, что и так видно: эти сайты открываются
        // напрямую, без туннеля.
        if (geoRouting)
            dnsRules.put(JSONObject().put("domain_suffix", JSONArray(RU)).put("server", "ru-dns"))

        val servers = JSONArray()
            .put(SingBoxCompat.dnsServer("remote-dns", mainDns, dnsDetour))
            // Раньше здесь стоял обычный UDP-резолвер (77.88.8.8) мимо
            // туннеля: оператор читал ВСЕ такие запросы открытым текстом,
            // включая имя VPN-сервера, а резолвер видел настоящий IP и
            // страну. Теперь даже прямой резолв идёт по DoH.
            // Мимо туннеля — только живучий резолвер. Здесь тоже стоял
            // адрес для туннеля, и при «Авто» имя самого VPN-сервера
            // разрешалось прямым DoH к Cloudflare.
            .put(SingBoxCompat.dnsServer(SingBoxCompat.BOOT, directDohUrl, null))
            // Если человек выбрал системный резолвер — значит у него в этот
            // день режут DoH, и Яндекс по DoH тоже может не пройти. Тогда
            // и российские имена спрашиваем у оператора: свои сайты он не
            // блокирует, а ответит быстро и с российскими узлами CDN.
            .put(SingBoxCompat.dnsServer("ru-dns",
                if (directDohUrl == "local") "local" else RU_DNS, null))
        if (SingBoxCompat.needsSys(servers))
            servers.put(JSONObject().put("tag", SingBoxCompat.SYS).put("type", "local"))
        val dns = JSONObject()
            .put("servers", servers)
            .put("rules", dnsRules)
            .put("final", "remote-dns")
            .put("strategy", ds)

        // ---- TUN ----
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "system")
            // MTU 9000 при физическом канале 1500 (LTE) = крупные пакеты дропаются:
            // соединение устанавливается, а данные встают. 1420 безопасно для мобильных.
            //
            // НО ДЛЯ QUIC-НОД 1420 ВРЕДЕН. У Hysteria2 и TUIC внутренний UDP
            // едет внутри QUIC-датаграммы, полезная часть которой около 1150
            // байт. Всё, что длиннее, ядро режет своим фрагментатором — а
            // датаграммы QUIC не переспрашиваются: потеря одного куска убивает
            // весь пакет целиком. При обычных для сотовой связи потерях это
            // выносит внутренний HTTP/3, и получается знакомая по журналу
            // картина «канал есть, а YouTube не отвечает»: TCP-сайты живы,
            // видео мертво. Даём такому туннелю меньше — но не меньше 1280.
            //
            // 1280 — это НЕ круглое число из головы, а жёсткий минимум IPv6.
            // Ниже него интерфейс с адресом fdfe:… настроить нельзя в
            // принципе: проверено живым тестом 6 сентября 2026 — при 1200
            // ядро падало сразу на «configure tun interface: Cannot set
            // address», туннель не поднимался вовсе.
            .put("mtu", if (n != null && (n.type == "Hysteria2" || n.type == "TUIC")) 1280 else 1420)
        // Себя исключаем из туннеля ТОЛЬКО когда рядом крутится свой прокси:
        // сокеты ByeDPI и процесса Xray никем не защищены, и без исключения
        // получится петля (ByeDPI → TUN → ByeDPI).
        //
        // Когда таких прокси нет, исключение вредно: собственный трафик
        // приложения (подписки, DoH, пинги нод) уходил бы мимо туннеля с
        // настоящим IP. Ядро sing-box своим сокетам делает protect() само,
        // так что петли не будет.
        //
        // РАЗДЕЛЬНОЕ ТУННЕЛИРОВАНИЕ живёт здесь же. Приложения из bypassApps
        // в туннель не заходят вовсе: банк или госуслуги видят настоящий адрес
        // и настоящую активную сеть, будто клиент выключен. Список и SELF_PKG
        // ОБЪЕДИНЯЕМ — раньше здесь стояла перезапись, и включённый DPI-щит
        // молча выкинул бы весь пользовательский выбор.
        val exclude = LinkedHashSet<String>()
        if (dpiEnabled || viaXray) exclude.add(SELF_PKG)
        exclude.addAll(bypassApps)
        if (exclude.isNotEmpty()) {
            val arr = JSONArray()
            exclude.forEach { arr.put(it) }
            tun.put("exclude_package", arr)
        }

        // ---- входы ----
        //
        // РАЗДАЧА НА КОМПЬЮТЕР. Android не пускает трафик раздачи (USB-модем,
        // точка доступа) в туннель: пакеты чужих устройств пересылает ядро
        // системы, а VPN-правила применяются по владельцу сокета, которого у
        // такого трафика нет. Без root это не обойти — и это ровно та причина,
        // по которой «раздача через VPN» криво работает у всех.
        //
        // Работающий путь — не маршрут, а ПРОКСИ: поднимаем тот же смешанный
        // вход (SOCKS5 + HTTP) на 0.0.0.0 РЯДОМ с туннелем. Компьютер
        // прописывает адрес телефона, его трафик попадает прямо в ядро и
        // уходит в туннель. Телефон при этом продолжает работать через TUN,
        // а компьютер сам решает, идти через туннель или напрямую, — «с VPN
        // или без» без единой перенастройки телефона.
        val inbounds = JSONArray()
        val mixed = JSONObject()
            .put("type", "mixed").put("tag", "mixed-in")
            .put("listen", if (proxyMode && !lanProxy) "127.0.0.1" else "0.0.0.0")
            .put("listen_port", Prefs.PROXY_PORT)
        // Открытый прокси на 0.0.0.0 — это открытый ретранслятор: в общей
        // сети им воспользуется кто угодно, и наружу это будет выглядеть как
        // наш трафик. Поэтому пароль, а не «ну ладно».
        if (lanPass.isNotEmpty())
            mixed.put("users", JSONArray().put(
                JSONObject().put("username", lanUser.ifEmpty { "zen" }).put("password", lanPass)))

        if (proxyMode) inbounds.put(mixed)
        else {
            inbounds.put(tun)
            if (lanProxy) inbounds.put(mixed)
        }

        // СЛУЖЕБНЫЙ ВХОД ДЛЯ САМОГО ПРИЛОЖЕНИЯ.
        //
        // Приложение исключено из туннеля (иначе ядро заворачивало бы свой же
        // трафик в туннель, который от него зависит). Из-за этого любой его
        // запрос уходит мимо VPN и показывает домашний адрес — узнать, где мы
        // на самом деле вышли, было нечем. Через этот вход приложение ходит
        // тем же путём, что и весь телефон, и получает честный ответ.
        //
        // Только 127.0.0.1: снаружи и даже из локальной сети он недоступен.
        inbounds.put(JSONObject()
            .put("type", "mixed").put("tag", "probe-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", LocalProbe.port))

        return JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put("dns", dns)
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", route)
            // Порт управляющего интерфейса ядра здесь только «по умолчанию»:
            // перед самым стартом он заменяется на свободный (см. ClashApi).
            // Конфиг живёт долго — сохраняется для переподключений, — а номер
            // порта верен лишь на момент подъёма.
            .put("experimental", JSONObject().put("clash_api",
                JSONObject().put("external_controller", "127.0.0.1:${ClashApi.port}")))
    }

    private fun isIpLiteral(s: String): Boolean =
        s.isNotEmpty() && (s[0].isDigit() || s.contains(':'))

    // ---------------------------------------------------- БЕЛЫЕ СПИСКИ
    //
    //  Когда провайдер рубит всё, кроме перечня разрешённых сайтов, наружу
    //  проходит только то, что ВЫГЛЯДИТ походом на разрешённый домен. Имя
    //  сайта DPI берёт из поля SNI в открытой части TLS-приветствия — его и
    //  подменяем, продолжая соединяться с настоящим IP сервера.
    //
    //  Работает не со всем и врать тут нельзя:
    //   • Reality — НЕ подменяем. Там SNI жёстко связан с настройкой сервера,
    //     подмена ломает рукопожатие. Reality и без того маскируется под чужой
    //     сайт, так что смысла нет.
    //   • Обычный TLS — сервер предъявит сертификат на своё имя, оно не сойдётся
    //     с подставленным. Поэтому вместе с подменой включаем insecure:
    //     шифрование остаётся, проверка имени отключается.
    //   • WS/XHTTP через CDN — самый рабочий случай: подменяются и SNI, и Host.
    private fun frontable(n: Node, frontDomain: String) =
        frontDomain.isNotEmpty() && n.frontable()

    // ------------------------------------------- АВАРИЙНЫЙ ПРОФИЛЬ
    //
    //  Для нод из белосписочного арсенала всё выкручено в надёжность, а не в
    //  скорость и не в изящество. Логика простая: под белым списком у нас один
    //  выстрел, и соединение обязано выглядеть максимально скучно.
    //
    //   • отпечаток ВСЕГДА chrome — под пристальным разбором редкий отпечаток
    //     это минус, а не плюс; «Броня» со случайным отпечатком тут вредна;
    //   • ALPN принудительно h2: в аварийном режиме QUIC/h3 режут первым делом,
    //     а обычный TLS на 443 живёт дольше всех;
    //   • только IPv4 — по IPv6 такие сети обычно вообще не пускают;
    //   • никакой цепочки через ByeDPI: он корёжит то самое приветствие,
    //     которое мы старательно делаем похожим на обычный сайт;
    //   • проверка имени в сертификате снята — иначе подменённое имя не пройдёт.
    private fun hardenTls(tls: JSONObject) {
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
        tls.put("alpn", JSONArray().put("h2").put("http/1.1"))
        tls.put("insecure", true)
    }

    private fun buildOutbound(n: Node, ds: String, chainViaDpi: Boolean,
                              armor: Boolean = false, fp: String = "",
                              frontDomain: String = "",
                              hyDown: Int = 0, hyUp: Int = 0,
                              slowLink: Boolean = false): JSONObject {
        val out = JSONObject().put("tag", "proxy").put("server", n.addr).put("server_port", n.port)
        // Цепочка через DPI — только для TCP-протоколов (Hy2/TUIC — QUIC/UDP, им TCP-SOCKS ломает связь).
        // Аварийные ноды через ByeDPI не пускаем НИКОГДА: он корёжит приветствие,
        // которое мы как раз делаем похожим на обычный поход на сайт.
        val isUdp = n.type == "Hysteria2" || n.type == "TUIC"
        if (chainViaDpi && !isUdp && !n.wl) out.put("detour", "dpi-socks")
        // В аварийном режиме только IPv4: по IPv6 такие сети обычно не пускают.
        // Имя сервера обычно приходит уже адресом (ZenVpnService.preresolve),
        // резолвер — на случай, когда заранее разрешить не вышло.
        out.put("domain_resolver", JSONObject().put("server", SingBoxCompat.BOOT)
            .put("strategy", if (n.wl) "ipv4_only" else ds))

        when (n.type) {
            // VLESS и VMess разбираются ОДНОЙ веткой намеренно: у них общий
            // TLS, общие транспорты и общая маскировка — расходятся они
            // только в двух строках ниже. Дублировать ради этого полсотни
            // строк значит завести второй экземпляр всех будущих правок.
            "VLESS", "VMess" -> {
                if (n.type == "VMess")
                    out.put("type", "vmess").put("uuid", n.uuid)
                        .put("alter_id", n.alterId)
                        // Способ шифрования полезной нагрузки. «auto» ядро
                        // выбирает само и это верно почти всегда.
                        .put("security", n.encryption.ifEmpty { "auto" })
                else
                    out.put("type", "vless").put("uuid", n.uuid)
                if (n.security != "none") {
                    // Под бронёй отпечаток случайный (меняется каждую сессию),
                    // иначе — из подписки или chrome по умолчанию.
                    val useFp = if (armor && fp.isNotEmpty()) fp else n.fp.ifEmpty { "chrome" }
                    val front = frontable(n, frontDomain)
                    val sni = if (front) frontDomain
                              else n.sni.ifEmpty { if (n.host.isNotEmpty()) n.host else n.addr }
                    val tls = JSONObject().put("enabled", true)
                        .put("server_name", sni)
                        .put("utls", JSONObject().put("enabled", true).put("fingerprint", useFp))
                    if (n.pbk.isNotEmpty())
                        tls.put("reality", JSONObject().put("enabled", true)
                            .put("public_key", n.pbk).put("short_id", n.sid))
                    if (n.alpn.isNotEmpty()) tls.put("alpn", JSONArray(n.alpn.split(",")))
                    // Имя в сертификате теперь заведомо не совпадёт с подставленным.
                    if (front || n.insecure) tls.put("insecure", true)
                    // Аварийная нода — поверх всего кладём консервативный профиль.
                    if (n.wl) hardenTls(tls)
                    out.put("tls", tls)
                }
                if (n.flow.isNotEmpty()) out.put("flow", n.flow)
                if (n.transport.isNotEmpty() && n.transport != "tcp") {
                    // XHTTP/mKCP сюда не доходят — их забирает Xray (см. XrayEngine).
                    // Имена транспортов в sing-box: http, ws, grpc, httpupgrade, quic.
                    val kind = when (n.transport.lowercase()) {
                        "h2", "http" -> "http"
                        else -> n.transport.lowercase()
                    }
                    val t = JSONObject().put("type", kind)
                    // В режиме белых списков заголовок Host тоже должен называть
                    // разрешённый домен — DPI смотрит и туда.
                    val hostHdr = if (frontable(n, frontDomain)) frontDomain else n.host
                    when (kind) {
                        "ws", "httpupgrade" -> {
                            t.put("path", n.path.ifEmpty { "/" })
                            if (hostHdr.isNotEmpty()) t.put("headers", JSONObject().put("Host", hostHdr))
                        }
                        "http" -> {
                            t.put("path", n.path.ifEmpty { "/" })
                            if (hostHdr.isNotEmpty()) t.put("host", JSONArray().put(hostHdr))
                        }
                        "grpc" -> t.put("service_name", n.serviceName.ifEmpty { n.path })
                    }
                    out.put("transport", t)
                }
                // Мультиплекс здесь включать НЕЛЬЗЯ: он работает, только если его
                // включили и на сервере. Подписки об этом ничего не сообщают, а
                // большинство VLESS+Reality серверов mux не держат — соединение
                // устанавливалось и тут же рвалось. Именно так «глючила» часть нод.
                // xudp маскирует UDP внутри соединения (иначе UDP-паттерн палится).
                if (armor) out.put("packet_encoding", "xudp")
            }
            "Hysteria2" -> {
                out.put("type", "hysteria2").put("password", n.auth)
                out.put("tls", JSONObject().put("enabled", true)
                    .put("server_name", n.sni.ifEmpty { n.addr })
                    .apply {
                        if (n.insecure) put("insecure", true)
                        // ALPN у Hysteria2 обычно h3; если панель прислала своё —
                        // ставим ровно его, сервер сверяет.
                        if (n.alpn.isNotEmpty()) put("alpn", JSONArray(n.alpn.split(",")))
                    })
                if (n.obfs.isNotEmpty())
                    out.put("obfs", JSONObject().put("type", n.obfs).put("password", n.obfsPw))
                // Порт-хоппинг: нода живёт не на одном порту, а на диапазоне и
                // прыгает по нему. Провайдеры включают его как раз там, где
                // операторы режут по номеру порта, — то есть ровно в наших
                // условиях. Диапазон «20000-50000» ядро ждёт как «20000:50000».
                if (n.hopPorts.isNotEmpty()) {
                    val ranges = n.hopPorts.split(",").mapNotNull { r ->
                        val t = r.trim()
                        when {
                            t.contains('-') -> {
                                val a = t.substringBefore('-').trim().toIntOrNull()
                                val b = t.substringAfter('-').trim().toIntOrNull()
                                if (a != null && b != null) "$a:$b" else null
                            }
                            t.toIntOrNull() != null -> "$t:$t"
                            else -> null
                        }
                    }
                    if (ranges.isNotEmpty()) {
                        // server_port и server_ports вместе ядро не принимает.
                        out.remove("server_port")
                        out.put("server_ports", JSONArray(ranges))
                        // Как часто прыгать. Разрыва пути прыжок не даёт —
                        // ядро подсовывает quic-go постоянный адрес, — но для
                        // NAT оператора каждый прыжок это НОВЫЙ поток, и запись
                        // в трансляторе заводится не мгновенно. Первые пакеты
                        // после прыжка теряются, а на узком канале одна потеря
                        // стоит секунды ожидания. На быстрой сети тридцати
                        // секунд не жалко, на медленной это десятая часть
                        // времени в нокауте — растягиваем вчетверо.
                        out.put("hop_interval", if (slowLink) "120s" else "30s")
                    }
                }
                // Заявленные скорости переключают Hysteria2 с BBR на Brutal:
                // ядро держит темп, а не сбрасывает его на каждой потере пакета.
                // На мобильной сети, где потери постоянны, это и есть разница
                // между «еле ползёт» и нормальной скоростью.
                if (hyDown > 0 && hyUp > 0) {
                    out.put("down_mbps", hyDown)
                    out.put("up_mbps", hyUp)
                }
            }
            "TUIC" -> {
                out.put("type", "tuic").put("uuid", n.uuid).put("password", n.password)
                    .put("congestion_control", n.cc)
                out.put("tls", JSONObject().put("enabled", true)
                    .put("server_name", n.sni.ifEmpty { n.addr })
                    .put("alpn", JSONArray(n.alpn.ifEmpty { "h3" }.split(",")))
                    .apply { if (n.insecure) put("insecure", true) })
            }
            "Trojan" -> {
                out.put("type", "trojan").put("password", n.password)
                out.put("tls", JSONObject().put("enabled", true)
                    .put("server_name", n.sni.ifEmpty { n.addr })
                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome")))
            }
            "Shadowsocks" -> out.put("type", "shadowsocks").put("method", n.method).put("password", n.password)
        }
        return out
    }
}
