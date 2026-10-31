package com.zen.blaster.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
//  ЧЁРНЫЙ ЯЩИК: журнал отложенных ошибок.
//
//  Зачем. Вкладка LOGS живёт в памяти: закрыл приложение — и всё, что оно
//  видело, исчезло. А разбираться приходится потом и по памяти человека:
//  «вчера в метро ничего не поднималось», «на Tele2 отваливается». По такому
//  описанию чинить нечего.
//
//  Здесь то же самое, но на диске и по делу: каждая попытка подключения —
//  отдельная запись с обстановкой (сеть, оператор, нода, ядро, режимы), с
//  ходом событий и с исходом. Плюс счётчик по нодам: сколько раз пробовали,
//  сколько раз поднялась, чем кончилось в последний раз.
//
//  Три вещи, ради которых всё и затевалось:
//
//   1. Запись идёт СРАЗУ на диск, построчно. Систему не спрашивают, когда
//      убить процесс VPN-клиента; всё, что копилось в памяти, пропало бы
//      ровно в тот момент, который и надо разобрать.
//   2. Незакрытый сеанс — тоже улика. Если при следующем запуске видим
//      открытую запись, значит процесс не дожил до исхода: так выглядит и
//      убийство системой, и падение ядра.
//   3. Файл лежит в общей папке приложения, откуда его снимают по кабелю:
//      adb pull /sdcard/Android/data/com.zen.nitro/files/zen-journal.txt
//
//  Чего здесь НЕТ и быть не должно: ключей, паролей, UUID и полных ссылок
//  подписок. Журнал уезжает наружу — в мессенджер, на почту, ко мне на разбор.
//  Ноду опознаём именем и адресом, содержимое конфига не пишем никогда.
// ============================================================================
object Blackbox {

    private const val FILE = "zen-journal.txt"
    private const val MAX_BYTES = 300_000       // сверх этого файл режем

    // Счётчики по нодам и флаг «сеанс не закрыт» переживают перезапуск, но в
    // сам журнал не пишутся: их надо читать и обновлять, а не листать.
    private const val SP = "zen_blackbox"
    private const val K_SCORE = "score"
    private const val K_OPEN = "openSession"
    private const val K_ATTEMPT = "attempt"

    private val lock = Any()
    @Volatile private var file: File? = null
    @Volatile private var app: Context? = null

    // Начало текущего сеанса: от него считаем «+2.4 с» у каждой строки.
    // Относительное время читается несравнимо лучше абсолютного — сразу
    // видно, что ядро молчало двадцать секунд, а не «12:04:11 и 12:04:31».
    @Volatile private var start = 0L
    @Volatile private var open = false

    private val stampFmt get() = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    private fun stamp() = stampFmt.format(Date())

    // ---------------------------------------------------------------- запуск

    // Журнал из ДРУГОГО процесса — служб Amnezia (:awg, :awg1). Пишут в тот
    // же файл, но не трогают общие счётчики и флаг «сеанс открыт»: у каждого
    // процесса своя копия настроек в памяти, и запись из второго процесса
    // затёрла бы то, что тем временем записал основной. По той же причине
    // такой процесс не «закрывает оборванный сеанс» — сеанс-то, скорее всего,
    // идёт прямо сейчас в основном процессе.
    @Volatile private var remote = false

    fun initRemote(ctx: Context) {
        remote = true
        init(ctx)
    }

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            app = ctx.applicationContext
            // Общая папка приложения, а не приватная: приватную с релизной
            // сборки не достать ни adb pull, ни run-as — подписана ключом,
            // отладка выключена. Журнал, который нельзя забрать, бесполезен.
            val dir = runCatching { ctx.getExternalFilesDir(null) }.getOrNull() ?: ctx.filesDir
            file = File(dir, FILE)
            closeOrphanSession()
        }
    }

    fun path(): String = file?.absolutePath ?: "(журнал ещё не открыт)"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // Прошлый запуск не дожил до исхода — так и записываем.
    private fun closeOrphanSession() {
        if (remote) return
        val c = app ?: return
        if (!sp(c).getBoolean(K_OPEN, false)) return
        sp(c).edit().putBoolean(K_OPEN, false).apply()
        write("└── ⚠ сеанс оборвался: процесс не дожил до исхода " +
            "(закрыли приложение, убила система или упало ядро)\n")
    }

    // ------------------------------------------------------------- запись

    // Одно и то же сообщение приходит двумя путями: сервис пишет его сам (он
    // работает и при закрытом приложении), и оно же возвращается через onLog
    // в интерфейс, а оттуда — снова сюда. Ловить это разбором «кто отправитель»
    // бессмысленно: проще не писать строку, которую только что записали.
    // Помним не одну последнюю строку, а несколько: пути расходятся, и копия
    // приходит не сразу следом, а через строку-другую — как «Xray слушает» и
    // «прогрев не прошёл», зажатые между двумя одинаковыми сообщениями.
    private val recent = ArrayDeque<Pair<String, Long>>()

    @Synchronized
    private fun dup(text: String): Boolean {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first().second > 2000) recent.removeFirst()
        if (recent.any { it.first == text }) return true
        recent.addLast(text to now)
        if (recent.size > 12) recent.removeFirst()
        return false
    }

    private fun write(text: String) {
        val f = file ?: return
        try {
            synchronized(lock) {
                f.appendBytes(text.toByteArray())
                if (f.length() > MAX_BYTES) trim(f)
            }
        } catch (_: Throwable) {
            // Журнал не имеет права мешать работе клиента: нет места на
            // карте, вынули карту, отобрали доступ — молча живём дальше.
        }
    }

    // ДОРОС ДО ПРЕДЕЛА — УХОДИТ В АРХИВ, А НЕ В МУСОР.
    //
    // Раньше файл резался до хвоста, и всё старое выбрасывалось. При живом
    // пользовании это два дня истории: 1 октября 2026 оператор попросил
    // «сбои за месяц», а в журнале нашлись только 30.09 и 01.10. Падения
    // 28–29 сентября пришлось доставать из системного архива Android.
    //
    // Теперь заполненный файл переименовывается в zen-journal.1.txt, прежние
    // архивы сдвигаются на номер, самый старый удаляется. Пятнадцать файлов
    // по 300 КБ — около месяца при нынешнем темпе и меньше пяти мегабайт.
    private fun trim(f: File) {
        try {
            val dir = f.parentFile ?: return
            File(dir, archiveName(ARCHIVES)).delete()
            for (i in ARCHIVES - 1 downTo 1)
                File(dir, archiveName(i)).takeIf { it.exists() }
                    ?.renameTo(File(dir, archiveName(i + 1)))
            f.renameTo(File(dir, archiveName(1)))
            File(dir, f.name).writeText(
                "… продолжение — предыдущая часть в ${archiveName(1)} …\n\n")
        } catch (_: Throwable) {}
    }

    private const val ARCHIVES = 15
    private fun archiveName(i: Int) = "zen-journal.$i.txt"

    // Событие вне сеанса: загрузка подписок, смена настроек, разбор сети.
    fun note(msg: String) {
        if (open) { event(msg); return }
        if (dup(msg)) return
        write("· ${stamp()} $msg\n")
    }

    // Строка внутри сеанса. Сюда зеркалится ВСЁ, что клиент говорит человеку,
    // пока идёт подключение, — включая сообщения самих ядер.
    fun event(msg: String) {
        if (dup(msg)) return
        if (!open) { if (interesting(msg)) write("· ${stamp()} $msg\n"); return }
        val dt = (System.currentTimeMillis() - start) / 100 / 10.0
        write("│ +${dt} $msg\n")
    }

    // Что стоит сохранить, даже когда никто никуда не подключается.
    // Обычные «загружаю подписки…» в файле не нужны — они его только топят.
    //
    // «…» и «✅» здесь не для красоты: это голос сторожа живости, а он работает
    // как раз ВНЕ сеанса. Без них в журнале оставались одни разрывы без
    // предыстории — четырнадцать «💔» за день и ни одной строки о том, сколько
    // раз клиент был в шаге от разрыва и что его удержало. Понять по такому
    // журналу, стало ли лучше после правки, невозможно.
    private fun interesting(msg: String) =
        msg.startsWith("❌") || msg.startsWith("⚠️") || msg.startsWith("🔒") ||
        msg.startsWith("📵") || msg.startsWith("🧾") || msg.startsWith("↔") ||
        msg.startsWith("…") || msg.startsWith("✅") ||
        // Итог проверки DNS и пойманные подмены. Проверка идёт уже после
        // подъёма, вне сеанса, — без этого в журнале не остаётся следа, что
        // оператор подменяет ответы.
        msg.startsWith("🟢") || msg.startsWith("🟡") || msg.startsWith("🔴") ||
        msg.startsWith("🛡") || msg.startsWith("🧹") || msg.startsWith("   DNS оператора")

    // Поток логов самих ядер (sing-box, Xray, ByeDPI).
    //
    // Именно здесь лежит самое ценное — настоящая причина отказа: «peer cert
    // is invalid», «context deadline exceeded», «connection reset by peer».
    // Ровно из такой строки в августе выяснилось, что подмена SNI ломает
    // аварийные ноды. Но целиком этот поток писать нельзя: он идёт сотнями
    // строк в минуту и за один сеанс съест весь файл.
    //
    // Поэтому в журнал попадают только жалобы, и не больше LIMIT за сеанс:
    // если ядро ругается непрерывно, первых полусотни строк хватит, чтобы
    // понять почерк, а остальное — то же самое ещё тысячу раз.
    private const val CORE_LIMIT = 60
    @Volatile private var coreLines = 0

    private val COMPLAINTS = listOf(
        "error", "fatal", "failed", "failure", "invalid", "refused", "reset",
        "timeout", "deadline", "unreachable", "denied", "reject", "no route",
        "eof", "handshake", "certificate", "cert is", "panic", "closed"
    )

    fun core(msg: String) {
        if (!open) return                       // вне подключения ядро не слушаем
        val low = msg.lowercase()
        if (COMPLAINTS.none { low.contains(it) }) return
        if (coreLines >= CORE_LIMIT) return
        if (dup(msg)) return
        coreLines++
        val dt = (System.currentTimeMillis() - start) / 100 / 10.0
        // Причина у ядра — в КОНЦЕ строки: «failed to send upload > Get …:
        // dial tcp: lookup cdn.thriller4ag.com: …». Здесь стояло take(220), и
        // всё самое важное отрезалось — по журналу было не понять, что
        // сломалось. Длинную строку держим целиком, а очень длинную — с
        // головой и хвостом, вырезая середину (там обычно повторы URL).
        val p = plain(msg)
        val line = if (p.length <= 900) p else p.take(300) + " … " + p.takeLast(560)
        write("│ +${dt} ⟨ядро⟩ $line\n" +
            if (coreLines == CORE_LIMIT) "│ … дальше ядро ругается тем же — не пишу\n" else "")
    }

    // Ядра красят свой вывод для терминала. В файле эта раскраска — набор
    // «[36m», сквозь который не читается ничего; вырезаем.
    // Только НАСТОЯЩАЯ раскраска: либо с управляющим символом, либо скобка
    // с цифрами и «m» на конце — «[31m», «[0m», «[38;5;174m».
    //
    // Прежнее правило вырезало ЛЮБУЮ скобку с буквой и портило сам текст:
    // «inbound/tun[tun-in]» превращалось в «inbound/tunun-in]». Цифры и «m»
    // обязательны — в именах ядра такого сочетания не бывает.
    private val ANSI = Regex("\\u001B\\[[0-9;]*[A-Za-z]|\\[[0-9;]+m")
    private fun plain(s: String) = ANSI.replace(s, "")

    // То же самое, но для экрана.
    //
    // В файл раскраску вырезали давно, а на вкладку журнала она шла как есть —
    // и человек видел «[31mERROR[0m[0010]» вместо «ERROR». Читается это как
    // код ошибки 31, которого не существует: 31 — это «красный», 0 — «сброс
    // цвета». Вдобавок ядро помечает каждое соединение цветным номером вида
    // «[[38;5;174m1326802846[0m 0ms]», и строка превращается в кашу.
    fun readable(s: String) = plain(s)

    // Строка пришла из потока логов ядра? Такие мы уже записали через core(),
    // и второй раз, придя тем же путём в интерфейс, они не нужны.
    fun looksLikeCore(s: String): Boolean {
        val t = plain(s).trimStart()
        return t.startsWith("INFO[") || t.startsWith("ERROR[") ||
            t.startsWith("WARN[") || t.startsWith("DEBUG[") || t.startsWith("FATAL[") ||
            t.startsWith("[Xray]")
    }

    // МЕТКА ЧЕЛОВЕКА — мост между тем, кто тестирует, и тем, кто разбирает.
    //
    // Тесты идут руками и без нас: человек гоняет клиент, ловит странность, а
    // разбирать её приходит другой и через сутки. Пересказать словами «вот
    // тут отвалилось» невозможно — в журнале к этому моменту сотни строк, и
    // непонятно, на какой смотреть.
    //
    // Метка ставится в тот самый момент, когда странность видна, и ищется в
    // файле по звёздочкам. Дальше по ней читается всё, что было рядом.
    fun mark(note: String = "") {
        val text = "\n★★★ МЕТКА ЧЕЛОВЕКА · ${stamp()}" +
            (if (note.isNotBlank()) " · «${note.take(120)}»" else "") + " ★★★\n"
        // Мимо dup(): две одинаковые метки подряд — это два разных нажатия,
        // и склеивать их нельзя.
        write(text)
    }

    // Идёт ли прямо сейчас записанный сеанс. Сервису нужно знать: запуск из
    // шторки или системой (always-on) проходит мимо приложения, и открывать
    // сеанс тогда некому, кроме него самого.
    val inSession: Boolean get() = open

    // ------------------------------------------------------------- сеансы

    // Начало попытки подключения. header — обстановка на момент старта:
    // сеть, нода, ядро, режимы. Всё, чего потом не восстановить.
    fun session(node: String, header: List<String>) {
        val c = if (remote) null else app
        val n = if (c != null) sp(c).getInt(K_ATTEMPT, 0) + 1 else 0
        c?.let { sp(it).edit().putInt(K_ATTEMPT, n).putBoolean(K_OPEN, true).apply() }
        start = System.currentTimeMillis()
        open = true
        coreLines = 0
        write(buildString {
            append("\n┌── ${stamp()} · " + (if (remote) "попытка Amnezia" else "попытка #$n") + "\n")
            header.forEach { append("│ $it\n") }
        })
        currentNode = node
    }

    @Volatile private var currentNode = ""

    // Исход попытки. Вызывать обязательно — незакрытый сеанс потом читается
    // как «процесс умер», и это будет ложью в журнале.
    fun outcome(ok: Boolean, why: String) {
        if (!open) return
        val secs = (System.currentTimeMillis() - start) / 100 / 10.0
        open = false
        if (!remote) app?.let { sp(it).edit().putBoolean(K_OPEN, false).apply() }
        write("└── ${if (ok) "✔ ПОДНЯЛОСЬ" else "✖ НЕ ПОДНЯЛОСЬ"} за $secs с" +
            (if (why.isNotEmpty()) " · $why" else "") + "\n")
        if (currentNode.isNotEmpty() && !remote) score(currentNode, ok, why, secs)
    }

    // Сеанс кончился не исходом, а решением человека: сменил сервер, нажал
    // «отключить». Это не провал ноды, и в счётчик такое писать нельзя.
    fun abandon(why: String) {
        if (!open) return
        open = false
        if (!remote) app?.let { sp(it).edit().putBoolean(K_OPEN, false).apply() }
        write("└── ⏹ прервано: $why\n")
    }

    // --------------------------------------------------- счётчики по нодам

    // Сводка «нода — попыток — поднялась — последняя ошибка». Именно она
    // отвечает на вопрос, который иначе тонет в хронологии: эта нода не
    // работает вообще никогда или сорвалась один раз в плохой день?
    private fun score(node: String, ok: Boolean, why: String, secs: Double) {
        val c = app ?: return
        try {
            val root = JSONObject(sp(c).getString(K_SCORE, "{}") ?: "{}")
            val o = root.optJSONObject(node) ?: JSONObject()
            o.put("a", o.optInt("a") + 1)
            if (ok) {
                o.put("o", o.optInt("o") + 1)
                o.put("sum", o.optDouble("sum", 0.0) + secs)
                o.put("okAt", stamp())
            } else {
                o.put("err", why)
                o.put("errAt", stamp())
            }
            root.put(node, o)
            sp(c).edit().putString(K_SCORE, root.toString()).apply()
        } catch (_: Throwable) {}
    }

    private fun scoreLines(): List<String> {
        val c = app ?: return emptyList()
        return try {
            val root = JSONObject(sp(c).getString(K_SCORE, "{}") ?: "{}")
            root.keys().asSequence().map { k ->
                val o = root.getJSONObject(k)
                val a = o.optInt("a"); val ok = o.optInt("o")
                val avg = if (ok > 0) " · подъём в среднем ${"%.1f".format(o.optDouble("sum") / ok)} с" else ""
                val last = when {
                    ok == 0 && o.has("err") -> "\n      последняя ошибка (${o.optString("errAt")}): ${o.optString("err")}"
                    o.has("err") -> "\n      последняя ошибка (${o.optString("errAt")}): ${o.optString("err")}" +
                        "\n      последний успех: ${o.optString("okAt", "—")}"
                    else -> ""
                }
                Triple(a, ok, "  $k\n      попыток $a, поднялась $ok$avg$last")
            }
                // Наверх — то, что чаще всего не поднимается: с этого и разбор.
                .sortedBy { (a, ok, _) -> if (a == 0) 0.0 else ok.toDouble() / a }
                .map { it.third }
                .toList()
        } catch (_: Throwable) { emptyList() }
    }

    // ------------------------------------------------------------- чтение

    // Готовый отчёт: шапка про устройство, сводка по нодам, затем хронология.
    fun report(ctx: Context): String {
        init(ctx)
        val f = file
        val body = try { f?.takeIf { it.exists() }?.readText().orEmpty() } catch (_: Throwable) { "" }
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (сборка ${@Suppress("DEPRECATION") pi.versionCode})"
        } catch (_: Throwable) { "?" }
        val score = scoreLines()
        return buildString {
            append("════ ZEN NITRO · журнал отладки ════\n")
            append("версия $ver · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ")
            append("Android ${android.os.Build.VERSION.RELEASE}\n")
            append("снят ${stamp()} · сеть сейчас: ${NetInfo.line(ctx)}\n")
            append("файл: ${path()}\n\n")
            if (score.isEmpty()) append("── по нодам пока ничего не накопилось ──\n\n")
            else {
                append("── что накопилось по нодам (сначала самые безнадёжные) ──\n")
                score.forEach { append(it).append('\n') }
                append('\n')
            }
            append("── как оно шло ──\n")
            append(if (body.isBlank()) "(журнал пуст)\n" else body)
        }
    }

    // Сложить отчёт в отдельный файл, который можно отправить.
    //
    // Отправляем именно отчёт, а не сам журнал: в отчёте есть шапка про
    // устройство и сводка по нодам, без которых хронология мало что значит.
    // Каталог report/ — единственное место, открытое наружу через провайдер.
    fun export(ctx: Context): File? = try {
        val dir = File(ctx.getExternalFilesDir(null), "report").apply { mkdirs() }
        File(dir, "zen-report.txt").apply { writeText(report(ctx)) }
    } catch (_: Throwable) { null }

    // Сколько всего накоплено — для строки в интерфейсе.
    fun size(): Long = try { file?.takeIf { it.exists() }?.length() ?: 0L } catch (_: Throwable) { 0L }

    fun clear() {
        synchronized(lock) {
            try { file?.writeText("") } catch (_: Throwable) {}
            open = false
            app?.let {
                sp(it).edit().putString(K_SCORE, "{}")
                    .putBoolean(K_OPEN, false).putInt(K_ATTEMPT, 0).apply()
            }
        }
    }
}
