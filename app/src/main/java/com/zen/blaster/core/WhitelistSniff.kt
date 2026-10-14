package com.zen.blaster.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

// ============================================================================
//  Сегодня белые списки?
//
//  Бывают дни, когда оператор рубит всё, кроме короткого перечня разрешённых
//  сайтов: госуслуги, банки, раздатчики статики VK и Яндекса. Обычный VPN в
//  такой день не поднимается ни на одной ноде, и со стороны это выглядит как
//  «все серверы сдохли». Человек идёт менять ноды по кругу, хотя менять надо
//  не ноду, а способ: пройдёт только то, что ВЫГЛЯДИТ походом на разрешённый
//  домен.
//
//  Отличить этот режим от обычной поломки можно опытом, а не догадкой:
//  постучаться напрямую в разрешённые адреса и в нейтральные заграничные.
//    • открылось и то, и другое      → сеть в порядке, дело в ноде;
//    • не открылось ничего           → сети нет вовсе, вердикта не выносим;
//    • разрешённые да, заграничные нет → это он, режим белых списков.
//
//  Стучимся ТОЛЬКО голым TCP и мимо любых прокси: нам нужно состояние самой
//  сети оператора, а не туннеля.
// ============================================================================
object WhitelistSniff {

    // Что у операторов в разрешённом перечне почти всегда: раздатчики статики
    // (их дёргает любое приложение) и госуслуги.
    private val ALLOWED = listOf(
        "sun9-1.userapi.com" to 443,
        "yastatic.net" to 443,
        "www.gosuslugi.ru" to 443,
    )

    // Нейтральное заграничное. В режиме белых списков сюда не пройти.
    private val NEUTRAL = listOf(
        "www.gstatic.com" to 443,
        "www.cloudflare.com" to 443,
        "1.1.1.1" to 443,
    )

    data class Verdict(
        val whitelist: Boolean,     // похоже на режим белых списков
        val allowedOk: Int,
        val neutralOk: Int,
        val noNetwork: Boolean      // не достучались вообще никуда
    ) {
        // Сеть открывает и разрешённое, и заграничное — дело в ноде.
        val open: Boolean get() = allowedOk >= 2 && neutralOk >= 2
        // Всё остальное — сеть работает рывками. Раньше такое писалось как
        // «пропускает всё, дело в ноде» (14.10.2026: «разрешённые 0/3,
        // заграничные 1/3 → пропускает всё»), и человек шёл менять ноды,
        // хотя ноды были ни при чём.
        val spotty: Boolean get() = !open && !whitelist && !noNetwork

        fun verdictText(): String = when {
            noNetwork -> "сети нет вовсе"
            whitelist -> "РЕЖИМ БЕЛЫХ СПИСКОВ"
            open -> "сеть пропускает всё, дело в ноде"
            else -> "сеть работает рывками — отвечает через раз"
        }
    }

    // Стучимся по НАСТОЯЩЕЙ сети оператора, а не «как получится». Обычный
    // Socket() идёт по сети по умолчанию, а пока наш туннель держит защиту,
    // это сам туннель — мёртвый. 14.10.2026 при живом канале Tele2 в 7 Мбит/с
    // опрос раз за разом выдавал «сети нет вовсе»: он стучался в собственный
    // упавший туннель. Имя разрешаем тоже через сеть оператора.
    private fun reach(host: String, port: Int, timeoutMs: Int = 3500): Boolean = try {
        val net = Resolver.physical()
        val addr = if (net != null) net.getAllByName(host).first() else java.net.InetAddress.getByName(host)
        val sock = net?.socketFactory?.createSocket() ?: Socket()
        sock.use { s ->
            s.connect(InetSocketAddress(addr, port), timeoutMs)
            true
        }
    } catch (_: Throwable) { false }

    suspend fun probe(): Verdict = withContext(Dispatchers.IO) {
        coroutineScope {
            val a = ALLOWED.map { (h, p) -> async { reach(h, p) } }
            val n = NEUTRAL.map { (h, p) -> async { reach(h, p) } }
            val allowedOk = a.awaitAll().count { it }
            val neutralOk = n.awaitAll().count { it }
            Verdict(
                // Порог намеренно строгий: один открывшийся разрешённый адрес
                // при одном закрытом заграничном — это ещё не режим, это могла
                // быть просто осечка. Врать про белые списки нельзя: человек
                // уйдёт чинить не то.
                whitelist = allowedOk >= 2 && neutralOk == 0,
                allowedOk = allowedOk,
                neutralOk = neutralOk,
                noNetwork = allowedOk == 0 && neutralOk == 0
            )
        }
    }

    // Что сказать человеку. Возвращает строки для журнала — пустой список,
    // если сказать нечего и лезть с советом не надо.
    fun explain(v: Verdict, wlNodes: Int, frontable: Int): List<String> = when {
        v.noNetwork -> listOf("… сети нет вовсе — вердикт по белым спискам не выношу")
        v.spotty -> listOf(
            "📶 Сеть работает рывками: разрешённые ${v.allowedOk}/3, заграничные ${v.neutralOk}/3.",
            "   Это не нода и не белые списки — связь у оператора сама проседает.",
            "   Подожду и попробую снова; менять серверы по кругу сейчас бесполезно.")
        !v.whitelist -> emptyList()
        else -> buildList {
            add("🧾 Похоже, сегодня режим БЕЛЫХ СПИСКОВ.")
            add("   Разрешённые адреса открываются (${v.allowedOk} из ${ALLOWED.size}), " +
                "заграничные — ни один. Обычный VPN в такой день глушат на любой ноде.")
            add("   Менять серверы по кругу бесполезно — нужен обход белых списков:")
            if (wlNodes == 0)
                add("   ⚠️ Аварийных нод у вас нет. Настройки → «Аварийный арсенал», " +
                    "добавьте ноду с прикрытием под разрешённый домен.")
            else {
                add("   ✔ В аварийном арсенале ${wlNodes} нод(ы), с прикрытием — $frontable.")
                if (frontable == 0)
                    add("   ⚠️ Ни одной пригодной: у Reality и голого TCP имя подменить нельзя. " +
                        "Нужна WS или XHTTP через CDN.")
                else
                    add("   Выберите ноду из группы «⚪ Белый список (аварийные)».")
            }
        }
    }
}
