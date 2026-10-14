package com.zen.blaster.core

import java.net.InetAddress

// ============================================================================
//  ТСПУ ГЛУШИТ «ХРОМОВОЕ» ПРИВЕТСТВИЕ TLS ПО ДОРОГЕ К CLOUDFLARE.
//
//  Свежий Chrome (а за ним uTLS-отпечаток «chrome» и сам Go без uTLS) кладёт
//  в ClientHello постквантовый ключ X25519MLKEM768. Приветствие вырастает до
//  ~1,5 КБ и уходит двумя TCP-сегментами — и к адресам Cloudflare такое
//  соединение ТСПУ замораживает: TCP установлен, приветствие висит на
//  перепосылках, ответа нет.
//
//  Проверено 14.10.2026 с проводного канала в РФ, мимо всех туннелей,
//  VLESS+WS+TLS на настоящие адреса:
//    • Cloudflare 104.16.x / 162.159.x: chrome, randomized, Go-по-умолчанию —
//      глухо; firefox, edge, safari, ios, android — Cloudflare отвечает;
//    • Google, Hetzner, Яндекс: chrome проходит;
//    • Hetzner с SNI www.cloudflare.com — chrome проходит: ловят по
//      АДРЕСУ назначения, а не по имени.
//
//  Значит, Reality на своих VPS это не трогает, а ноды за CDN Cloudflare
//  (WS/gRPC/XHTTP через CDN, белосписочные с подменой имени) с отпечатком
//  chrome не поднимутся никогда. Для таких нод берём firefox: его
//  приветствие без постквантового ключа и проходит.
// ============================================================================
object Cloudflare {

    // Отпечаток для нод за Cloudflare.
    const val SAFE_FP = "firefox"

    // Отпечатки без постквантового ключа (проверены выше). Всё остальное —
    // chrome, randomized, random (может выпасть chrome), пустой (голый Go)
    // — под подозрением.
    private val SAFE = setOf("firefox", "edge", "safari", "ios", "android")

    fun risky(fp: String?): Boolean = fp.isNullOrEmpty() || fp.lowercase() !in SAFE

    // Опубликованные сети Cloudflare (cloudflare.com/ips).
    private val V4 = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    ).map { parse(it) }
    private val V6 = listOf(
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
        "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
    ).map { parse(it) }

    private class Net(val bytes: ByteArray, val bits: Int)

    private fun parse(cidr: String): Net {
        val (a, b) = cidr.split('/')
        return Net(InetAddress.getByName(a).address, b.toInt())
    }

    // Только для адреса-литерала: имя здесь не разрешаем.
    fun owns(ip: String): Boolean {
        if (!Resolver.isIpLiteral(ip)) return false
        val addr = try { InetAddress.getByName(ip.substringBefore('%')).address } catch (_: Exception) { return false }
        val nets = if (addr.size == 4) V4 else V6
        return nets.any { inNet(addr, it) }
    }

    private fun inNet(addr: ByteArray, n: Net): Boolean {
        if (addr.size != n.bytes.size) return false
        var left = n.bits
        var i = 0
        while (left > 0) {
            val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((addr[i].toInt() and mask) != (n.bytes[i].toInt() and mask)) return false
            left -= 8; i++
        }
        return true
    }
}
