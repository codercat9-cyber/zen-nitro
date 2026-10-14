package com.zen.blaster.core

import org.json.JSONObject

// ============================================================================
//  Защита от «выключения PDP» — когда сессию гасит сам оператор.
//
//  Что это. У мобильного интернета есть сеанс передачи данных (PDP/PDN).
//  Оператор вправе снести его в любой момент, и тогда рвётся ВСЁ разом:
//  туннель, мессенджеры, браузер. От обычного провала связи это отличается
//  почерком — обрывы приходят пачками, на мобильной сети, при живом сигнале,
//  и часто вскоре после подъёма туннеля.
//
//  Чего мы НЕ можем. Заставить оператора не гасить сессию нельзя. Опознать
//  причину с уверенностью — тоже: снаружи это неотличимо от глухой соты.
//  Поэтому здесь не «защита от PDP-шатдауна», а сокращение поводов и почерка:
//
//    • разброс пауз между переподключениями (ровный ритм — сам по себе улика);
//    • свежий отпечаток TLS на каждую попытку, а не один и тот же навсегда;
//    • честный разбор в журнале: пачка обрывов на мобильной сети — повод
//      уйти с QUIC на TCP, а не менять сервер по кругу.
//
//  Мера с наибольшим эффектом — уход с UDP/QUIC на TCP: Hysteria2 и TUIC
//  дают долгий равномерный UDP-поток, который выделяется в статистике соты
//  сильнее всего.
// ============================================================================
object PdpGuard {

    // Метки времени обрывов за последний час. Больше не нужно: почерк
    // читается в пределах минут.
    private val drops = ArrayDeque<Long>()
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val SUSPECT_AT = 2

    @Volatile var lastVerdict: String = ""; private set

    @Synchronized
    fun onDrop(onMobile: Boolean, now: Long): Boolean {
        if (!onMobile) { drops.clear(); lastVerdict = ""; return false }
        drops.addLast(now)
        while (drops.isNotEmpty() && now - drops.first() > WINDOW_MS) drops.removeFirst()
        val suspect = drops.size >= SUSPECT_AT
        lastVerdict = if (suspect)
            "обрывов на мобильной сети: ${drops.size} за 10 минут — похоже, сессию гасит оператор"
        else ""
        return suspect
    }

    @Synchronized
    fun reset() { drops.clear(); lastVerdict = "" }

    // Что посоветовать при подтверждённом почерке.
    fun advice(nodeIsQuic: Boolean): String =
        if (nodeIsQuic)
            "Нода работает поверх UDP (QUIC). Такой поток виден в соте лучше всего — " +
            "смените ноду на TCP: VLESS-Reality, Vision или WS."
        else
            "Нода уже на TCP. Попробуйте другой сервер или другой порт: похоже, " +
            "не нравится именно это направление."

    // Отпечаток TLS в готовом конфиге — приводим к отпечатку устройства.
    //
    // Раньше здесь ставился СВЕЖИЙ случайный отпечаток на каждую попытку:
    // считалось, что одинаковое приветствие при возвратах — примета. С 3.4
    // логика обратная (см. ConfigBuilder.deviceFp): примета как раз смена
    // браузера у одного абонента. Функция осталась, чтобы старый конфиг,
    // собранный ещё со случайным отпечатком, при возврате получил постоянный.
    fun freshenFingerprint(config: String): String = try {
        val root = JSONObject(config)
        val outs = root.optJSONArray("outbounds") ?: return config
        var touched = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val utls = o.optJSONObject("tls")?.optJSONObject("utls") ?: continue
            utls.put("fingerprint", ConfigBuilder.pickFingerprint())
            touched = true
        }
        if (touched) root.toString() else config
    } catch (_: Throwable) { config }
}
