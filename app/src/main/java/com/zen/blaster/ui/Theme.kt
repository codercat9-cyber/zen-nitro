package com.zen.blaster.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.zen.blaster.R

// Палитра в стиле Amnezia, но в наших цветах.
val ZenBg      = Color(0xFF000000)   // чистый чёрный, как у Amnezia
val ZenCard    = Color(0xFF131316)   // нижняя карточка-шторка
val ZenDivider = Color(0xFF1F1F23)
val ZenCyan    = Color(0xFF00FFCC)   // покой
val ZenRed     = Color(0xFFFF2A4B)   // подключено
val ZenGray    = Color(0xFF8E8E93)   // вторичный текст

// Заголовок приложения — готика (blackletter).
//
// Взят Pirata One, а не классическая фрактура вроде UnifrakturMaguntia:
// у настоящей фрактуры «Z» читается как «3», а «N» как «R», и название
// превращается в ребус. Pirata сохраняет готический излом, но остаётся
// разборчивым и заметно у́же — как раз то «плотненько», которое нужно
// в одну строку с кнопками.
//
// Готика нравится не всем — поэтому она только первая в списке, а не
// единственная. Шрифт заголовка выбирается тапом по самому заголовку.
val ZenGothic = FontFamily(Font(R.font.pirata_one))
val ZenGothicHeavy = FontFamily(Font(R.font.unifraktur_cook))

// Что предлагается на выбор. Порядок важен: в настройках хранится номер,
// поэтому НОВЫЕ шрифты дописывать только В КОНЕЦ — иначе у людей, которые
// уже что-то выбрали, заголовок молча сменится на чужой.
val ZEN_TITLE_FONTS: List<Pair<String, FontFamily>> = listOf(
    "Готика" to ZenGothic,
    "Готика тяжёлая" to ZenGothicHeavy,
    "Обычный" to FontFamily.SansSerif,
    "С засечками" to FontFamily.Serif,
    "Печатная машинка" to FontFamily.Monospace,
)

fun zenTitleFont(i: Int): FontFamily =
    ZEN_TITLE_FONTS.getOrElse(i) { ZEN_TITLE_FONTS[0] }.second

private val ZenColors = darkColorScheme(
    primary = ZenCyan,
    secondary = ZenRed,
    background = ZenBg,
    surface = ZenCard,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenColors, content = content)
}
