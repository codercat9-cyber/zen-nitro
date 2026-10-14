package com.zen.blaster.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

// ============================================================================
//  Через что телефон сейчас в сети и чей это оператор.
//
//  Нужно чёрному ящику: одна и та же нода на домашнем Wi-Fi и на Tele2 ведёт
//  себя совершенно по-разному, и запись «не поднялась» без этой строки почти
//  бесполезна — непонятно, чинить ноду или обход.
//
//  Разрешений не просим намеренно. Имя оператора и MCC/MNC отдаются без
//  READ_PHONE_STATE, а тип поколения (LTE/5G) — уже с ним; ради одной строчки
//  в журнале выпрашивать доступ к телефонии в антицензурном клиенте нельзя.
//  Поэтому «мобильная сеть», а не «LTE».
// ============================================================================
object NetInfo {

    data class Snapshot(
        val kind: String,        // Wi-Fi | мобильная сеть | кабель | нет сети
        val operator: String,    // «Tele2 (25020)» — пусто на Wi-Fi
        val mobile: Boolean,
        val vpn: Boolean,        // поверх уже есть чей-то туннель
        val roaming: Boolean
    )

    fun snapshot(ctx: Context): Snapshot = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val mobile = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val kind = when {
            caps == null -> "нет сети"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            mobile -> "мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "кабель"
            else -> "сеть неизвестного типа"
        }
        Snapshot(
            kind = kind,
            operator = if (mobile) operatorName(ctx) else "",
            mobile = mobile,
            // TRANSPORT_VPN у активной сети — это либо мы сами, либо чужой
            // клиент. Второе объясняет добрую половину «ничего не работает».
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            roaming = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false
        )
    } catch (_: Throwable) {
        Snapshot("неизвестно", "", false, false, false)
    }

    // Имя оператора вместе с кодом сети: имена операторы пишут как хотят
    // («Tele2», «TELE2 RU», «T2 Mobile»), а MCC/MNC — цифра в цифру.
    //
    // На ДВУХ симках спрашиваем ту, что сейчас раздаёт интернет. Системный
    // TelephonyManager по умолчанию отвечает про симку для звонков: 14.10.2026
    // телефон ходил через МТС, а журнал весь день писал «Tele2» — и разбор
    // «на каком операторе что работает» уходил не туда.
    private fun operatorName(ctx: Context): String = try {
        val base = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dataSub = if (android.os.Build.VERSION.SDK_INT >= 30)
            android.telephony.SubscriptionManager.getActiveDataSubscriptionId()
        else android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
        val tm = if (dataSub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            base.createForSubscriptionId(dataSub) else base
        val name = tm.networkOperatorName.orEmpty().trim()
        val code = tm.networkOperator.orEmpty().trim()
        when {
            name.isNotEmpty() && code.isNotEmpty() -> "$name ($code)"
            name.isNotEmpty() -> name
            code.isNotEmpty() -> code
            else -> "оператор не назвался"
        }
    } catch (_: Throwable) { "" }

    // ------------------------------------------------- насколько канал быстрый
    //
    //  Поколение сети (E / H / H+ / LTE) без READ_PHONE_STATE не спросить, а
    //  просить его в антицензурном клиенте нельзя. Зато система сама держит
    //  оценку пропускной способности канала, и её отдают БЕЗ разрешений с
    //  API 21. Для нашей задачи этого хватает с запасом: EDGE система
    //  оценивает в сотни килобит, HSPA — в единицы мегабит, LTE — в десятки.
    //
    //  Зачем это нужно. Клиент судил о живости туннеля по замеру с таймаутом
    //  в пять секунд, одинаковым для домашнего Wi-Fi и для EDGE в электричке.
    //  На медленном канале пять секунд не хватает никогда, пять промахов
    //  подряд набегали за полминуты — и туннель рвался сам, на ровном месте.
    //  Отсюда четырнадцать записей «сорвалась» за один день живых тестов,
    //  и все до единой — на мобильной сети.
    fun downKbps(ctx: Context): Int = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.linkDownstreamBandwidthKbps ?: 0
    } catch (_: Throwable) { 0 }

    // Мобильный канал заведомо медленного поколения. Порог в два мегабита
    // отделяет E/GPRS/HSPA от нормального HSPA+/LTE; ноль означает «система
    // не знает», и тогда мы не выдумываем.
    fun slowMobile(ctx: Context): Boolean = try {
        val s = snapshot(ctx)
        val k = downKbps(ctx)
        s.mobile && k in 1 until 2000
    } catch (_: Throwable) { false }

    // Во сколько раз растягивать таймауты и пороги на этом канале.
    //  1 — домашний Wi-Fi и хороший LTE, ничего не меняем;
    //  2 — обычная сотовая связь;
    //  4 — заведомо медленный мобильный канал.
    fun patience(ctx: Context): Int = try {
        val s = snapshot(ctx)
        when {
            !s.mobile -> 1
            slowMobile(ctx) -> 4
            downKbps(ctx) in 1 until 10_000 -> 2
            else -> 2          // сотовая связь всегда рванее провода
        }
    } catch (_: Throwable) { 1 }

    // Короткий ключ сети для «памяти удач»: на каком операторе какие ноды
    // поднимались. Мобильная — код сети раздающей симки (25020, 25001…),
    // иначе «wifi» / «other». Wi-Fi-сети между собой не различаем: имя сети
    // без разрешения на геолокацию не узнать, а просить его ради этого нельзя.
    fun carrierKey(ctx: Context): String = try {
        val s = snapshot(ctx)
        when {
            s.mobile -> operatorName(ctx).substringAfterLast('(').substringBefore(')').ifEmpty { "mobile" }
            s.kind == "Wi-Fi" -> "wifi"
            else -> "other"
        }
    } catch (_: Throwable) { "other" }

    // Одна строка для журнала и для интерфейса.
    fun line(ctx: Context): String {
        val s = snapshot(ctx)
        val k = downKbps(ctx)
        return buildString {
            append(s.kind)
            if (s.operator.isNotEmpty()) append(" · ").append(s.operator)
            // Оценка канала: без неё запись «сорвалась на мобильной» не
            // отличает уверенный LTE от EDGE в подвале, а это разные диагнозы.
            if (k > 0) append(" · канал ~")
                .append(if (k >= 1000) "${k / 1000} Мбит/с" else "$k Кбит/с")
            if (slowMobile(ctx)) append(" · МЕДЛЕННЫЙ")
            if (s.roaming) append(" · роуминг")
            if (s.vpn) append(" · поверх уже есть VPN")
        }
    }
}
