package com.zen.blaster.core

import android.content.Context
import com.zen.blaster.model.Node

// ============================================================================
//  Конфиг ноды и выбор следующей — БЕЗ экрана.
//
//  Собирать конфиг умело только приложение, поэтому, стоило ему уйти в фон,
//  всё восстановление оставалось на службе — а служба умела только повторять
//  ТУ ЖЕ ноду. Журнал 05–06.11, Xiaomi, Tele2: вход NoV лёг целиком (с
//  компьютера порты молчат тоже), и сторож шесть часов подряд, 3957 раз,
//  поднимал «🇺🇸 Каскад NoV-US», хотя в списке были живые ноды на других
//  входах. Теперь и служба, и приложение собирают конфиг здесь, и служба
//  сама уходит на следующую ноду, когда своя не встаёт.
// ============================================================================
object NodeConfig {

    fun traitKey(n: Node) = "${n.addr}:${n.port}"
    fun keyOf(n: Node) = "${n.addr}:${n.port}|${n.name}"

    // Нода, которую надо поднимать через Xray: транспорт только его, или она
    // уже вставала только через него, или так вставали две её соседки по
    // входу (у Триллера десятки каскадов на одном адресе с одной настройкой).
    fun xrayNeighbours(prefs: Prefs, n: Node): Int =
        prefs.xrayNodes().count { it != traitKey(n) && it.substringBeforeLast(':') == n.addr }

    fun prefersXray(ctx: Context, prefs: Prefs, n: Node): Boolean {
        if (!XrayEngine.available(ctx)) return false
        if (XrayEngine.handles(n)) return true
        return XrayEngine.bothPossible(n) &&
            (traitKey(n) in prefs.xrayNodes() || xrayNeighbours(prefs, n) >= 2)
    }

    // Разгон Hysteria2: сколько заявлять ядру. См. подробности в истории —
    // вслепую заявленный Brutal на сотовой связи топит сам себя.
    fun brutal(ctx: Context, prefs: Prefs): Pair<Int, Int> {
        if (!prefs.hyBoost) return 0 to 0
        val want = prefs.hyDownMbps to prefs.hyUpMbps
        if (NetInfo.slowMobile(ctx)) return 0 to 0
        val kbps = NetInfo.downKbps(ctx)
        val mobile = NetInfo.snapshot(ctx).mobile
        // Система скорость не знает — на сотовой связи без замера BBR.
        if (kbps <= 0) return if (mobile) 0 to 0 else want
        // Потолок с запасом вниз: переоценить канал в Brutal опаснее, чем
        // недооценить; оценка сотовой сети у Android почти всегда завышена.
        val cap = (kbps * (if (mobile) 3 else 4) / 5 / 1000).coerceAtLeast(1)
        return if (want.first <= cap) want
               else cap to (want.second * cap / want.first).coerceAtLeast(1)
    }

    // Конфиг ноды по текущим настройкам: (sing-box, Xray или "").
    // То же, что собирает плитка и обычное подключение без откатов.
    fun build(ctx: Context, prefs: Prefs, n: Node): Pair<String, String> {
        val dpiOn = prefs.dpiEnabled
        val viaXray = prefersXray(ctx, prefs, n)
        val fp = if (prefs.armor) ConfigBuilder.pickFingerprint() else ""
        val front = when {
            n.whitelistNative() -> ""
            prefs.whitelistMode -> prefs.whitelistDomain.trim()
            else -> ""
        }
        val xray = if (viaXray)
            XrayEngine.buildConfig(n, prefs.armor, fp,
                viaDpi = prefs.chainVpnViaDpi && dpiOn && !n.wl, frontDomain = front,
                bait = AwgBait.fromProfiles(prefs.awgProfiles())).toString()
        else ""
        val (hyDown, hyUp) = brutal(ctx, prefs)
        val box = ConfigBuilder.build(
            n, prefs.geoRouting, prefs.ipv6, true, dpiOn, prefs.geoDir(), prefs.armor, viaXray,
            prefs.proxyMode, front,
            hyDown = hyDown, hyUp = hyUp,
            slowLink = NetInfo.slowMobile(ctx),
            bypassApps = if (prefs.geoRouting) prefs.splitApps() else emptySet(),
            lanProxy = prefs.lanProxy, lanUser = prefs.lanUser, lanPass = prefs.lanPass,
            dohUrl = prefs.dnsUrl(), directDohUrl = prefs.preDnsUrl(),
            chainViaDpi = prefs.chainVpnViaDpi && dpiOn
        ).toString()
        return box to xray
    }

    // Следующая нода для службы, когда своя не встаёт.
    //
    //  Тот же порядок, что у каскада в приложении: поднималась на ЭТОМ
    //  операторе — свежие первыми, жила недавно, свежий пинг; три провала на
    //  этом операторе — в конец. Плюс одно правило службы: если своя нода
    //  получала отказ (сброс, пароль), её вход, скорее всего, лёг целиком —
    //  ноды на том же адресе идут после всех остальных.
    fun next(ctx: Context, prefs: Prefs, failed: Set<String>, deadEntry: String?): Node? =
        ranked(ctx, prefs, failed, deadEntry).firstOrNull()

    // Быстрая проверка кандидата мимо туннеля: имя разрешается, у TCP-ноды
    // порт отвечает. На эмуляторе 06.11 сторож перебрал три мёртвые ноды
    // («lookup fr.example.com: empty result») по 30 с каждая, прежде чем
    // дошёл до живой. UDP-порт QUIC-ноды снаружи не проверить — ей хватает
    // того, что адрес нашёлся.
    fun reachable(n: Node, timeoutMs: Int = 2000): Boolean {
        val ip = Resolver.resolve(n.addr) ?: return false
        if (n.quic()) return true
        return try {
            (Resolver.physical()?.socketFactory?.createSocket() ?: java.net.Socket()).use {
                it.connect(java.net.InetSocketAddress(ip, n.port), timeoutMs)
            }
            true
        } catch (_: Exception) { false }
    }

    fun ranked(ctx: Context, prefs: Prefs, failed: Set<String>, deadEntry: String?): List<Node> {
        val carrier = NetInfo.carrierKey(ctx)
        val wins = prefs.carrierWins(carrier)
        val losses = prefs.carrierLosses(carrier)
        val pdp = PdpGuard.lastVerdict.isNotEmpty()
        val now = System.currentTimeMillis()
        val cand = prefs.cachedNodes().filter { n ->
            n.supported() && traitKey(n) !in failed &&
                // Выпавшая из выдачи и ни разу не отвечавшая — не кандидат.
                (!n.gone || n.ping in 1..899 || n.lastOkAt > 0L) &&
                // В режиме белых списков — только аварийные (как у каскада).
                (!prefs.whitelistMode || n.wl)
        }
        return cand.sortedWith(
            compareBy<Node> { if (deadEntry != null && it.addr == deadEntry) 1 else 0 }
                .thenBy { if (pdp && it.quic()) 1 else 0 }
                .thenBy { if ((losses[traitKey(it)] ?: 0) >= 3) 1 else 0 }
                .thenByDescending { wins[traitKey(it)] ?: 0L }
                .thenByDescending { if (now - it.lastOkAt < 24 * 3600_000L) it.lastOkAt else 0L }
                .thenBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }
        )
    }
}
