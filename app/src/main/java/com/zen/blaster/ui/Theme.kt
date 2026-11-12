package com.zen.blaster.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.zen.blaster.R

// ЦВЕТОВЫЕ ТЕМЫ — 12.11.2026, по просьбе «своих, мягких и красивых».
//
// Все прежние имена (ZenBg, ZenCyan, ZenRed…) остались — теперь это не
// константы, а окно в текущую палитру. Палитра лежит в состоянии Compose,
// поэтому смена темы перерисовывает всё сразу, без перезапуска.
//
// Тёмные все до одной: светлой теме мешает белый текст, зашитый по экранам,
// а VPN смотрят и ночью. «Мягкие» — значит акценты пастельные, а фон не
// чисто чёрный, а с оттенком: глазу спокойнее, чем неон на чёрном.
//
// accent — покой (кольцо до подключения, кнопки), accent2 — «подключено»
// и заголовки. Порядок — как в выборе; id хранится в настройках.
data class ZenPalette(
    val id: String, val name: String, val about: String,
    val bg: Color, val card: Color, val divider: Color,
    val accent: Color, val accent2: Color, val gray: Color,
)

val ZEN_THEMES = listOf(
    ZenPalette("neon", "Неон", "чёрный, бирюза и алый — как было",
        Color(0xFF000000), Color(0xFF131316), Color(0xFF1F1F23),
        Color(0xFF00FFCC), Color(0xFFFF2A4B), Color(0xFF8E8E93)),
    ZenPalette("forest", "Лес", "тёмная хвоя и мятный",
        Color(0xFF0D1411), Color(0xFF15201B), Color(0xFF223029),
        Color(0xFF8FE3B0), Color(0xFFFF8A7A), Color(0xFF8FA39A)),
    ZenPalette("lavender", "Лаванда", "сумеречный фиолетовый и пудра",
        Color(0xFF121019), Color(0xFF1B1824), Color(0xFF2A2536),
        Color(0xFFB7A6FF), Color(0xFFFF9EC4), Color(0xFF9A93AD)),
    ZenPalette("ocean", "Океан", "глубокий синий и небесный",
        Color(0xFF0A111C), Color(0xFF111B2A), Color(0xFF1E2B3E),
        Color(0xFF7CC8FF), Color(0xFFFF8F8F), Color(0xFF8A9AB0)),
    ZenPalette("peach", "Персик", "тёплый графит и персиковый",
        Color(0xFF15110F), Color(0xFF201915), Color(0xFF30261F),
        Color(0xFFFFB98A), Color(0xFFFF7F8E), Color(0xFFA69A90)),
    ZenPalette("sakura", "Сакура", "вишнёвая ночь и нежно-розовый",
        Color(0xFF150F13), Color(0xFF20171D), Color(0xFF30222B),
        Color(0xFFF5A9C8), Color(0xFFFFD39A), Color(0xFFA8939E)),
    ZenPalette("moss", "Мох", "лесной камень и шалфей",
        Color(0xFF111412), Color(0xFF1A1E1B), Color(0xFF272D29),
        Color(0xFFC2D9A5), Color(0xFFE8A87C), Color(0xFF979F97)),
    ZenPalette("graphite", "Графит", "спокойный серый и морская волна",
        Color(0xFF101113), Color(0xFF191B1F), Color(0xFF26292F),
        Color(0xFFA9D6C9), Color(0xFFE6A4A4), Color(0xFF9097A0)),
)

private val palette = androidx.compose.runtime.mutableStateOf(ZEN_THEMES[0])

val zenPalette: ZenPalette get() = palette.value

fun setZenTheme(id: String) {
    palette.value = ZEN_THEMES.firstOrNull { it.id == id } ?: ZEN_THEMES[0]
}

val ZenBg: Color      get() = palette.value.bg
val ZenCard: Color    get() = palette.value.card     // карточки и шторка
val ZenDivider: Color get() = palette.value.divider
val ZenCyan: Color    get() = palette.value.accent   // покой
val ZenRed: Color     get() = palette.value.accent2  // подключено
val ZenGray: Color    get() = palette.value.gray     // вторичный текст

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

private fun zenColors() = darkColorScheme(
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
    MaterialTheme(colorScheme = zenColors(), content = content)
}
