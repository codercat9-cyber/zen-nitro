package com.zen.blaster.core

import kotlinx.coroutines.flow.MutableStateFlow

// Живая статистика туннеля: скорость, пинг, имя сервера, время сессии.
// Пишет сервис, читают и уведомление в шторке, и главный экран.
object Stats {
    val up = MutableStateFlow(0L)        // байт/с отдача
    val down = MutableStateFlow(0L)      // байт/с приём
    val ping = MutableStateFlow(-1)      // мс, -1 = нет данных. СЫРОЙ замер сторожа
    // То, что показываем человеку: в шторке и на главном экране.
    val shownPing = MutableStateFlow(-1)
    val server = MutableStateFlow("")    // имя активного сервера
    val mode = MutableStateFlow("")      // VPN | DPI | VPN+DPI
    val since = MutableStateFlow(0L)     // время подключения (millis)
    val stage = MutableStateFlow("")     // что делаем прямо сейчас (во время подключения)

    // ГДЕ МЫ ВЫШЛИ НА САМОМ ДЕЛЕ.
    //
    // Название ноды — это то, что написал провайдер, и оно запросто врёт.
    // Каскадные ноды («🇫🇮 Каскад NoV-FI») называют точку ВХОДА, а выйти могут
    // в третьей стране: выбранная финская нода на проверке 27.08.2026 вышла
    // в Румынии. Человек выбирает страну осознанно — значит должен видеть,
    // куда попал, а не догадываться.
    val exitPlace = MutableStateFlow("")   // «Румыния, Бухарест» — пусто, пока не знаем
    val exitIp = MutableStateFlow("")

    // Kill switch держит трафик закрытым: туннеля нет, наружу тоже ничего.
    // Именно поток, а не обычное поле: экран должен перерисоваться в тот же
    // миг, а не когда-нибудь при следующем тике статистики — которого в этом
    // состоянии как раз и не будет, замеры остановлены.
    val blocked = MutableStateFlow(false)

    fun reset() {
        up.value = 0; down.value = 0; ping.value = -1; since.value = 0; stage.value = ""
        shownPing.value = -1; windowBest = -1; windowStart = 0L
    }

    // ПИНГ, КОТОРЫЙ НЕ МОРОСИТ.
    //
    // Сторож связи меряет канал каждые несколько секунд — это ему нужно,
    // чтобы вовремя заметить обрыв. Но показывать каждый его замер человеку
    // было ошибкой: цифра прыгала, а любая осечка превращалась в «—», и в
    // шторке шла морзянка из точек и тире. Так это и описал оператор
    // 1 октября 2026.
    //
    // Теперь показ живёт отдельно: первый удачный замер виден сразу, а
    // дальше раз в полчаса цифра меняется на ЛУЧШИЙ результат за эти
    // полчаса. Осечки показ не трогают вовсе — промах ничего не говорит о
    // скорости канала, только о том, что один запрос не дошёл.
    private const val WINDOW = 30 * 60_000L
    @Volatile private var windowBest = -1
    @Volatile private var windowStart = 0L

    @Synchronized
    fun feedPing(ms: Int) {
        if (ms <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (windowBest <= 0 || ms < windowBest) windowBest = ms
        if (shownPing.value <= 0) {               // первый — показываем сразу
            shownPing.value = ms
            windowStart = now
            return
        }
        if (now - windowStart >= WINDOW) {        // полчаса прошло — обновляем
            shownPing.value = windowBest
            windowBest = -1
            windowStart = now
        }
    }

    // "1,2 МБ/с" / "345 КБ/с" / "12 Б/с"
    fun fmtSpeed(bps: Long): String = when {
        bps >= 1024L * 1024L -> String.format("%.1f МБ/с", bps / 1048576.0)
        bps >= 1024L -> String.format("%.0f КБ/с", bps / 1024.0)
        else -> "$bps Б/с"
    }

    fun fmtDuration(): String {
        if (since.value == 0L) return "00:00"
        val s = (System.currentTimeMillis() - since.value) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }
}
