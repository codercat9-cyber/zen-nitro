package com.zen.blaster.core

import android.content.Context

// ============================================================================
//  Ядра — сами, как облачное обновление.
//
//  Решение 06.11.2026: свежие Xray и AmneziaWG собирает и выкладывает на
//  сервер обновлений программа на ПК (tools/zen-cores), а Zen у людей ставит
//  их сам, тихо — без «Доступно обновление ядер → Настройки → Поставить».
//
//  Подстраховка та же, что у ручной установки: модуль ставится только с
//  нашей подписью и сверкой sha256; три провала подряд при живом встроенном
//  ядре — модуль снимается сам (CoreModules.report). Снятую версию сами
//  больше не ставим — ни после автоотката, ни после «ВЕРНУТЬ ВСТРОЕННОЕ»
//  руками: иначе карусель «поставил — снял — поставил». Следующая, более
//  свежая версия — ставится.
//
//  Трафик: AmneziaWG весит ~3 МБ — ставим на любой сети; Xray ~31 МБ —
//  только на безлимитной (Wi-Fi). На мобильной остаётся надпись с кнопкой,
//  а с первым Wi-Fi он встанет сам.
// ============================================================================
object CoreAuto {

    private val lock = Any()

    fun builtinXray(ctx: Context): String = try {
        val bin = java.io.File(ctx.applicationInfo.nativeLibraryDir, "libxrayexec.so")
        val out = ProcessBuilder(bin.absolutePath, "version").redirectErrorStream(true)
            .start().inputStream.bufferedReader().readText()
        Regex("Xray ([0-9.]+)").find(out)?.groupValues?.get(1) ?: "?"
    } catch (_: Throwable) { "?" }

    // Что из каталога новее стоящего сейчас (модуля или встроенного) — по
    // одному, самому свежему, на каждое ядро.
    fun offers(ctx: Context, cat: CoreModules.Catalog, builtinXray: String): List<CoreModules.Module> {
        val cm = CoreModules
        val now = mapOf(
            cm.XRAY to (cm.active(ctx, cm.XRAY)?.version ?: builtinXray),
            cm.AWG to (cm.active(ctx, cm.AWG)?.version ?: AmneziaCore.BUILD),
            cm.AWG_LEGACY to (cm.active(ctx, cm.AWG_LEGACY)?.version ?: AmneziaCore.BUILD_LEGACY))
        return cat.modules.filter { m -> cm.newer(m.version, now[m.slot] ?: "") }
            .groupBy { it.slot }
            .mapNotNull { (_, l) -> l.maxWithOrNull { a, b -> if (cm.newer(a.version, b.version)) 1 else -1 } }
    }

    fun offerText(l: List<CoreModules.Module>) = l.joinToString(", ") { m ->
        when (m.slot) {
            CoreModules.XRAY -> "Xray"
            CoreModules.AWG -> "Amnezia"
            else -> "Amnezia 1.x"
        } + " " + m.version
    }

    private fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.isActiveNetworkMetered.not()
    } catch (_: Throwable) { false }

    // Проверить сервер и поставить, что можно. Не чаще раза в три часа (кроме
    // force). Возвращает то, что осталось только предложить (надпись), или
    // null, если проверка не состоялась.
    fun run(ctx: Context, force: Boolean = false, onLog: (String) -> Unit = {}): List<CoreModules.Module>? =
        synchronized(lock) {
            val prefs = Prefs(ctx)
            if (!force && System.currentTimeMillis() - prefs.lastCoreCheck < 3 * 3600_000L) return null
            val cat = CoreModules.fetch(ctx) ?: return null
            prefs.lastCoreCheck = System.currentTimeMillis()
            // ОТКАТ С СЕРВЕРА доходит до людей так же сам, как и обновление.
            // На ПК нажали «Откатить» — в подписанном манифесте нашего сервера
            // больше нет версии, что стоит у человека. Снимаем её (и сами не
            // поставим снова); дальше ниже встанет то, что сервер отдаёт
            // сейчас, если оно новее встроенного. Без этого откат на сервере
            // никого бы не откатил: старое «новее стоящего» не предлагается.
            val ours = cat.modules.filter { it.source == CoreModules.DEFAULT_SOURCE }
            for (slot in listOf(CoreModules.XRAY, CoreModules.AWG, CoreModules.AWG_LEGACY)) {
                val a = CoreModules.active(ctx, slot) ?: continue
                val served = ours.filter { it.slot == slot }
                if (served.isNotEmpty() && served.none { it.version == a.version }) {
                    CoreModules.revert(ctx, slot, "снят на сервере (откат ${a.version})")
                    onLog("↩️ Ядро ${a.version} отозвано с сервера — вернулся на проверенное")
                }
            }
            val wifi = unmetered(ctx)
            val left = mutableListOf<CoreModules.Module>()
            for (m in offers(ctx, cat, builtinXray(ctx))) {
                if (prefs.rejectedCore(m.slot) == m.version) {
                    // Эту версию уже снимали — сами не ставим, только предлагаем.
                    left += m; continue
                }
                if (m.slot == CoreModules.XRAY && !wifi) { left += m; continue }
                val err = CoreModules.install(ctx, cat, m)
                if (err == null) {
                    onLog("🧩 Ядро обновилось само: ${offerText(listOf(m))} — заработает со следующего подключения")
                    Blackbox.note("🧩 ядро обновлено само (облако): ${offerText(listOf(m))}")
                } else {
                    left += m
                    Blackbox.note("⚠️ ${offerText(listOf(m))} не поставилось само: $err")
                }
            }
            prefs.coreOffer = offerText(left)
            left
        }
}
