package com.zen.blaster.core

import android.util.Base64
import com.zen.blaster.model.AwgProfile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

// ============================================================================
//  Разбор конфигов Amnezia: файлы .conf и ссылки vpn://.
//
//  vpn:// — это base64url от qCompress-блоба Qt: 4 байта размера (big-endian)
//  плюс обычный zlib. Внутри JSON, где нужный нам .conf лежит строкой в
//  containers[].awg.last_config → config.
// ============================================================================
object AwgParser {

    // ------------------------------------------------------------ .conf (INI)

    fun parseConf(text: String, fallbackName: String = "Amnezia"): AwgProfile? {
        var section = ""
        val iface = LinkedHashMap<String, String>()
        val peer = HashMap<String, String>()
        for (raw in text.lines()) {
            // «#» внутри I1..I5 не комментарий: там бывает что угодно. Режем
            // комментарий, только если строка с него НАЧИНАЕТСЯ.
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) { section = line.trim('[', ']').lowercase(); continue }
            val i = line.indexOf('='); if (i < 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            when (section) {
                "interface" -> iface[k] = v
                "peer" -> peer[k] = v
            }
        }
        val pk = iface["privatekey"] ?: return null
        val addr = split(iface["address"])
        if (addr.isEmpty()) return null

        // Маскировка: ВСЁ из [Interface], что не относится к обычному
        // WireGuard. Раньше брали по списку известных ключей — и параметры
        // AWG 3 молча терялись. Теперь наоборот: выкидываем только известное
        // WireGuard-у, остальное несём ядру (а неизвестное ему он отбросит
        // при сборке UAPI, см. AmneziaCore.buildUapi).
        val junk = LinkedHashMap<String, String>()
        for ((k, v) in iface) if (k !in WG_INTERFACE && v.isNotEmpty()) junk[k] = v

        val ka = peer["persistentkeepalive"].orEmpty()
        return AwgProfile(
            name = fallbackName,
            privateKey = pk,
            addresses = addr,
            dns = split(iface["dns"]),
            mtu = iface["mtu"]?.toIntOrNull() ?: 1280,
            listenPort = iface["listenport"]?.toIntOrNull() ?: 0,
            junk = junk,
            peerPublicKey = peer["publickey"] ?: "",
            presharedKey = peer["presharedkey"] ?: "",
            endpoint = peer["endpoint"] ?: "",
            allowedIps = split(peer["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") },
            // «25-35» — диапазон AWG 3; старому ядру отдаём нижнюю границу.
            keepalive = ka.substringBefore('-').trim().toIntOrNull() ?: 25,
            keepaliveRange = if (ka.contains('-')) ka.replace(" ", "") else "",
            raw = text.trim()
        )
    }

    // Обычные поля WireGuard в [Interface] — всё остальное считаем маскировкой.
    private val WG_INTERFACE = setOf(
        "privatekey", "address", "dns", "mtu", "listenport", "fwmark", "table",
        "preup", "postup", "predown", "postdown", "saveconfig"
    )

    private fun split(s: String?) =
        s?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    // ------------------------------------------------------------ vpn:// ссылка

    fun parseLink(link: String): AwgProfile? = try {
        val payload = link.trim().removePrefix("vpn://")
        val raw = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val json = qUncompress(raw) ?: String(raw)
        fromAmneziaJson(JSONObject(json))
    } catch (_: Throwable) { null }

    // Qt-шный qCompress: 4 байта длины + zlib.
    private fun qUncompress(data: ByteArray): String? = try {
        if (data.size < 5) null else {
            val inf = Inflater()
            inf.setInput(data, 4, data.size - 4)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
            inf.end()
            if (out.size() == 0) null else out.toString("UTF-8")
        }
    } catch (_: Throwable) { null }

    // Достаёт .conf из структуры Amnezia-конфига.
    fun fromAmneziaJson(root: JSONObject): AwgProfile? {
        val name = root.optString("description")
            .ifEmpty { root.optString("hostName") }
            .ifEmpty { "Amnezia" }

        val containers = root.optJSONArray("containers")
        if (containers != null) for (i in 0 until containers.length()) {
            val c = containers.optJSONObject(i) ?: continue
            for (key in listOf("awg", "wireguard")) {
                val box = c.optJSONObject(key) ?: continue
                val lastRaw = box.optString("last_config")
                if (lastRaw.isEmpty()) continue
                val conf = try { JSONObject(lastRaw).optString("config") } catch (_: Throwable) { lastRaw }
                if (conf.isNotEmpty()) parseConf(conf, name)?.let { return it }
            }
        }
        // Иногда ссылка несёт .conf прямо в поле config.
        val plain = root.optString("config")
        if (plain.isNotEmpty()) return parseConf(plain, name)
        return null
    }

    // Единая точка входа: строка из буфера обмена / файла.
    fun parseAny(text: String, name: String = "Amnezia"): AwgProfile? {
        val t = text.trim()
        return when {
            t.startsWith("vpn://") -> parseLink(t)
            t.startsWith("{") -> try { fromAmneziaJson(JSONObject(t)) } catch (_: Throwable) { null }
            t.contains("[Interface]", ignoreCase = true) -> parseConf(t, name)
            else -> null
        }
    }
}
