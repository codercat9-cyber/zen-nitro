package com.zen.blaster.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Разбор НАСТОЯЩИХ подписок, скачанных заранее (ZEN_SUBS_DIR или app/build/real-subs, *.links —
// уже раскодированные ссылки). В репозитории их нет: в ссылках ключи людей.
// Нет каталога — тест молча пропускается. Конфиги ложатся рядом с остальными
// в build/zen-configs/real*, и tools/check-configs.sh проверяет их ядрами.
class RealSubsTest {
    @Test fun parseRealSubscriptions() {
        val dir = (System.getenv("ZEN_SUBS_DIR")?.let { File(it) } ?: File("build/real-subs"))
        assumeTrue(dir != null && dir.isDirectory)
        val out = File("build/zen-configs").apply { mkdirs() }
        val xout = File(out, "xray").apply { mkdirs() }
        for (f in dir!!.listFiles { x -> x.name.endsWith(".links") }!!.sortedBy { it.name }) {
            val nodes = Subscriptions.decode(f.readText())
            println("== ${f.name}: ${nodes.size} нод" +
                (if (Subscriptions.lastStub.isNotEmpty()) " · заглушка: ${Subscriptions.lastStub}" else ""))
            Subscriptions.lastStub = ""
            nodes.forEachIndexed { i, n ->
                val core = if (XrayEngine.handles(n)) "xray" else "sing-box"
                println("   ${n.type} ${n.transport}/${n.security} ${n.addr}:${n.port} sni=${n.sni} fp=${n.fp} " +
                    "mode=${n.mode} extra=${n.extra.length} insecure=${n.insecure} → $core | ${n.name}")
                val tag = "real-${f.nameWithoutExtension}-$i"
                if (core == "xray")
                    File(xout, "$tag.json").writeText(XrayEngine.buildConfig(n, armor = true, fp = "chrome").toString(2))
                else
                    File(out, "$tag.json").writeText(ConfigBuilder.build(n, geoRouting = false, ipv6 = false,
                        vpnEnabled = true, dpiEnabled = false).toString(2))
            }
        }
    }
}
