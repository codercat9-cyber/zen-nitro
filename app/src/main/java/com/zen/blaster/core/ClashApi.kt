package com.zen.blaster.core

// ============================================================================
//  Порт управляющего интерфейса ядра (clash_api).
//
//  Через него мы спрашиваем у sing-box задержку до сервера и число соединений.
//  Порт был зашит намертво — 9095, — и это порождало драку на каждой смене
//  сервера: старое ядро ещё держит сокет, новое уже поднимается и падает с
//  «address already in use». Ядро при этом живёт, но своего API не имеет, и
//  проверка канала уходит к остаткам прежнего экземпляра.
//
//  Лечить ожиданием бесполезно: Go-рантайм отпускает сокет когда сочтёт
//  нужным, иногда через несколько секунд, и всё это время человек смотрит на
//  крутящееся кольцо. Поэтому порт больше не один на всех — каждый подъём
//  берёт свободный. Драться становится не за что.
// ============================================================================
object ClashApi {

    // С какого номера начинаем искать. 9095 оставлен первым, чтобы в обычной
    // жизни (одно ядро, порт свободен) ничего не менялось.
    private const val FIRST = 9095
    private const val LAST = 9115

    @Volatile
    var port: Int = FIRST
        private set

    val base: String get() = "http://127.0.0.1:$port"

    // ПАРОЛЬ НА УПРАВЛЯЮЩИЙ ПОРТ — новый на каждый подъём.
    //
    // Без него любое приложение на телефоне могло спросить наше ядро о
    // соединениях и гонять через него запросы («/proxies/proxy/delay» — это
    // готовый запрос через туннель). Так приложения со встроенной аналитикой
    // и вычисляют VPN: стучатся по 127.0.0.1 в известные порты клиентов.
    @Volatile var secret: String = ""
        private set

    fun token(n: Int = 24): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    // Запрос к управляющему порту — сразу с паролем.
    fun req(path: String): okhttp3.Request.Builder =
        okhttp3.Request.Builder().url(base + path).also {
            if (secret.isNotEmpty()) it.header("Authorization", "Bearer $secret")
        }

    // Занять свободный порт под новый экземпляр ядра.
    //
    // Проверяем именно попыткой встать на него: держит ли его прошлое ядро,
    // чужое приложение или система — нам всё равно, важен сам факт занятости.
    fun claim(): Int {
        secret = token()
        for (p in FIRST..LAST) {
            if (free(p)) { port = p; return p }
        }
        // Все двадцать заняты — берём первый и надеемся, что освободится.
        // Это заведомо ненормальная ситуация, но молча падать здесь нельзя.
        port = FIRST
        return FIRST
    }

    // Свободен ли порт.
    //
    // reuse решает, что считать занятостью.
    //   false — строго: не занят никем и не остыл после прошлого хозяина.
    //           Так выбираем СВОЙ порт: взяли — значит наш.
    //   true  — по делу: слушает ли кто-то прямо сейчас. Закрытый сокет
    //           висит в TIME_WAIT около минуты, и строгая проверка всё это
    //           время врёт «занято». Так мы и теряли по три секунды на каждом
    //           подключении через Xray, ожидая порт, который на деле давно
    //           свободен: ядро-то встаёт поверх TIME_WAIT без вопросов.
    fun free(p: Int, reuse: Boolean = false): Boolean = try {
        java.net.ServerSocket().use {
            it.reuseAddress = reuse
            it.bind(java.net.InetSocketAddress("127.0.0.1", p))
        }
        true
    } catch (_: Throwable) {
        false
    }

    // Подставить занятый порт в готовый конфиг ядра.
    //
    // Конфиг собирается заранее (и сохраняется для переподключений), поэтому
    // номер в нём может устареть. Правим непосредственно перед стартом.
    fun retarget(config: String): String = try {
        val root = org.json.JSONObject(config)
        var changed = false
        val api = root.optJSONObject("experimental")?.optJSONObject("clash_api")
        if (api != null) {
            api.put("external_controller", "127.0.0.1:$port")
            if (secret.isNotEmpty()) api.put("secret", secret)
            changed = true
        }
        // И служебный вход тоже.
        //
        // Здесь была ровно та же дыра, из-за которой мы городили выбор
        // свободного порта: LocalProbe честно занимал свободный номер, а в
        // сохранённом конфиге оставался прежний — и ядро падало насмерть с
        // «listen tcp 127.0.0.1:2081: bind: address already in use», унося с
        // собой весь туннель. За один день это уронило три подъёма.
        // Выход на щит: у него порт тоже плавающий.
        val outs = root.optJSONArray("outbounds")
        if (outs != null) for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == "dpi-socks") {
                o.put("server_port", ByeDpi.port); changed = true
            }
            // Ход к Xray: его SOCKS теперь на случайном порту и с паролем
            // (см. XrayEngine.claim) — подставляем те, что выпали сейчас.
            if (o.optString("tag") == "proxy" && o.optString("type") == "socks" &&
                o.optString("server") == "127.0.0.1") {
                o.put("server_port", XrayEngine.port)
                if (XrayEngine.user.isNotEmpty())
                    o.put("username", XrayEngine.user).put("password", XrayEngine.pass)
                changed = true
            }
        }
        val ins = root.optJSONArray("inbounds")
        if (ins != null) for (i in 0 until ins.length()) {
            val o = ins.optJSONObject(i) ?: continue
            if (o.optString("tag") == "probe-in") {
                o.put("listen_port", LocalProbe.port)
                // Служебный вход ведёт ПРЯМО В ТУННЕЛЬ. Без пароля им мог
                // пользоваться кто угодно на телефоне — в том числе
                // приложения, пущенные мимо туннеля, — и узнать адрес нашего
                // сервера. Пароль новый на каждый подъём.
                if (LocalProbe.user.isNotEmpty())
                    o.put("users", org.json.JSONArray().put(org.json.JSONObject()
                        .put("username", LocalProbe.user).put("password", LocalProbe.pass)))
                changed = true
            }
        }
        if (changed) root.toString() else config
    } catch (_: Throwable) {
        config
    }
}
