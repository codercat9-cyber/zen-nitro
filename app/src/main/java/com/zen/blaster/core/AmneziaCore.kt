package com.zen.blaster.core

import android.util.Base64
import com.zen.blaster.model.AwgProfile

// ============================================================================
//  Ядро AmneziaWG. ДВА ядра, по одному на процесс:
//
//   • libwg.so  — amneziawg-go v3.1 (основное). Понимает всё: WireGuard,
//     AWG 1.0, 1.5, 2.0 и AWG 3 (защита заголовков, хвосты, тайминги).
//     Ровно на нём же работает официальный клиент Amnezia 5.0.
//   • libwg1.so — amneziawg-go v1.0.4 (запасное), последняя версия старой
//     ветки AWG 1.x. Нужно на случай старого сервера, который новое ядро
//     почему-либо не уговорит: тогда ZenAwgService сам пробует ещё раз на
//     нём, уже в своём процессе (:awg1).
//
//  Оба собраны патченным Go: таймеры считают время и во сне телефона
//  (CLOCK_BOOTTIME) — иначе после глубокого сна рукопожатие «опаздывает».
//  Рецепт сборки: native/awg/ в корне проекта.
//
//  Живёт в ОТДЕЛЬНОМ процессе (:awg / :awg1) — см. ZenAwgService. Причина:
//  libbox (sing-box) тоже несёт в себе рантайм Go, а два рантайма Go в одном
//  процессе дерутся за обработчики сигналов. По той же причине два ядра AWG
//  нельзя держать в одном процессе.
// ============================================================================
object AmneziaCore {

    // Версии вкомпилированных ядер. Константами, а не через version(): вызов
    // из UI-процесса подгрузил бы libwg.so туда, где уже живёт libbox.
    const val BUILD = "v3.1.20260828"
    const val BUILD_LEGACY = "v1.0.4"

    // Какое ядро грузить в этом процессе. Ставит служба ДО первого вызова.
    @Volatile var legacy = false

    @Volatile private var loaded = false
    @Volatile private var handle: Int = -1

    val running: Boolean get() = handle >= 0

    private fun load(): Boolean {
        if (loaded) return true
        return try { System.loadLibrary(if (legacy) "wg1" else "wg"); loaded = true; true }
        catch (t: Throwable) { android.util.Log.e("ZenAwg", "loadLibrary: ${t.message}"); false }
    }

    // Почему ядро не поднялось — его собственными словами («IpcSet: …»).
    fun lastError(): String = if (!load()) "ядро не загрузилось" else
        try { awgLastError() ?: "" } catch (_: Throwable) { "" }

    fun version(): String = if (!load()) "?" else try { awgVersion() } catch (_: Throwable) { "?" }

    // Поднимает туннель на уже открытом файловом дескрипторе TUN.
    // Возвращает handle >= 0 либо -1.
    fun up(ifName: String, tunFd: Int, uapi: String): Int {
        if (!load()) return -1
        handle = try { awgTurnOn(ifName, tunFd, uapi) } catch (t: Throwable) {
            android.util.Log.e("ZenAwg", "awgTurnOn: ${t.message}"); -1
        }
        return handle
    }

    fun down() {
        if (handle >= 0) { try { awgTurnOff(handle) } catch (_: Throwable) {} }
        handle = -1
    }

    // Сокеты наружу нужно защитить от заворачивания в собственный туннель.
    fun sockets(): List<Int> = if (handle < 0) emptyList() else
        listOfNotNull(
            try { awgGetSocketV4(handle) } catch (_: Throwable) { -1 },
            try { awgGetSocketV6(handle) } catch (_: Throwable) { -1 }
        ).filter { it > 0 }

    // Новый исходный UDP-порт без остановки туннеля (см. awgRebind в
    // native/awg). Вызывающий ОБЯЗАН сразу защитить новые сокеты (protect),
    // иначе пакеты ядра завернутся в собственный туннель. false — ядро
    // старое (без awgRebind) или команду не приняло.
    fun rebind(): Boolean = handle >= 0 && try { awgRebind(handle) == 0 } catch (_: Throwable) { false }

    // Сырой дамп состояния ядра (rx/tx, время хендшейка).
    fun config(): String = if (handle < 0) "" else
        try { awgGetConfig(handle) ?: "" } catch (_: Throwable) { "" }

    // Счётчики трафика из UAPI-дампа.
    fun traffic(): Pair<Long, Long> {
        var rx = 0L; var tx = 0L
        for (line in config().lines()) {
            val i = line.indexOf('='); if (i < 0) continue
            when (line.substring(0, i)) {
                "rx_bytes" -> rx += line.substring(i + 1).toLongOrNull() ?: 0
                "tx_bytes" -> tx += line.substring(i + 1).toLongOrNull() ?: 0
            }
        }
        return rx to tx
    }

    // ------------------------------------------------------------ UAPI-конфиг
    //
    //  Ядро принимает не .conf, а UAPI-текст: ключи в hex, адрес пира уже
    //  разрешённым IP (внутри Go нет системного резолвера, на который можно
    //  положиться), обфускация — отдельными строками.
    //
    //  Каждому ядру — только то, что оно понимает: на незнакомый ключ ядро
    //  отвечает отказом целиком («invalid UAPI device key»), и туннель не
    //  поднимается вовсе.
    fun buildUapi(p: AwgProfile, resolvedHost: String? = null, legacyCore: Boolean = legacy): String = buildString {
        append("private_key=").append(hex(p.privateKey)).append('\n')
        append("listen_port=").append(p.listenPort).append('\n')
        for ((k, v) in p.junk) {
            val line = uapiLine(k, v, legacyCore) ?: continue
            append(line).append('\n')
        }
        append("replace_peers=true\n")
        if (p.peerPublicKey.isNotEmpty())
            append("public_key=").append(hex(p.peerPublicKey)).append('\n')
        if (p.presharedKey.isNotEmpty())
            append("preshared_key=").append(hex(p.presharedKey)).append('\n')
        val host = resolvedHost ?: p.host
        append("endpoint=").append(if (host.contains(':')) "[$host]:${p.port}" else "$host:${p.port}").append('\n')
        val ka = if (!legacyCore && p.keepaliveRange.isNotEmpty()) p.keepaliveRange
                 else if (p.keepalive > 0) p.keepalive.toString() else ""
        if (ka.isNotEmpty()) append("persistent_keepalive_interval=").append(ka).append('\n')
        append("replace_allowed_ips=true\n")
        for (ip in p.allowedIps) append("allowed_ip=").append(ip).append('\n')
    }

    // Ключ .conf → строка UAPI. null — этому ядру не передавать.
    private fun uapiLine(k: String, v: String, legacyCore: Boolean): String? = when (k) {
        "jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4", "i1" -> "$k=$v"
        "i2", "i3", "i4", "i5", "s3", "s4" -> if (legacyCore) null else "$k=$v"
        // Ранние AWG 1.5: в v3 этих ключей больше нет, а это мусор на
        // стороне клиента — сервер их не сверяет.
        "j1", "j2", "j3", "itime" -> if (legacyCore) "$k=$v" else null
        // AWG 3. Ключ защиты заголовков в .conf в base64, ядру нужен hex.
        "headerprotectionkey" -> if (legacyCore) null else "header_protection_key=${hex(v)}"
        "contentpaddingaddition" -> if (legacyCore) null else "content_padding_addition=${v.replace(" ", "")}"
        "rekeyaftertime" -> if (legacyCore) null else "rekey_after_time=${v.replace(" ", "")}"
        "rekeytimeout" -> if (legacyCore) null else "rekey_timeout=${v.replace(" ", "")}"
        "rejectaftertime" -> if (legacyCore) null else "reject_after_time=${v.replace(" ", "")}"
        "keepalivetimeout" -> if (legacyCore) null else "keepalive_timeout=${v.replace(" ", "")}"
        "maxhandshakeattempts" -> if (legacyCore) null else "max_handshake_attempts=${v.replace(" ", "")}"
        // В .conf пишут «on», ядро на Go понимает только true/false: «on» оно
        // отвергло бы, и весь конфиг вместе с ним.
        "randomtrailers" -> if (legacyCore) null else "random_trailers=${bool(v)}"
        "disablecookies" -> if (legacyCore) null else "disable_cookies=${bool(v)}"
        else -> null
    }

    private fun bool(v: String) = when (v.trim().lowercase()) {
        "on", "1", "true", "yes", "y", "enabled" -> "true"
        else -> "false"
    }

    // base64 (как в .conf) → hex (как хочет UAPI).
    private fun hex(b64: String): String = try {
        Base64.decode(b64, Base64.DEFAULT).joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { "" }

    // --------------------------------------------------------------- нативка
    private external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int
    private external fun awgTurnOff(handle: Int)
    private external fun awgRebind(handle: Int): Int
    private external fun awgGetSocketV4(handle: Int): Int
    private external fun awgGetSocketV6(handle: Int): Int
    private external fun awgGetConfig(handle: Int): String?
    private external fun awgVersion(): String
    private external fun awgLastError(): String?
}
