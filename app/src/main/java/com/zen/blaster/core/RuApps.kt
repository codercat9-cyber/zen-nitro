package com.zen.blaster.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

// ============================================================================
//  Кто на телефоне «российский».
//
//  Точного списка не существует и существовать не может — приложения не носят
//  флага страны. Зато почти все российские сборки лежат в пространстве имён
//  `ru.*`: ru.sberbankmobile, ru.ozon.app.android, ru.mts.mymts и так далее.
//  Этого признака хватает, чтобы ОДНИМ нажатием набрать разумный список,
//  а не отмечать полсотни приложений вручную.
//
//  Признак не идеален: часть наших живёт в `com.*` (VK, Т-Банк, Wildberries),
//  а какое-нибудь `ru.` может оказаться у иностранца. Поэтому:
//    • известные `com.*` дописаны отдельным списком ниже;
//    • итог показывается человеку, и он снимает или добавляет что угодно.
//  Автоматика здесь предлагает, а не решает.
// ============================================================================
object RuApps {

    data class Entry(val pkg: String, val label: String, val system: Boolean, val ru: Boolean)

    // Российские приложения, которые НЕ лежат в ru.* — по префиксу их не поймать.
    // Список заведомо неполный, это не беда: он только помогает первому нажатию.
    private val KNOWN = setOf(
        "com.vkontakte.android",          // ВКонтакте
        "com.vk.im",                      // VK Мессенджер
        "com.vk.calls",                   // VK Звонки
        "com.idamob.tinkoff.android",     // Т-Банк
        "com.wildberries.ru",             // Wildberries
        "com.wb.wbx",                     // Wildberries (новая сборка)
        "com.yandex.browser",             // Яндекс Браузер
        "com.yandex.zen",                 // Дзен
        "com.yandex.mail",                // Яндекс Почта
        "com.yandex.music",               // Яндекс Музыка
        "com.yandex.searchapp",           // Яндекс с Алисой
        "com.sberbank.mobile",            // Сбербанк (старый пакет)
        "com.ozon.app.android",           // Ozon (альтернативный пакет)
        "com.aliexpress.aer",             // AliExpress Россия
        "com.gosuslugi.pgu",              // Госуслуги
        "com.mts.money",                  // МТС Деньги
        "com.megafon.mlk",                // МегаФон
        "com.rutube.app",                 // Rutube
        "com.dodopizza.app",              // Додо Пицца
        "com.pyaterochka.app",            // Пятёрочка
        "com.magnit.express",             // Магнит
        "com.perekrestok.app",            // Перекрёсток
    )

    // Всё установленное, что человек может отправить мимо туннеля.
    //
    // Свой пакет из выдачи убран намеренно: вывести из туннеля сам клиент —
    // значит отправить мимо него подписки и проверку нод. Это не «обход»,
    // это дыра, и предлагать её в списке не нужно.
    fun installed(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val self = ctx.packageName
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Throwable) { emptyList<ApplicationInfo>() }

        return apps.asSequence()
            .filter { it.packageName != self }
            // Системные без интерфейса запуска отбрасываем: их сотни, и в
            // списке они только мешают искать нужное.
            .filter { app ->
                val user = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                user || pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .map { app ->
                Entry(
                    pkg = app.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }
                        .getOrDefault(app.packageName),
                    system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    ru = isRu(app.packageName)
                )
            }
            // Наши — наверх, дальше по алфавиту: искать глазами проще.
            .sortedWith(compareByDescending<Entry> { it.ru }.thenBy { it.label.lowercase() })
            .toList()
    }

    fun isRu(pkg: String): Boolean =
        pkg.startsWith("ru.") || pkg in KNOWN

    // Что предложить при первом нажатии «выбрать все российские».
    fun suggest(ctx: Context): Set<String> =
        installed(ctx).filter { it.ru }.map { it.pkg }.toSet()
}
