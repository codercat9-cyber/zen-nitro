package com.zen.blaster.core

// ============================================================================
//  «Сервер представляется не тем именем» — разбор строки ядра.
//
//  x509: certificate is valid for *.avito.ru, avito.ru, not eh.vk.ru
//                                 ^^^^^^^^^^ real           ^^^^^^^^ want
//
//  Отдельно и без Android — чтобы проверять тестом на настоящих строках из
//  журналов. Раньше «want» искали по «Post "https://», а в журнале 1.14 его
//  нет: substringAfter отдавал всю строку, и в журнале выходило «подписка
//  ведёт на ERROR[0001] …» (Realme, 31.10.2026).
// ============================================================================
object CertMismatch {

    class Hit(val real: String, val want: String?)

    // Ошибка сертификата DNS-резолвера — не беда ноды (ControlD 14.10.2026).
    // «process DNS packet» — это DNS, который ядро везёт ЧЕРЕЗ ноду: там
    // ошибка именно ноды, поэтому его не отбрасываем.
    fun isResolver(line: String): Boolean =
        line.contains("dns:") || line.contains("exchange failed") || line.contains("process packet")

    fun parse(line: String): Hit? {
        val i = line.indexOf("certificate is valid for ")
        if (i < 0) return null
        val tail = line.substring(i + "certificate is valid for ".length)
        val real = tail.substringBefore(',').trim().trimEnd('.', ' ').take(60)
        if (real.isEmpty()) return null
        val want = Regex("""not ([^\s,"]+)""").find(tail)?.groupValues?.get(1)?.trimEnd('.')
        return Hit(real, want)
    }
}
