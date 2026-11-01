package com.zen.blaster.core

import android.content.Context
import com.zen.blaster.model.Subscription
import org.json.JSONArray
import org.json.JSONObject

// Простое хранилище (SharedPreferences) для подписок и настроек.
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("zen", Context.MODE_PRIVATE)
    private val ctx = context.applicationContext

    // Распаковывает гео-правила из assets в файлы (ядро читает их с диска).
    // Возвращает путь к каталогу или null, если ничего нет.
    fun geoDir(): String? = try {
        val dir = java.io.File(ctx.filesDir, "geo").apply { mkdirs() }
        for (name in listOf("geosite-ru.srs", "geoip-ru.srs", "geosite-ads.srs")) {
            val out = java.io.File(dir, name)
            if (out.exists() && out.length() > 100) continue
            ctx.assets.open("geo/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        dir.absolutePath
    } catch (e: Exception) { null }

    var selectedServer: Int
        get() = sp.getInt("selected", 0)
        set(v) = sp.edit().putInt("selected", v).apply()

    // ПРИМЕТА ВЫБРАННОЙ НОДЫ, а не её номер в списке.
    //
    // Номер — плохая память о выборе. Список пересобирается при каждом
    // обновлении подписок: одна промолчала и её ноды спаслись, другая отдала
    // больше, третья меньше, порядок сортировки поехал — и под тем же номером
    // оказывается совсем другой сервер. Человек жмёт «подключиться» и попадает
    // не туда, куда выбирал.
    //
    // Именно из-за этого плитка в шторке когда-то поднимала «вообще другой
    // сервер», и из-за этого же 14.08.2026 мы полчаса чинили обходную ноду,
    // а на деле подключались к Hysteria2 по UDP.
    //
    // Храним адрес, порт и имя: адрес один и тот же у разных нод одного
    // сервера, имя различает их между собой.
    var selectedKey: String
        get() = sp.getString("selKey", "") ?: ""
        set(v) = sp.edit().putString("selKey", v).apply()

    // Пускать РУ-сайты мимо туннеля. Удобно (банки, госуслуги), но такие сайты
    // видят настоящий IP и страну — поэтому по умолчанию ВЫКЛЮЧЕНО.
    var geoRouting: Boolean
        get() = sp.getBoolean("geo", false)
        set(v) = sp.edit().putBoolean("geo", v).apply()

    var ipv6: Boolean
        get() = sp.getBoolean("ipv6", false)
        set(v) = sp.edit().putBoolean("ipv6", v).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean("autoRe", true)
        set(v) = sp.edit().putBoolean("autoRe", v).apply()

    // Режимы работы (можно оба сразу — «Режим БОГА»).
    var vpnEnabled: Boolean
        get() = sp.getBoolean("vpnOn", true)
        set(v) = sp.edit().putBoolean("vpnOn", v).apply()

    var dpiEnabled: Boolean
        get() = sp.getBoolean("dpiOn", false)
        set(v) = sp.edit().putBoolean("dpiOn", v).apply()

    var dpiStrategy: Int
        get() = sp.getInt("dpiStrat", 0)
        set(v) = sp.edit().putInt("dpiStrat", v).apply()

    // false — системный VPN (TUN, весь трафик телефона);
    // true  — локальный прокси: ядро поднимает SOCKS/HTTP на 127.0.0.1 и
    //         не трогает маршруты. Полезно, когда нужно завернуть только
    //         одно приложение или когда системный VPN занят другим клиентом.
    var proxyMode: Boolean
        get() = sp.getBoolean("proxyMode", false)
        set(v) = sp.edit().putBoolean("proxyMode", v).apply()

    companion object {
        const val PROXY_PORT = 2080

        const val TITLE_MIN = 16
        const val TITLE_MAX = 40

        // Куда приложение ходит за списком версий.
        //
        // Канал живёт в сервере мессенджера, рядом с его собственными
        // обновлениями, но в своём каталоге: server/updates/zen/. Выкладывает
        // туда сборки tools/publish-zen.sh. Свой адрес, а не чужой магазин —
        // обновление не зависит ни от Google Play, ни от чьей-то модерации.
        //
        // Меняется в Настройках; стереть строку = выключить проверки совсем.
        const val DEFAULT_UPDATE_URL = "https://storage-mess.online/api/updates/zen"
    }

    // «Броня»: маскировка от детекта туннеля оператором (PDP).
    var armor: Boolean
        get() = sp.getBoolean("armor", true)
        set(v) = sp.edit().putBoolean("armor", v).apply()

    // Последний рабочий конфиг — нужен, чтобы система могла поднять туннель
    // сама (режим «Постоянная VPN» / перезапуск сервиса), без запуска UI.
    var lastConfig: String
        get() = sp.getString("lastCfg", "") ?: ""
        set(v) = sp.edit().putString("lastCfg", v).apply()

    var lastDpi: Boolean
        get() = sp.getBoolean("lastDpi", false)
        set(v) = sp.edit().putBoolean("lastDpi", v).apply()

    // Конфиг Xray последней сессии (для always-on, когда UI не запускается).
    // Имя ноды последнего конфига. Нужно уведомлению: при запуске из плитки
    // приложение не открывается, Stats.server никто не заполняет, и в шторке
    // вместо сервера висело безликое «ZEN NITRO» — понять, куда подключён,
    // было невозможно.
    var lastServerName: String
        get() = sp.getString("lastSrvName", "") ?: ""
        set(v) = sp.edit().putString("lastSrvName", v).apply()

    var lastXray: String
        get() = sp.getString("lastXray", "") ?: ""
        set(v) = sp.edit().putString("lastXray", v).apply()

    // Второй источник модулей ядер (кроме нашего сервера) — например,
    // релизы на GitHub: https://github.com/<кто>/zen-cores/releases/latest/download/
    // Подпись проверяется та же, так что чужой источник модуль не подсунет.
    var coresSource: String
        get() = sp.getString("coresSource", "") ?: ""
        set(v) = sp.edit().putString("coresSource", v.trim()).apply()

    // Профиль AmneziaWG последней сессии (для always-on без запуска UI).
    var lastAwg: String
        get() = sp.getString("lastAwg", "") ?: ""
        set(v) = sp.edit().putString("lastAwg", v).apply()

    // Ядро Amnezia живёт в отдельном процессе, статикой состояние не передать —
    // флаг пишет сама служба, UI и плитка в шторке его читают.
    //
    // ФАЙЛОМ, а не в SharedPreferences. Настройки каждый процесс держит своей
    // копией в памяти: служба (:awg) писала «поднят», а интерфейс читал свою
    // старую копию «не поднят» — и при каждом возврате в приложение гасил
    // кнопку, хотя туннель работал (поймано 14.10.2026). Файл читается с
    // диска каждый раз, и копий у него нет.
    private fun flag(name: String) = java.io.File(ctx.filesDir, name)
    private fun readFlag(name: String) = try { flag(name).readText() == "1" } catch (_: Throwable) { false }
    private fun writeFlag(name: String, v: Boolean) { try { flag(name).writeText(if (v) "1" else "0") } catch (_: Throwable) {} }

    var awgRunning: Boolean
        get() = readFlag("awg.running")
        set(v) = writeFlag("awg.running", v)

    // Туннель Amnezia идёт на ЗАПАСНОМ ядре (процесс :awg1) — его и гасить.
    var awgLegacyRunning: Boolean
        get() = readFlag("awg1.running")
        set(v) = writeFlag("awg1.running", v)

    // Какое ядро выбрано: 0 — sing-box/Xray (VLESS, Hy2, TUIC…), 1 — Amnezia.
    var engine: Int
        get() = sp.getInt("engine", 0)
        set(v) = sp.edit().putInt("engine", v).apply()

    var selectedAwg: Int
        get() = sp.getInt("selAwg", 0)
        set(v) = sp.edit().putInt("selAwg", v).apply()

    // ---------------------------------------------------- профили Amnezia

    fun awgProfiles(): List<com.zen.blaster.model.AwgProfile> {
        val raw = sp.getString("awg", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { com.zen.blaster.model.AwgProfile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun saveAwgProfiles(list: List<com.zen.blaster.model.AwgProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("awg", arr.toString()).apply()
    }

    fun addAwgProfile(p: com.zen.blaster.model.AwgProfile) =
        saveAwgProfiles(awgProfiles().filter { it.name != p.name } + p)

    fun removeAwgProfile(index: Int) =
        saveAwgProfiles(awgProfiles().filterIndexed { i, _ -> i != index })

    // ------------------------------------------------- кэш списка серверов
    //
    // Подписки тянутся по сети, а сеть в момент обновления может лежать —
    // особенно когда туннель как раз рвётся. Раньше пустой ответ затирал
    // список, и приложение оставалось с нулём серверов. Держим последний
    // удачный список на диске.

    fun cachedNodes(): List<com.zen.blaster.model.Node> {
        val raw = sp.getString("nodeCache", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { com.zen.blaster.model.Node.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    // Пустым кеш обычно не затираем — но удалённая подписка должна уйти и
    // из него, иначе её ноды вернутся при следующем запуске.
    fun clearNodeCache() { sp.edit().remove("nodeCache").apply() }

    fun cacheNodes(list: List<com.zen.blaster.model.Node>) {
        if (list.isEmpty()) return                 // пустым кэш не затираем
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("nodeCache", arr.toString()).apply()
    }

    // Ноды, убранные крестиком. Ключ — «подписка|адрес:порт»: по нему убранная
    // не возвращается со следующим «обновить», даже если панель её ещё отдаёт.
    // Сменит провайдер ей адрес — это уже другая нода, она придёт заново.
    fun dismissedNodes(): Set<String> = sp.getStringSet("dismissedNodes", emptySet()) ?: emptySet()

    fun dismissNodes(keys: Collection<String>) {
        sp.edit().putStringSet("dismissedNodes", dismissedNodes() + keys).apply()
    }

    fun clearDismissed() { sp.edit().remove("dismissedNodes").apply() }

    // Ссылки на отдельные серверы, добавленные вручную (vless://, hy2://…).
    fun manualLinks(): List<String> =
        sp.getStringSet("manualLinks", emptySet())?.toList()?.sorted() ?: emptyList()

    fun addManualLink(link: String) =
        sp.edit().putStringSet("manualLinks", manualLinks().toMutableSet().apply { add(link) }).apply()

    fun removeManualLink(link: String) =
        sp.edit().putStringSet("manualLinks", manualLinks().toMutableSet().apply { remove(link) }).apply()

    // ----------------------------------------- аварийный (белосписочный) арсенал
    //
    // Держим ОТДЕЛЬНО от обычных подписок. Когда оператор рубит всё, кроме
    // разрешённого перечня, это последнее, что работает, — искать такую ноду
    // в общей куче из сотни серверов некогда.
    fun wlLinks(): List<String> =
        sp.getStringSet("wlLinks", emptySet())?.toList()?.sorted() ?: emptyList()

    fun addWlLink(link: String) =
        sp.edit().putStringSet("wlLinks", wlLinks().toMutableSet().apply { add(link) }).apply()

    fun removeWlLink(link: String) =
        sp.edit().putStringSet("wlLinks", wlLinks().toMutableSet().apply { remove(link) }).apply()

    // ------------------------------------------------- режим белых списков
    //
    // Когда оператор рубит всё, кроме разрешённого перечня сайтов, наружу
    // проходит только трафик, который ВЫГЛЯДИТ походом на разрешённый домен.
    // Подменяем SNI и Host на такой домен, продолжая идти на настоящий IP.
    var whitelistMode: Boolean
        get() = sp.getBoolean("wlMode", false)
        set(v) = sp.edit().putBoolean("wlMode", v).apply()

    // Прикрываемся именно CDN: у операторов в белом списке лежат раздатчики
    // статики VK и Яндекса — картинки, скрипты, видео. Туда ходят все и
    // постоянно, поэтому такой трафик не вызывает вопросов.
    var whitelistDomain: String
        get() = sp.getString("wlDomain", "sun9-1.userapi.com") ?: "sun9-1.userapi.com"
        set(v) = sp.edit().putString("wlDomain", v).apply()

    // ------------------------------------------------- разгон Hysteria2
    //
    // Если скорости НЕ заданы, Hysteria2 работает на BBR: при потерях пакетов
    // он честно сбавляет темп. На мобильной сети потери есть всегда, поэтому
    // скорость и «пердит». Заданные значения включают режим Brutal: ядро
    // держит заявленный темп, не отступая на каждую потерю.
    //
    // Врать нельзя: если поставить больше реального канала, станет ХУЖЕ —
    // клиент будет забивать канал и топить сам себя.
    var hyDownMbps: Int
        get() = sp.getInt("hyDown", 100)
        set(v) = sp.edit().putInt("hyDown", v).apply()

    var hyUpMbps: Int
        get() = sp.getInt("hyUp", 20)
        set(v) = sp.edit().putInt("hyUp", v).apply()

    var hyBoost: Boolean
        get() = sp.getBoolean("hyBoost", true)
        set(v) = sp.edit().putBoolean("hyBoost", v).apply()

    // ------------------------------------------------- раздельное туннелирование
    //
    // Приложения, которые ходят МИМО туннеля — своим настоящим адресом.
    // Нужно для банков, госуслуг, маркетплейсов и всего, что нервничает при
    // виде заграничного IP. Работает через addDisallowedApplication: трафик
    // такого приложения в туннель не заходит вовсе, а не «заходит и выходит».
    //
    // Включается тем же переключателем, что и обход РУ-сайтов (geoRouting):
    // одна кнопка на обе половины, как и просили.
    fun splitApps(): Set<String> = sp.getStringSet("splitApps", emptySet()) ?: emptySet()

    fun setSplitApps(list: Set<String>) =
        sp.edit().putStringSet("splitApps", list).apply()

    // ------------------------------------------------------------------ DNS
    //
    // Номер из DnsChoice.LIST, либо DnsChoice.CUSTOM — тогда берётся dnsCustom.
    // Раньше резолвер был один и зашит намертво; в день, когда оператор его
    // прикрыл, приложение осталось без DNS вообще.
    // По умолчанию больше НЕ Cloudflare (номер 0).
    //
    // С августа 2026 ТСПУ рвёт рукопожатие к 1.1.1.1 и 8.8.8.8 точечно, по их
    // адресам и SNI. Внешне это выглядит хуже, чем полная блокировка: канал
    // живой, сайты «не открываются», и человеку не за что зацепиться. Кто уже
    // выбрал резолвер руками — остаётся на своём, умолчание трогает только
    // тех, кто ни разу не заходил в настройки.
    var dnsProvider: Int
        get() = sp.getInt("dnsProv", DnsChoice.PREFERRED)
        set(v) = sp.edit().putInt("dnsProv", v).apply()

    var dnsCustom: String
        get() = sp.getString("dnsCustom", "") ?: ""
        set(v) = sp.edit().putString("dnsCustom", v.trim()).apply()

    // НОДЫ, КОТОРЫМ ПОДМЕНА ИМЕНИ ВРЕДИТ.
    //
    //  Клиент умеет откатываться: не встала с подменённым именем — пробует с
    //  собственным. Но открытие жило только до перезапуска, и при каждом
    //  следующем подключении всё начиналось сначала: попытка с подменой,
    //  «peer cert is invalid», отказ, откат. Человек платил за это лишними
    //  секундами и одним неудачным подъёмом каждый раз.
    //
    //  Теперь такие ноды помним. Ключ — тот же, что у выбора сервера
    //  (адрес:порт|имя), так что переехавшая на новый IP нода получит право
    //  на новую попытку — вдруг у неё там всё иначе.
    fun frontHostile(): Set<String> =
        (sp.getString("noFront", "") ?: "").split('\n').filter { it.isNotEmpty() }.toSet()

    fun rememberFrontHostile(key: String) {
        if (key.isEmpty()) return
        val cur = frontHostile()
        if (key in cur) return
        // Держим последние полсотни: список не должен расти бесконечно.
        val next = (cur + key).toList().takeLast(50)
        sp.edit().putString("noFront", next.joinToString("\n")).apply()
    }

    // НОДЫ, КОТОРЫЕ ПОДНИМАЮТСЯ ТОЛЬКО ЧЕРЕЗ XRAY.
    //
    // Серверы Sirius (и не только) настроены под Happ, то есть под Xray: часть
    // из них sing-box не пускает вовсе — «reality verification failed», хотя
    // Xray с теми же ключами проходит за 0,1–0,8 с (проверено 18.10.2026 на
    // de8/srv88/glaveda/paruscamp, gRPC nl7/us7). Раз нода поднялась через
    // Xray после отказа sing-box — запоминаем и в следующий раз идём сразу им.
    fun xrayNodes(): Set<String> =
        (sp.getString("xrayNodes", "") ?: "").split("\n").filter { it.isNotEmpty() }.toSet()

    fun rememberXrayNode(key: String) {
        if (key.isEmpty()) return
        val cur = xrayNodes()
        if (key in cur) return
        sp.edit().putString("xrayNodes", (cur + key).toList().takeLast(200).joinToString("\n")).apply()
    }

    fun forgetXrayNode(key: String) {
        val cur = xrayNodes()
        if (key !in cur) return
        sp.edit().putString("xrayNodes", (cur - key).joinToString("\n")).apply()
    }

    fun forgetFrontHostile(key: String) {
        val next = frontHostile() - key
        sp.edit().putString("noFront", next.joinToString("\n")).apply()
    }

    // Когда в последний раз мерили пинг всего списка. Полный замер — не
    // чаще раза в полчаса; переживает перезапуск, иначе каждое открытие
    // приложения снова запускало бы веер по двум сотням серверов.
    // Тихая проверка модулей ядер: когда была и что нашла («Xray 26.10.29»).
    // Найденное держим и между запусками — по нему горит надпись на главной.
    var lastCoreCheck: Long
        get() = sp.getLong("lastCoreCheck", 0L)
        set(v) = sp.edit().putLong("lastCoreCheck", v).apply()

    var coreOffer: String
        get() = sp.getString("coreOffer", "") ?: ""
        set(v) = sp.edit().putString("coreOffer", v).apply()

    var lastPingAll: Long
        get() = sp.getLong("lastPingAll", 0L)
        set(v) = sp.edit().putLong("lastPingAll", v).apply()

    // НОДЫ, КОТОРЫМ СВЯЗКА С DPI-ЩИТОМ МЕШАЕТ.
    //
    //  Щит режет TLS-приветствие на куски, а часть серверов — особенно всё,
    //  что за CDN, — на такое приветствие не отвечает. Клиент умеет
    //  откатываться: двадцать секунд ждёт через щит, потом пробует напрямую.
    //  Но открытие жило до перезапуска, и в журнале за 30.09–01.10 это
    //  четырнадцать провалов — четырнадцать раз по двадцать секунд впустую,
    //  на одних и тех же нодах. Теперь помним.
    fun chainHostile(): Set<String> =
        (sp.getString("noChain", "") ?: "").split('\n').filter { it.isNotEmpty() }.toSet()

    fun rememberChainHostile(key: String) {
        if (key.isEmpty()) return
        val cur = chainHostile()
        if (key in cur) return
        sp.edit().putString("noChain", (cur + key).toList().takeLast(50).joinToString("\n")).apply()
    }

    // НОДЫ, У КОТОРЫХ СЕРВЕР ПРЕДЪЯВЛЯЕТ ЧУЖОЕ ИМЯ.
    //
    //  Бывает поломка на стороне провайдера: в подписке указано одно имя, а
    //  сервер отдаёт сертификат на другое. Так 6 сентября 2026 встала нода
    //  «ICEbreaker» — подписка вела на sentry.allepal.ee, сервер предъявлял
    //  «*.iperf.uk», и ядро честно отказывалось, раз за разом, по кругу.
    //
    //  Мы такую ноду не бросаем: внешний TLS у XHTTP — это обёртка, а данные
    //  внутри неё всё равно зашифрованы ключами VLESS, и без них сервер
    //  ничего не отдаст. Поэтому для таких нод — и ТОЛЬКО для них — проверку
    //  внешнего имени снимаем, а человеку говорим прямо, что происходит.
    //
    //  Ключ хранения сменён в 3.4 («certOff» → «certOff2»), и это нарочно:
    //  до 3.4 ловец принимал за беду ноды и ошибки СЕРТИФИКАТА DNS-резолвера
    //  (ControlD по неверному адресу, 14.10.2026), и в список попадали
    //  здоровые ноды — с них молча снималась проверка имени. Старый список
    //  выбрасываем целиком; настоящие поломки провайдеров ловец найдёт заново
    //  с первой же попытки.
    fun certMismatch(): Set<String> =
        (sp.getString("certOff2", "") ?: "").split('\n').filter { it.isNotEmpty() }.toSet()

    fun rememberCertMismatch(key: String) {
        if (key.isEmpty()) return
        val cur = certMismatch()
        if (key in cur) return
        sp.edit().putString("certOff2", (cur + key).toList().takeLast(50).joinToString("\n")).apply()
    }

    // Отпечаток TLS этого устройства — выбирается однажды и живёт дальше
    // (см. ConfigBuilder.deviceFp: почему не новый каждую сессию).
    fun deviceFingerprint(): String {
        val cur = sp.getString("tlsFp", "") ?: ""
        if (cur.isNotEmpty()) return cur
        val fp = ConfigBuilder.freshFingerprint()
        sp.edit().putString("tlsFp", fp).apply()
        return fp
    }

    // ПАМЯТЬ УДАЧ ПО ОПЕРАТОРУ: какие ноды поднимались на этой сети и когда.
    //
    //  14.10.2026 на Tele2 одна и та же нода штурмовалась 48 раз (26 провалов),
    //  а соседняя в это же время поднималась 10 раз из 12 — каскад просто не
    //  знал, что на этом операторе работает. Ключ ноды — адрес:порт (имена
    //  провайдеры меняют), время — когда последний раз поднялась.
    fun carrierWins(carrier: String): Map<String, Long> = try {
        val o = JSONObject(sp.getString("carrierWins", "{}") ?: "{}").optJSONObject(carrier)
        o?.keys()?.asSequence()?.associateWith { o.optLong(it) } ?: emptyMap()
    } catch (_: Throwable) { emptyMap() }

    fun rememberCarrierWin(carrier: String, key: String) {
        if (carrier.isEmpty() || key.isEmpty()) return
        try {
            val root = JSONObject(sp.getString("carrierWins", "{}") ?: "{}")
            val o = root.optJSONObject(carrier) ?: JSONObject()
            o.put(key, System.currentTimeMillis())
            // Держим 40 свежих на оператора — старое уходит само.
            if (o.length() > 40) {
                val old = o.keys().asSequence().minByOrNull { o.optLong(it) }
                if (old != null) o.remove(old)
            }
            root.put(carrier, o)
            sp.edit().putString("carrierWins", root.toString()).apply()
        } catch (_: Throwable) {}
    }

    // ПАМЯТЬ ПРОВАЛОВ ПО ОПЕРАТОРУ — первый шаг «змеи».
    //
    //  Удачи мы помнили, провалы — нет: нода, которая на этом операторе
    //  валится раз за разом, снова шла в каскад наравне со всеми. Здесь —
    //  сколько раз ПОДРЯД нода не поднялась на этой сети; удача обнуляет.
    fun carrierLosses(carrier: String): Map<String, Int> = try {
        val o = JSONObject(sp.getString("carrierLosses", "{}") ?: "{}").optJSONObject(carrier)
        o?.keys()?.asSequence()?.associateWith { o.optInt(it) } ?: emptyMap()
    } catch (_: Throwable) { emptyMap() }

    fun rememberCarrierLoss(carrier: String, key: String, lost: Boolean) {
        if (carrier.isEmpty() || key.isEmpty()) return
        try {
            val root = JSONObject(sp.getString("carrierLosses", "{}") ?: "{}")
            val o = root.optJSONObject(carrier) ?: JSONObject()
            if (lost) o.put(key, o.optInt(key) + 1) else o.remove(key)
            if (o.length() > 80) o.keys().asSequence().firstOrNull()?.let { o.remove(it) }
            root.put(carrier, o)
            sp.edit().putString("carrierLosses", root.toString()).apply()
        } catch (_: Throwable) {}
    }

    // Резолвер, который назвала подписка. Хранится, чтобы режим «Авто»
    // работал и до первой загрузки — например сразу после перезапуска.
    var autoDns: String
        get() = sp.getString("autoDns", "") ?: ""
        set(v) = sp.edit().putString("autoDns", v.trim()).apply()

    // Автоматический сброс DNS-кеша при смене сети (Wi-Fi → сотовая и обратно).
    //
    // ЗАЧЕМ: на новой сети адреса нод надо разрешать заново — другой
    // оператор, другие правила, а DoH, который на прошлой сети молчал, на
    // этой может быть жив. Сбрасывается наш кеш адресов и метка «DoH мёртв».
    // Кеш ядра и системный этим не трогаются — см. DnsCacheManager, там
    // объяснено, почему и почему это не страшно.
    //
    // По умолчанию ВКЛЮЧЕН — это безопасно: лишний сброс стоит пару лишних
    // DNS-запросов, а пропущенный сброс стоит минуты без интернета.
    var autoFlushDns: Boolean
        get() = sp.getBoolean("autoFlush", true)
        set(v) = sp.edit().putBoolean("autoFlush", v).apply()

    // Чем ядро разрешает имена ВНУТРИ туннеля (идёт в конфиг).
    fun dnsUrl(): String = DnsChoice.tunnelUrl(dnsProvider, dnsCustom)

    // Чем МЫ разрешаем адрес ноды, пока туннеля ещё нет. Отличается только
    // в режиме «Авто»: совет провайдера хорош внутри канала и опасен снаружи.
    fun preDnsUrl(): String = DnsChoice.preUrl(dnsProvider, dnsCustom)

    // ------------------------------------------- сцеплять ли VPN с ByeDPI
    //
    // ByeDPI задумывался как САМОСТОЯТЕЛЬНАЯ функция: обход без VPN. Но в
    // конфиге он оказался вшит в цепочку — при включённом щите весь трафик
    // ноды шёл через него (detour → dpi-socks). Получилась зависимость,
    // которой не должно было быть: выключаешь щит — и VPN перестаёт работать
    // вовсе, хотя это два независимых механизма.
    //
    // Теперь это ОТДЕЛЬНЫЙ переключатель, а не следствие включённого щита:
    // сцепку можно выключить, не выключая сам щит, и наоборот.
    //
    // Но ПО УМОЛЧАНИЮ она ВКЛЮЧЕНА, и это не лень, а результат проверки на
    // живой сети 14.08.2026 (Tele2/МТС, режим ограничений). Мы отвязали её
    // по умолчанию — и перестали работать ВСЕ ноды разом: и обычная Reality,
    // и обходная через Yandex CDN. Туннель поднимался, а трафик не шёл:
    // «open outbound connection: context deadline exceeded».
    //
    // Вывод: у этих операторов фрагментация ByeDPI — не украшение, а условие,
    // при котором вообще устанавливается соединение с нодой. Выключать её по
    // умолчанию значит отдавать человеку заведомо неработающий клиент.
    var chainVpnViaDpi: Boolean
        get() = sp.getBoolean("chainDpi", true)
        set(v) = sp.edit().putBoolean("chainDpi", v).apply()

    // --------------------------------------------------- чем поднимать ноду
    //
    // 0 — сами решим по транспорту (обычный случай), 1 — только sing-box,
    // 2 — только Xray.
    //
    // Ручной выбор нужен, когда автоматика формально права, а на практике
    // проходит именно второе ядро: они по-разному собирают TLS-приветствие,
    // и сервер бывает согласен ровно с одним. Выбрал руками — значит выбрал:
    // автоматический откат на второе ядро в этом режиме НЕ срабатывает.
    var tunnelEngine: Int
        get() = sp.getInt("tunEngine", 0)
        set(v) = sp.edit().putInt("tunEngine", v).apply()

    // ------------------------------------------------ раздача на компьютер
    //
    // Телефон в режиме модема раздаёт интернет, а компьютер ходит через наш
    // прокси и попадает в туннель. Android не пускает трафик раздачи в TUN
    // (у пересылаемых пакетов нет владельца-приложения, а VPN-правила
    // применяются именно по нему), поэтому прокси — единственный путь без
    // root. Зато компьютер сам решает, идти через туннель или напрямую.
    var lanProxy: Boolean
        get() = sp.getBoolean("lanProxy", false)
        set(v) = sp.edit().putBoolean("lanProxy", v).apply()

    var lanUser: String
        get() = sp.getString("lanUser", "zen") ?: "zen"
        set(v) = sp.edit().putString("lanUser", v.trim()).apply()

    // Пароль на прокси. Пустой — вход свободный, и это опасно: в общей сети
    // таким ретранслятором воспользуется кто угодно, а наружу это пойдёт как
    // наш трафик. Поэтому при первом включении пароль создаётся сам.
    var lanPass: String
        get() = sp.getString("lanPass", "") ?: ""
        set(v) = sp.edit().putString("lanPass", v.trim()).apply()

    fun ensureLanPass(): String {
        if (lanPass.isEmpty()) {
            val abc = "abcdefghijkmnpqrstuvwxyz23456789"   // без похожих 0/o, 1/l
            lanPass = (1..10).map { abc.random() }.joinToString("")
        }
        return lanPass
    }

    // ------------------------------------------------------------ kill switch
    //
    // Когда туннель падает, трафик НЕ должен молча уйти в открытую сеть.
    // Интерфейс остаётся поднятым, но за ним никого нет — пакеты умирают
    // в нём. Снаружи это выглядит как «интернета нет», и это правильное
    // поведение: лучше без сети, чем с засвеченным адресом.
    //
    // По умолчанию ВЫКЛЮЧЕН: включённый по умолчанию оставил бы без связи
    // человека, который просто не понял, почему всё встало.
    var killSwitch: Boolean
        get() = sp.getBoolean("killSwitch", false)
        set(v) = sp.edit().putBoolean("killSwitch", v).apply()

    // --------------------------------------------------------- вид заголовка
    //
    // Готический шрифт зашёл не всем, поэтому надпись сверху настраивается:
    // тап по ней открывает выбор. Пустой текст = «ZEN NITRO».
    var titleText: String
        get() = sp.getString("titleText", "") ?: ""
        set(v) = sp.edit().putString("titleText", v.take(24)).apply()

    // Номер из ZEN_TITLE_FONTS. Дописывать шрифты только в конец списка.
    var titleFont: Int
        get() = sp.getInt("titleFont", 0)
        set(v) = sp.edit().putInt("titleFont", v).apply()

    // Кегль в sp. Границы держим здесь, а не в интерфейсе: слишком мелкое
    // не прочитать, слишком крупное наезжает на кнопки справа.
    var titleSize: Int
        get() = sp.getInt("titleSize", 30).coerceIn(TITLE_MIN, TITLE_MAX)
        set(v) = sp.edit().putInt("titleSize", v.coerceIn(TITLE_MIN, TITLE_MAX)).apply()

    // ------------------------------------------------------------- обновления
    //
    // Адрес списка версий. Пустая строка = проверок нет вовсе: никаких
    // походов наружу, никакой кнопки. Так и должно быть у того, кто раздаёт
    // сборку сам и не хочет, чтобы клиент куда-то стучался.
    var updateUrl: String
        get() = sp.getString("updUrl", DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(v) = sp.edit().putString("updUrl", v.trim()).apply()

    var updCheckedAt: Long
        get() = sp.getLong("updAt", 0L)
        set(v) = sp.edit().putLong("updAt", v).apply()

    // Последнее известное предложение. Держим на диске, чтобы кнопка была
    // на месте сразу при запуске, а не после ответа сервера.
    var updCode: Int
        get() = sp.getInt("updCode", 0)
        set(v) = sp.edit().putInt("updCode", v).apply()

    var updName: String
        get() = sp.getString("updName", "") ?: ""
        set(v) = sp.edit().putString("updName", v).apply()

    var updNotes: String
        get() = sp.getString("updNotes", "") ?: ""
        set(v) = sp.edit().putString("updNotes", v).apply()

    var updUrl: String
        get() = sp.getString("updApk", "") ?: ""
        set(v) = sp.edit().putString("updApk", v).apply()

    var updSize: Long
        get() = sp.getLong("updSize", 0L)
        set(v) = sp.edit().putLong("updSize", v).apply()

    fun forgetUpdate() = sp.edit()
        .remove("updCode").remove("updName").remove("updNotes")
        .remove("updApk").remove("updSize").apply()

    // Версия скачанных гео-правил. 0 — стоят те, что приехали в сборке.
    var geoVersion: Int
        get() = sp.getInt("geoVer", 0)
        set(v) = sp.edit().putInt("geoVer", v).apply()

    fun subscriptions(): List<Subscription> {
        val raw = sp.getString("subs", null) ?: return defaultSubs()
        val arr = JSONArray(raw)
        val all = (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Subscription(o.getString("name"), o.getString("url"))
        }
        // Одна и та же ссылка дважды — одна подписка, а не две: 18.10.2026
        // Ultra от Sirius оказалась в списке дважды, и её ноды шли двумя
        // группами. Лишнее убираем и сохраняем уже без дублей.
        val uniq = all.distinctBy { it.url.trim() }
        if (uniq.size != all.size) saveSubscriptions(uniq)
        return uniq
    }

    fun saveSubscriptions(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        sp.edit().putString("subs", arr.toString()).apply()
    }

    // Имя подписки — по умолчанию адрес сервера, и у одного провайдера их
    // бывает несколько на одном хосте (у Sirius — Main и Ultra на
    // s.creati.men): обе звались «s.creati.men», а их ноды сливались в одну
    // группу. Совпадения разводим номером, а после первой загрузки имя
    // заменяется заголовком самой панели (см. Subscriptions.renamed).
    // false — такая ссылка уже есть.
    fun addSubscription(name: String, url: String): Boolean {
        val cur = subscriptions()
        if (cur.any { it.url.trim() == url.trim() }) return false
        saveSubscriptions(cur + Subscription(uniqueSubName(name), url))
        return true
    }

    fun uniqueSubName(base: String, exceptUrl: String = ""): String {
        val taken = subscriptions().filter { it.url != exceptUrl }.map { it.name }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    fun renameSubscription(url: String, name: String) =
        saveSubscriptions(subscriptions().map { if (it.url == url) it.copy(name = name) else it })

    // По ССЫЛКЕ, а не по номеру строки. Номер брался из списка на экране, а
    // список в настройках мог уже поменяться (сведение повторов, имя от
    // панели): 01.11.2026 «удалить» у Sirius снесла соседнюю FastLane.
    fun removeSubscription(url: String) =
        saveSubscriptions(subscriptions().filter { it.url.trim() != url.trim() })

    // ПУСТО — И ТАК ДОЛЖНО БЫТЬ.
    //
    // Здесь лежали две рабочие подписки с личными токенами. В сборке, которую
    // раздают людям, их быть не может: токен один на всех, провайдер видит
    // массовые подключения с одного ключа и закрывает его — вместе с доступом
    // у владельца. Свои подписки пользователь добавляет сам в настройках.
    private fun defaultSubs(): List<Subscription> = emptyList()
}
