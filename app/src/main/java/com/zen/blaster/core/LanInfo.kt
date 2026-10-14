package com.zen.blaster.core

import java.net.Inet4Address
import java.net.NetworkInterface

// ============================================================================
//  Под каким адресом телефон виден компьютеру.
//
//  Человеку надо просто вписать адрес в настройки прокси на ПК, а какой из
//  интерфейсов сейчас раздаёт — знать он не обязан. Поэтому показываем все
//  подходящие и подписываем, что есть что.
// ============================================================================
object LanInfo {

    data class Addr(val ip: String, val iface: String, val kind: String)

    // Интерфейс TUN самого туннеля в список не попадает: адрес 172.19.0.1
    // принадлежит нам самим, компьютеру он бесполезен.
    private const val TUN_PREFIX = "172.19.0."

    fun addresses(): List<Addr> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { Addr(it.hostAddress ?: "", ni.name, kindOf(ni.name)) }
            }
            .filter { it.ip.isNotEmpty() && !it.ip.startsWith(TUN_PREFIX) }
            // Раздачу — наверх: именно её обычно и ищут.
            .sortedBy { if (it.kind.startsWith("раздача")) 0 else 1 }
    } catch (_: Throwable) { emptyList() }

    private fun kindOf(name: String): String = when {
        name.startsWith("rndis") || name.startsWith("usb") -> "раздача по USB"
        name.startsWith("ap") || name.startsWith("swlan")  -> "раздача по Wi-Fi"
        name.startsWith("wlan")  -> "Wi-Fi"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "мобильная сеть"
        name.startsWith("eth")   -> "кабель"
        else -> name
    }
}
