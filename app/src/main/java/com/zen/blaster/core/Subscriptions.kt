package com.zen.blaster.core

import android.util.Base64
import com.zen.blaster.model.Node
import com.zen.blaster.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

// Загрузка и парсинг подписок — Kotlin-порт fetch_nodes.py.
object Subscriptions {

    // 15 секунд, а не 8.
    //
    // Подписка тянется в том числе когда туннель уже поднят, то есть запрос
    // идёт через него же — по мобильной сети восьми секунд не хватало, и
    // источник «молчал» на ровном месте. Ждать чуть дольше дешевле, чем
    // потерять список серверов.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Предел на весь запрос целиком. readTimeout считает паузу между
        // кусками, и подписка, которая цедит ответ по байту, держала
        // «обновить» минутами — кнопка выглядела мёртвой.
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val DIRECT = listOf("vless://", "hysteria2://", "hy2://", "trojan://", "ss://", "tuic://")

    // measure=false — не мерить пинги. Подписки тянутся при каждом открытии
    // приложения, и раньше вместе с ними каждый раз шёл полный замер двухсот
    // серверов. Пинг за десять минут не меняется, а нагрузка от веера —
    // настоящая. Цифры при этом не теряются: их переносят со старого списка.
    suspend fun loadAll(subs: List<Subscription>, gentle: Boolean = false,
                        measure: Boolean = true): List<Node> =
            withContext(Dispatchers.IO) {
        unparsed.clear()
        report.clear()
        val nodes = mutableListOf<Node>()
        for (s in subs) {
            report += "▪ ${s.name}"
            // Помечаем каждую ноду её подпиской: иначе в общем списке из сотни
            // серверов не понять, кто чей.
            val part = fetchOne(s.url).onEach { it.sub = s.name }
            nodes += part
        }
        if (measure) pingAll(nodes, gentle)
        // Сначала по подписке (чтобы группы шли подряд), внутри — по протоколу
        // и задержке.
        nodes.sortedWith(compareBy(
            { it.sub },
            { it.badge() },
            { if (it.ping in 1..899) it.ping else 100000 }
        ))
    }

    // ПРЕДСТАВЛЯЕМСЯ ТЕМ КЛИЕНТОМ, КОТОРОГО ПАНЕЛЬ ЗНАЕТ.
    //
    // Панели подписок (Marzban, 3x-ui, Remnawave и прочие) отдают РАЗНОЕ в
    // зависимости от того, кто пришёл. Незнакомому клиенту прилетает страница
    // «приложение не поддерживается» — и подписка, за которую человек заплатил,
    // выглядит пустой. Ноды при этом есть, их просто не показывают нам.
    //
    // Поэтому не выдумываем своё имя, а перебираем имена популярных клиентов,
    // пока не придёт то, что разбирается в ноды. Первым — Happ: сейчас
    // большинство панелей настроено под него.
    //
    // Порядок важен: панель отдаёт формат под конкретный клиент, и для Clash
    // это будет YAML, который мы не разбираем. Поэтому клиенты со «ссылочным»
    // форматом идут первыми.
    //
    // Версии держим свежими. Панели смотрят не только на имя, но и на номер:
    // старому клиенту отдают урезанный шаблон, а то и вовсе «обновите
    // приложение». Happ на телефоне оператора 6 сентября 2026 — 4.3.0,
    // а мы годами представлялись 1.62.
    private val AGENTS = listOf(
        "Happ/4.3.0",
        "v2rayNG/1.9.16",
        "v2rayN/6.42",
        "Streisand/1.6.30",
        "Shadowrocket/2.2.ardo",
        "sing-box/1.11.0",
        "NekoBox/1.3.1",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
    )

    // Что вернула последняя попытка — показываем в журнале, чтобы человек
    // видел, каким именем подписка вообще открылась.
    @Volatile var lastAgent: String = ""

    // DoH-резолвер, который назвала сама подписка. Пусто — никто не называл.
    // Живёт до следующей загрузки; сохранённое значение лежит в настройках.
    @Volatile var subDns: String = ""

    // ПАНЕЛЬ ОТДАЁТ РАЗНЫМ КЛИЕНТАМ РАЗНЫЙ НАБОР НОД.
    //
    // Это не догадка, а то, что видно глазами: в Hiddify из подписки
    // fastlanehosting приходит десяток нод Hysteria2, а у нас из той же
    // подписки — ни одной, только VLESS. Причина в шаблонах: панель держит
    // для каждого известного клиента свой список и отдаёт то, что тот, по её
    // мнению, умеет. Мы брали ПЕРВЫЙ непустой ответ — и на этом успокаивались,
    // теряя всё, чего не было в happ-шаблоне.
    //
    // Поэтому спрашиваем тремя именами сразу и СКЛАДЫВАЕМ результаты. Ноды
    // из разных шаблонов совпадают по адресу и порту, дубликаты отсеиваются.
    // Порядок ВАЖЕН: при совпадении ноды побеждает тот, кто в списке выше.
    //
    // Первыми идут клиенты, которым панель отдаёт обычные ссылки
    // (vless://…?pbk=…&sid=…&flow=…) — в них параметры лежат ровно так, как
    // их задумал провайдер. Happ-шаблон у части панелей отдаёт вместо ссылок
    // готовые JSON-конфиги Xray: разбор такого файла всегда беднее, часть
    // полей до нас просто не доезжает, и нода потом не поднимается, хотя
    // сервер жив. Поэтому Happ — последний: он нужен там, где ссылок нет
    // вовсе (так отдаёт, например, Capybara).
    private val UNION_AGENTS = listOf(
        "Hiddify/2.5.7",
        "v2rayNG/1.9.16",
        "Happ/4.3.0",
    )

    // Строки, похожие на ссылку, но не разобранные. Молчаливая потеря ноды —
    // ровно та болезнь, из-за которой Hysteria2 не было видно месяцами.
    // Копится за всю загрузку и показывается в журнале одним списком.
    val unparsed = java.util.Collections.synchronizedList(mutableListOf<String>())

    private suspend fun fetchOne(url: String): List<Node> {
        val u = url.trim()
        if (DIRECT.any { u.startsWith(it) }) return listOfNotNull(parseLine(u))

        // ПРИМЕТА СЕРВЕРА ПРИ СКЛЕЙКЕ ТРЁХ ОТВЕТОВ.
        //
        // Один и тот же список мы просим тремя именами клиентов и сводим
        // вместе. Раньше приметой были только протокол, адрес с портом и
        // транспорт — и всё, что совпало по ним, считалось одним сервером.
        //
        // Но провайдер держит на одном хосте и порту несколько РАЗНЫХ серверов,
        // различая их путём, ключом или именем: у Sirius это отдельные входы
        // под YouTube, под российские сервисы, под торренты. Такие ноды
        // склеивались в одну, и подписка на 15 серверов превращалась в 8.
        //
        // Имя в примету входит последним и в осторожном виде: разные клиенты
        // подписки дают ему разные украшения (эмодзи, пометки «Новый»), и
        // сравнивать их дословно — значит раздвоить одну и ту же ноду.
        fun mergeKey(n: Node): String {
            val plain = n.name.filter { it.isLetterOrDigit() }.lowercase()
            return "${n.type}|${n.addr}:${n.port}|${n.transport}|${n.path}|" +
                "${n.uuid}${n.password}${n.auth}|${n.sni}|$plain"
        }

        // Первый заход — тремя именами разом.
        val union = coroutineScope {
            UNION_AGENTS.map { a -> async(Dispatchers.IO) { a to get(u, a) } }.awaitAll()
        }
        // «Связи нет» — это когда не ответил НИКТО. Если хоть один ответ дошёл,
        // работаем с ним: у одного из трёх запросов может быть свой таймаут,
        // свой 429 от панели, своя осечка сети — терять из-за неё всю подписку
        // нельзя, иначе список серверов пропадает на ровном месте.
        if (union.all { it.second == null }) return emptyList()
        val merged = LinkedHashMap<String, Node>()
        val gotFrom = mutableListOf<String>()
        for ((agent, res) in union) {
            val short = agent.substringBefore('/')
            if (res == null) { report += "$short: не ответил"; continue }
            val part = decode(res.second)
            report += "$short: HTTP ${res.first}, ${res.second.length} симв. → ${part.size} нод"
            // ОТВЕТИЛА, НО НИ ОДНОЙ НОДЫ — САМЫЙ ОБИДНЫЙ СЛУЧАЙ.
            //
            // Подписка жива, тело пришло, а разобрать мы его не смогли: панель
            // отдала формат, которого мы не знаем. Со стороны это выглядит как
            // «серверы пропали» — и ровно так и выглядело у Lunaire, где
            // Hiddify-шаблон отдаёт девять килобайт и ноль нод, а ноды из него
            // уходят в «нет в подписке» и перестают отвечать.
            //
            // Без начала тела в журнале понять причину нельзя, а гадать по
            // симптому — это то, чем мы занимались неделями. Показываем ровно
            // столько, чтобы опознать формат: ключи или первые строки. Токен
            // из URL сюда не попадает — это тело ответа, а не адрес.
            if (part.isEmpty() && res.second.isNotBlank()) {
                val head = res.second.trim().take(110)
                    .replace('\n', '⏎').replace('\r', ' ')
                report += "   ↳ не разобрано, начало: $head"
            }
            if (part.isEmpty()) continue
            gotFrom += short
            for (n in part) merged.getOrPut(mergeKey(n)) { n }
        }
        if (merged.isNotEmpty()) {
            lastAgent = gotFrom.joinToString("+")
            return merged.values.toList()
        }

        // Никто из троих не признал — перебираем остальные имена по одному.
        for (agent in AGENTS) {
            val body = try {
                http.newCall(
                    Request.Builder().url(u)
                        .header("User-Agent", agent)
                        // Без этого некоторые панели отдают HTML-страницу
                        // личного кабинета вместо самой подписки.
                        .header("Accept", "*/*")
                        .build()
                ).execute().use { it.body?.string() ?: "" }
            } catch (e: Exception) {
                // СВЯЗИ НЕТ — и перебирать имена клиентов бессмысленно.
                //
                // Имя клиента меняет только СОДЕРЖИМОЕ ответа, а не то,
                // доходит ли запрос вообще. Первая версия этого не различала
                // и в день ограничений долбилась восемь раз по пятнадцать
                // секунд в каждую молчащую подписку: загрузка списка вместо
                // полуминуты занимала больше десяти минут.
                return emptyList()
            }
            if (body.isBlank()) return emptyList()   // ответ пустой — то же самое

            val nodes = decode(body)
            if (nodes.isNotEmpty()) {
                lastAgent = agent.substringBefore('/')
                return nodes
            }
            // Сервер ОТВЕТИЛ, но нод нет — вот тут имя клиента и решает:
            // панель отдала «приложение не поддерживается». Пробуем следующее.
        }
        return emptyList()
    }

    // Один поход за подпиской. null — связи нет (это НЕ то же самое, что
    // «панель не дала нод»: в первом случае перебирать имена бессмысленно).
    // Возвращаем и код ответа: «панель молчит» и «панель отдала 403» лечатся
    // по-разному, а со стороны выглядят одинаково — пустым списком.
    private fun get(url: String, agent: String): Pair<Int, String>? = try {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", agent)
                .header("Accept", "*/*")
                .build()
        ).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: Exception) { null }

    // Что ответила каждая панель на этой загрузке — построчно, для журнала.
    // Именно здесь видно, что happ-шаблон отдал 22 ноды, а hiddify-шаблон 34:
    // без этой строки пропажу целого протокола нечем доказать.
    val report = java.util.Collections.synchronizedList(mutableListOf<String>())

    // Что ответила панель, когда не признала нас за своего. Пустая строка —
    // такого не было.
    @Volatile var lastStub: String = ""

    // Разбор тела подписки: голые ссылки, base64 или base64 с переносами.
    private fun decode(body: String): List<Node> {
        // Подписка может отдавать не ссылки, а готовые конфиги Xray в JSON —
        // так делает Capybara. Раньше мы искали в теле «://», не находили и
        // докладывали «ни одного сервера», хотя серверы были.
        // Конфиг sing-box проверяем ПЕРВЫМ: у него тоже есть «outbounds», и
        // проверка Xray принимает его за свой, после чего разбирает в ноль.
        if (SingBoxConfigParser.looksLike(body)) {
            val fromBox = SingBoxConfigParser.parse(body)
            if (fromBox.isNotEmpty()) {
                SingBoxConfigParser.dnsFrom(body)?.let { subDns = it }
                return fromBox
            }
        }
        if (XrayConfigParser.looksLikeConfig(body)) {
            val fromCfg = XrayConfigParser.parse(body)
            // Заодно запоминаем резолвер, который назвал сам провайдер: он
            // знает, какой DNS отдаёт для его нод правильные адреса, а не
            // подменённые оператором. Пригодится режиму «Авто».
            if (fromCfg.isNotEmpty()) {
                XrayConfigParser.dnsFrom(body)?.let { subDns = it }
                return fromCfg
            }
            XrayConfigParser.stubName(body)?.let { lastStub = it }
            return emptyList()
        }

        val direct = Regex("(vless|hysteria2|hy2|tuic|trojan|ss)://")
        val text = if (direct.containsMatchIn(body)) body
        else try {
            // Панели переносят base64 по строкам и иногда шлют url-safe вариант.
            val clean = body.trim().replace("\n", "").replace("\r", "")
                .replace('-', '+').replace('_', '/')
            String(Base64.decode(clean, Base64.DEFAULT))
        } catch (e: Exception) { body }

        // Считаем не только то, что разобрали, но и то, что НЕ разобрали.
        //
        // Раньше нераспознанная ссылка просто исчезала: parseLine возвращал
        // null, и строка растворялась. Так и жили с потерянными Hysteria2 —
        // ни в списке, ни в логе, ни следа. Теперь каждая такая строка
        // попадает в журнал, обрезанная до схемы и хоста (ключи и пароли
        // наружу не отдаём).
        val out = mutableListOf<Node>()
        val lost = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val n = parseLine(line)
            if (n != null) { out += n; continue }
            if (line.contains("://")) lost += safeShape(line)
        }
        // Одну и ту же подписку мы спрашиваем несколькими именами клиента,
        // поэтому непонятая строка приходит по разу на каждый ответ. В журнал
        // такую тройную бухгалтерию класть нельзя — счёт потерь должен быть
        // честным.
        synchronized(unparsed) { lost.forEach { if (it !in unparsed) unparsed += it } }
        return out
    }

    // Как показать непонятую ссылку, ничего не выдав: схема, хост и длина.
    private fun safeShape(line: String): String {
        val scheme = line.substringBefore("://", "?")
        val tail = line.substringAfter("://", "")
        val host = tail.substringAfterLast('@').substringBefore('?')
            .substringBefore('#').take(40)
        return "$scheme:// … @$host (${line.length} симв.)"
    }

    private fun parseLine(line: String): Node? = when {
        line.startsWith("vless://") -> parseVless(line)
        line.startsWith("vmess://") -> parseVmess(line)
        line.startsWith("hysteria2://") || line.startsWith("hy2://") -> parseHy2(line)
        line.startsWith("tuic://") -> parseTuic(line)
        line.startsWith("trojan://") -> parseTrojan(line)
        line.startsWith("ss://") -> parseSs(line)
        else -> null
    }

    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    private fun params(qs: String): Map<String, String> =
        qs.split("&").mapNotNull { val i = it.indexOf('='); if (i < 0) null else dec(it.substring(0, i)) to dec(it.substring(i + 1)) }.toMap()

    // ------------------------------------------------------------------
    //  РАЗБОР ССЫЛКИ ПО ЧАСТЯМ, А НЕ ОДНОЙ РЕГУЛЯРКОЙ НА ВСЁ.
    //
    //  Прежние регулярки требовали, чтобы у ссылки ОБЯЗАТЕЛЬНО был «?» с
    //  параметрами. Для VLESS это почти всегда так, а для Hysteria2 — нет:
    //  когда у ноды нет ни obfs, ни отдельного sni, панель отдаёт короткое
    //  «hysteria2://пароль@хост:порт#Имя». Такая ссылка не подходила под
    //  шаблон, parseLine возвращал null, и нода пропадала МОЛЧА — ни в
    //  списке, ни в логе. Отсюда и «у нас нет ни одной Hysteria2», хотя в
    //  той же подписке другой клиент их прекрасно показывает.
    //
    //  Заодно чинятся ещё три случая, которые терялись так же тихо:
    //   • IPv6-адрес в квадратных скобках — «[2a01:4f8::1]:443»;
    //   • порт-хоппинг Hysteria2 — «host:443,20000-50000»;
    //   • ссылка вообще без порта — берём 443.
    // ------------------------------------------------------------------
    private data class Parts(
        val user: String,          // до «@»: uuid, пароль, auth
        val host: String,
        val port: Int,
        val ports: String,         // диапазон порт-хоппинга, если он указан
        val path: String,
        val q: Map<String, String>,
        val name: String
    )

    private fun splitUri(line: String, scheme: String, defPort: Int = 443): Parts? {
        var rest = line.removePrefix("$scheme://")
        if (rest.isEmpty()) return null

        // Имя ноды — всё после «#», и оно может содержать что угодно, включая
        // «?» и «@», поэтому отрезаем его первым.
        var name = ""
        val h = rest.indexOf('#')
        if (h >= 0) { name = dec(rest.substring(h + 1)); rest = rest.substring(0, h) }

        var qs = ""
        val qm = rest.indexOf('?')
        if (qm >= 0) { qs = rest.substring(qm + 1); rest = rest.substring(0, qm) }

        // Разделяем по ПОСЛЕДНЕМУ «@»: в пароле Trojan/Hysteria2 «@» законен.
        val at = rest.lastIndexOf('@')
        if (at < 0) return null
        val user = rest.substring(0, at)
        var hostPort = rest.substring(at + 1)

        var path = ""
        val slash = hostPort.indexOf('/')
        if (slash >= 0) { path = hostPort.substring(slash); hostPort = hostPort.substring(0, slash) }

        val host: String
        var portPart: String
        if (hostPort.startsWith("[")) {                 // IPv6
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            portPart = hostPort.substring(close + 1).removePrefix(":")
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0) { host = hostPort; portPart = "" }
            else { host = hostPort.substring(0, colon); portPart = hostPort.substring(colon + 1) }
        }
        if (host.isBlank()) return null

        // «443,20000-50000» — первый порт рабочий, остальное диапазон прыжков.
        val ports = if (portPart.contains(',') || portPart.contains('-')) portPart else ""
        val first = Regex("\\d+").find(portPart)?.value
        val port = first?.toIntOrNull() ?: defPort

        return Parts(user, host, port, ports, path, params(qs), name.ifEmpty { host })
    }

    private fun parseVless(l: String): Node? {
        val u = splitUri(l, "vless") ?: return null
        val uuid = u.user; val addr = u.host; val port = u.port; val name = u.name
        val p = u.q
        return Node("VLESS", name.ifEmpty { addr }, addr, port, uuid = uuid,
            encryption = (p["encryption"] ?: "").takeIf { it != "none" } ?: "",
            sni = p["sni"] ?: "", pbk = p["pbk"] ?: "", sid = p["sid"] ?: "", flow = p["flow"] ?: "",
            transport = p["type"] ?: "tcp", path = p["path"] ?: "", host = p["host"] ?: "",
            serviceName = p["serviceName"] ?: "", alpn = p["alpn"] ?: "", security = p["security"] ?: "tls",
            fp = p["fp"] ?: "",
            // XHTTP-специфика: без mode и extra (xmux, scMax*, xPaddingBytes)
            // сервер отвечает 400/таймаутом — параметры должны совпасть точно.
            mode = p["mode"] ?: "", extra = p["extra"] ?: "",
            seed = p["seed"] ?: "", headerType = p["headerType"] ?: "",
            insecure = truthy(p["allowInsecure"]) || truthy(p["insecure"]))
    }

    // VMESS — ССЫЛКА, КОТОРУЮ МЫ ГОДАМИ ТЕРЯЛИ.
    //
    //  Формат у неё не такой, как у остальных: не «схема://пользователь@хост
    //  ?параметры», а base64 от JSON целиком. Наш разбор искал знакомую форму,
    //  не находил — и нода молча пропадала. В журнале это была вечная строка
    //  «⚠️ Не разобрано ссылок: 1», и до 6 сентября 2026 никто не знал, какая
    //  именно: оказалось, австрийская нода из SiriusVPN, живая.
    //
    //  Поля панели пишут кто во что горазд: порт и aid бывают и числом, и
    //  строкой, «tls» лежит в отдельном ключе, имя хоста — то в sni, то в host.
    //  Поэтому читаем терпимо, а не по букве.
    private fun parseVmess(l: String): Node? {
      return try {
        val raw = l.removePrefix("vmess://").trim()
        val json = String(Base64.decode(
            raw.replace('-', '+').replace('_', '/'), Base64.DEFAULT))
        val o = org.json.JSONObject(json)
        fun str(vararg keys: String): String {
            for (k in keys) {
                val v = o.opt(k) ?: continue
                val t = v.toString().trim()
                if (t.isNotEmpty() && t != "null") return t
            }
            return ""
        }
        val addr = str("add", "address", "host")
        val port = str("port").toIntOrNull() ?: return null
        if (addr.isEmpty()) return null
        val net = str("net", "network").ifEmpty { "tcp" }
        val tls = str("tls", "security")
        Node(
            "VMess", str("ps", "remarks", "name").ifEmpty { addr }, addr, port,
            uuid = str("id"),
            alterId = str("aid", "alterId").toIntOrNull() ?: 0,
            // scy — способ шифрования полезной нагрузки. «auto» подходит всем.
            encryption = str("scy", "security").takeIf {
                it in setOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305")
            } ?: "auto",
            sni = str("sni", "peer"),
            transport = net,
            path = str("path"),
            host = str("host"),
            serviceName = str("serviceName", "path").takeIf { net == "grpc" } ?: "",
            alpn = str("alpn"),
            security = if (tls == "tls" || tls == "reality") "tls" else "none",
            fp = str("fp"),
            headerType = str("type").takeIf { it != "none" } ?: "",
            insecure = truthy(str("allowInsecure", "insecure"))
        )
      } catch (_: Throwable) { null }
    }

    private fun parseHy2(l0: String): Node? {
        val l = l0.replaceFirst("hy2://", "hysteria2://")
        val u = splitUri(l, "hysteria2") ?: return null
        val p = u.q
        // Имена параметров у Hysteria2 разнятся от панели к панели: sni/peer,
        // obfs-password/obfs_password, insecure=1/true. Принимаем все — иначе
        // нода поднимется без обфускации или без нужного имени и просто не
        // заработает, а выглядеть будет как «сервер плохой».
        return Node("Hysteria2", u.name, u.host, u.port,
            auth = dec(u.user),
            sni = p["sni"] ?: p["peer"] ?: "",
            alpn = p["alpn"] ?: "",
            obfs = p["obfs"] ?: "",
            obfsPw = p["obfs-password"] ?: p["obfs_password"] ?: p["obfsParam"] ?: "",
            // Порт-хоппинг: диапазон из адреса либо из mport/ports.
            hopPorts = u.ports.ifEmpty { p["mport"] ?: p["ports"] ?: "" },
            insecure = truthy(p["insecure"]) || truthy(p["allowInsecure"]))
    }

    private fun parseTuic(l: String): Node? {
        val u = splitUri(l, "tuic") ?: return null
        val p = u.q
        // В userinfo лежит «uuid:пароль», и пароль может содержать «:».
        val c = u.user.indexOf(':')
        val uuid = if (c < 0) u.user else u.user.substring(0, c)
        val pw = if (c < 0) "" else dec(u.user.substring(c + 1))
        return Node("TUIC", u.name, u.host, u.port, uuid = uuid, password = pw,
            sni = p["sni"] ?: p["peer"] ?: "", alpn = p["alpn"] ?: "h3",
            cc = p["congestion_control"] ?: "bbr",
            insecure = truthy(p["allow_insecure"]) || truthy(p["insecure"]))
    }

    private fun parseTrojan(l: String): Node? {
        val u = splitUri(l, "trojan") ?: return null
        val p = u.q
        return Node("Trojan", u.name, u.host, u.port, password = dec(u.user),
            sni = p["sni"] ?: p["peer"] ?: "", alpn = p["alpn"] ?: "", fp = p["fp"] ?: "",
            insecure = truthy(p["allowInsecure"]) || truthy(p["insecure"]))
    }

    // «1», «true», «yes» — панели пишут по-разному, значение одно.
    private fun truthy(v: String?) = v != null && v.lowercase() in setOf("1", "true", "yes")

    private fun parseSs(l: String): Node? {
        var body = l.substring(5); var name = ""
        val h = body.indexOf('#'); if (h >= 0) { name = dec(body.substring(h + 1)); body = body.substring(0, h) }
        val at = body.lastIndexOf('@'); if (at < 0) return null
        val userinfo = body.substring(0, at); val host = body.substring(at + 1)
        val creds = try {
            val d = String(Base64.decode(userinfo, Base64.DEFAULT)); if (d.contains(':')) d else dec(userinfo)
        } catch (e: Exception) { dec(userinfo) }
        val c = creds.indexOf(':'); if (c < 0) return null
        val cc = host.lastIndexOf(':'); if (cc < 0) return null
        return Node("Shadowsocks", name.ifEmpty { host.substring(0, cc) }, host.substring(0, cc),
            host.substring(cc + 1).toIntOrNull() ?: return null,
            method = creds.substring(0, c), password = creds.substring(c + 1))
    }

    // Сколько держать удачную цифру пинга при осечках следующих замеров.
    const val PING_HOLD = 60 * 60_000L

    // Перемерить задержку по уже загруженному списку — без похода за подписками.
    suspend fun reping(nodes: List<Node>, gentle: Boolean = false) =
        withContext(Dispatchers.IO) { pingAll(nodes, gentle) }

    // ЩАДЯЩИЙ РЕЖИМ — ДЛЯ МОБИЛЬНОЙ СЕТИ.
    //
    //  Замер по всему списку — это не безобидная мелочь. На двухстах нодах он
    //  разом открывает две сотни TCP-соединений, а по каждой молчащей QUIC-ноде
    //  добавляет ещё до десяти боковых портов по полторы секунды и запуск
    //  системного ping. На домашнем Wi-Fi это незаметно, на сотовой связи —
    //  минута забитого эфира, и туннель в это время задыхается: в журнале
    //  4 сентября всплески замера ложатся ровно на записи «канал не отвечает».
    //
    //  Поэтому на мобильной сети: не больше восьми проверок разом, короче
    //  таймауты, боковой веер урезан, системный ping не запускаем вовсе.
    // Сокет по НАСТОЯЩЕЙ сети оператора, мимо нашего же туннеля.
    //
    // Пока туннель поднят, обычный Socket() уходит в TUN, а там ядро
    // принимает соединение само, не дожидаясь сервера, — и замер показывал
    // «1 мс» у нод на другом краю Европы (14.10.2026, ноды fastlane). Такой
    // пинг врёт дважды: сервер мог быть мёртв, а выглядел самым быстрым.
    // Имя ноды разрешаем шифрованным DNS: ответ оператора бывает заглушкой.
    private fun physSocket(): Socket =
        Resolver.physical()?.socketFactory?.createSocket() ?: Socket()

    private fun target(host: String, port: Int): InetSocketAddress {
        val ip = if (Resolver.isIpLiteral(host)) host else Resolver.resolve(host)
        return if (ip != null) InetSocketAddress(java.net.InetAddress.getByName(ip), port)
               else InetSocketAddress(host, port)
    }

    private suspend fun pingAll(nodes: List<Node>, gentle: Boolean = false) = coroutineScope {
        val gate = Semaphore(if (gentle) 8 else 64)
        val wait = if (gentle) 1200 else 2000
        nodes.map { n ->
            async(Dispatchers.IO) {
                val ms = gate.withPermit {
                    try {
                        val addr = target(n.addr, n.port)
                        val t = System.currentTimeMillis()
                        physSocket().use { it.connect(addr, wait) }
                        n.pingExact = true
                        (System.currentTimeMillis() - t).toInt()
                    } catch (e: Exception) {
                        // QUIC-ноде отказ по TCP ничего не говорит: сервер может
                        // слушать только UDP. Помечаем «не измерено» (-1), а не
                        // «мертва» (999) — иначе рабочие ноды пропадают из списка.
                        if (n.quic()) sideProbe(n, gentle) else 999
                    }
                }
                // Удачный замер — новая цифра. Осечка свежую цифру НЕ стирает:
                // один не дошедший пакет ничего не говорит о скорости сервера,
                // а список от таких осечек мигал «нет ответа» через раз. Держим
                // час; молчит дольше — значит правда молчит, говорим как есть.
                val now = System.currentTimeMillis()
                if (ms in 1..899) { n.ping = ms; n.pingAt = now }
                else if (n.pingAt == 0L || now - n.pingAt > PING_HOLD) n.ping = ms
                // МОНИТОР МОЛЧУНОВ.
                //
                // Счётчик ведём только по QUIC-нодам: у остальных отказ по
                // TCP — это уже ответ (999, «мертва»), считать нечего. А
                // молчащая Hysteria2 может быть и живой, и глухой, и отличить
                // их можно только по тому, повторяется ли молчание.
                // Молчание засчитываем в вину ТОЛЬКО на нормальной сети.
                //
                // Здесь была систематическая ошибка: на мобильной связи у
                // QUIC-ноды режется и боковой TCP, и ICMP, поэтому молчат
                // поголовно все — в журнале 4 сентября глухими оказались
                // 8 из 8, потом 4 из 4, и каждый раз ВСЕ до единой Hysteria2,
                // включая ту, через которую туннель в тот день поднимался.
                // Так не выглядят случайно падающие серверы, так выглядит
                // негодный способ измерения. Красный крест по нему не ставим.
                if (n.quic()) { if (ms > 0) n.silentTries = 0 else if (!gentle) n.silentTries++ }
                ms
            }
        }.awaitAll()
    }

    // ЗАДЕРЖКА ДО QUIC-НОДЫ, КОГДА САМ ПОРТ МОЛЧИТ.
    //
    // У Hysteria2 и TUIC порт живёт по UDP и на чужие пакеты не отвечает
    // принципиально: обфускация (salamander) молча отбрасывает всё, что не
    // опознала, а без обфускации сервер ждёт валидное QUIC-рукопожатие.
    // Проверено на живых нодах — QUIC Initial молчит у всех.
    //
    // Но САМ ХОСТ обычно отзывается на соседних портах: у одних открыт 443
    // (веб-маскировка), у других 22. Это тот же сервер и тот же путь до него,
    // поэтому время до него — честная ОЦЕНКА задержки, а не выдумка. Именно
    // так она и подписана в списке: «~41 мс», с тильдой.
    //
    // Раньше здесь стоял прочерк и надпись «QUIC»: формально честно, а на
    // деле бесполезно — по такому списку нельзя выбрать ближний сервер, и
    // все обходные ноды выглядели одинаково никакими.
    // Первые два — самые ходовые, дальше идут порты, которые открывает
    // Cloudflare-совместимая обвязка панелей; 22 в конце, он есть почти
    // везде, но у части хостеров закрыт наглухо для чужих адресов.
    private val SIDE_PORTS = listOf(443, 80, 8443, 2087, 2083, 2053, 2096, 8080, 8880, 22)

    private fun sideProbe(n: Node, gentle: Boolean = false): Int {
        // Нода уже признана глухой: гонять по ней весь веер на каждом замере
        // незачем — это десяток таймаутов по полторы секунды на пустом месте.
        // Молчунов достаточно коротко переспрашивать: ожила — счётчик сбросится
        // сам, и следующий замер снова будет полным.
        val ports = when {
            gentle -> SIDE_PORTS.take(2)       // на сотовой связи веер непозволителен
            n.silentTries >= Node.SILENT_LIMIT -> SIDE_PORTS.take(2)
            else -> SIDE_PORTS
        }
        for (p in ports) {
            if (p == n.port) continue          // этот уже пробовали
            try {
                val t = System.currentTimeMillis()
                physSocket().use { it.connect(target(n.addr, p), if (gentle) 900 else 1500) }
                n.pingExact = false            // цифра приблизительная
                return (System.currentTimeMillis() - t).toInt()
            } catch (_: Exception) { /* пробуем следующий */ }
        }
        // Последняя ступень — эхо-запрос. Раньше здесь стоял InetAddress
        // .isReachable(): на Android он почти всегда впустую, потому что без
        // прав на ICMP молча скатывается в стук по TCP-порту 7, закрытому у
        // всех. Системный ping такими правами обладает и меряет настоящий
        // путь — ровно тот, которым пойдёт и сам туннель.
        // Системный ping — это форк процесса на каждую ноду. Двести форков
        // на мобильной сети телефон переживёт, а канал нет.
        if (!gentle) icmpProbe(n.addr)?.let { n.pingExact = false; return it }
        return -1                              // сказать нечего
    }

    // Одиночный эхо-запрос через системный ping. Цифру берём из вывода, а не
    // по секундомеру вокруг процесса: запуск процесса сам по себе стоит
    // десятки миллисекунд и приписал бы их серверу.
    private fun icmpProbe(addr: String): Int? = try {
        val bin = if (addr.contains(':')) "/system/bin/ping6" else "/system/bin/ping"
        val p = ProcessBuilder(bin, "-c", "1", "-W", "1", "-n", addr)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.destroy()
        Regex("time=([0-9.]+)").find(out)
            ?.groupValues?.get(1)?.toDouble()?.toInt()?.coerceAtLeast(1)
    } catch (_: Exception) { null }
}
