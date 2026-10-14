package com.zen.blaster

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.zen.blaster.core.ConfigBuilder
import com.zen.blaster.core.Prefs
import com.zen.blaster.core.Stats
import com.zen.blaster.core.Subscriptions
import com.zen.blaster.core.XrayEngine
import com.zen.blaster.model.Node
import com.zen.blaster.model.Subscription
import com.zen.blaster.ui.*
import com.zen.blaster.vpn.ZenAwgService
import com.zen.blaster.vpn.ZenVpnService
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val nodes = mutableStateListOf<Node>()
    private val logLines = mutableStateListOf<String>()
    private var pendingConfig: String? = null
    private var pendingXray: String? = null      // конфиг Xray для текущего подключения

    // 0 — выкл, 1 — подключение, 2 — подключено
    private val connState = mutableIntStateOf(0)
    private val selected = mutableIntStateOf(0)
    private val showPicker = mutableStateOf(false)   // шторка выбора сервера

    // Идёт ли сейчас загрузка подписок — по этому флагу крутится значок
    // обновления в шапке. Без него кнопка выглядит мёртвой: нажал, а понять,
    // работает оно или нет, можно только уйдя в логи.
    private val subsLoading = mutableStateOf(false)
    private var subsLoadingAt = 0L
    // Идёт ли замер пинга / проверка канала — от двойных нажатий.
    @Volatile private var pingBusy = false
    @Volatile private var probeBusy = false

    // Обновление приложения.
    //   update      — что лежит снаружи; null = всё свежее, кнопки нет вовсе
    //   updProgress — −2 покой, −1 качаю (размер неизвестен), 0..100 проценты
    //   updReady    — скачанный APK, ждёт нажатия «установить»
    private val update = mutableStateOf<com.zen.blaster.core.Updater.Release?>(null)
    private val updProgress = mutableIntStateOf(-2)
    private val updReady = mutableStateOf<java.io.File?>(null)

    // Вид надписи сверху. Держим в состоянии, а не читаем из настроек на
    // каждой отрисовке: правка должна быть видна сразу, прямо под пальцем.
    private val titleText = mutableStateOf("")
    private val titleFont = mutableIntStateOf(0)
    private val titleSize = mutableIntStateOf(30)
    private val showTitleDlg = mutableStateOf(false)

    // Раздельное туннелирование: РУ-сайты и РУ-приложения мимо туннеля.
    private val splitRu = mutableStateOf(false)
    private val showAppPicker = mutableStateOf(false)
    private val showModesDlg = mutableStateOf(false)
    private val showJournal = mutableStateOf(false)
    private val showLeaks = mutableStateOf(false)

    // Как часто перемерять пинг всего списка сам по себе.
    private val PING_EVERY = 30 * 60_000L

    // Какие полки с серверами человек открыл в настройках. Ключ — имя
    // подписки. Чего здесь нет, то закрыто (кроме полки с выбранным сервером).
    private val subOpen = mutableStateMapOf<String, Boolean>()
    private val markNote = mutableStateOf("")

    // Номер попытки подключения. По нему сторож отката понимает, что за время
    // ожидания человек уже начал другое подключение, и молча уходит.
    private var connectAttempt = 0

    // Нода, которая только что отказалась подниматься с подменённым именем.
    // Если без подмены она встанет, человек должен об этом узнать: значит
    // режим подмены ей мешает, и держать его включённым незачем.
    private var frontFailedFor = ""

    // То же для связки с ByeDPI: нода не встала через щит, но встала напрямую.
    private var chainFailedFor = ""

    // Как использовать ноду: туннель на весь телефон или локальный прокси.
    // Имя НЕ proxyMode: внутри toggleConnect живёт одноимённая локальная
    // переменная, и перекрытие читалось бы как ошибка.
    private val useProxy = mutableStateOf(false)

    // Чем поднимать ноду: 0 — авто, 1 — sing-box, 2 — Xray.
    private val tunEngine = mutableIntStateOf(0)

    // Ping лежит обычным полем внутри Node, а Compose такие изменения не видит.
    // Тик заставляет список перерисоваться после замера — иначе новые цифры
    // просто не появлялись.
    private val pingTick = mutableIntStateOf(0)

    // Примета ноды: по ней находим ту же самую после пересборки списка.
    private fun keyOf(n: com.zen.blaster.model.Node) = "${n.addr}:${n.port}|${n.name}"

    // Примета для ПОМЕТОК О ПОВЕДЕНИИ ноды — без имени.
    //
    // Имя провайдер меняет когда захочет: «ICEbreaker» 6 сентября 2026 разом
    // потеряла ведущий пробел, и все наши пометки о ней (подмена имени вредит,
    // сервер предъявляет чужой сертификат) перестали к ней относиться —
    // клиент заново наступал на те же грабли. Адрес и порт устойчивее.
    private fun traitKey(n: com.zen.blaster.model.Node) = "${n.addr}:${n.port}"

    // Дата пропажи ноды — коротко, «19.08». Год не пишем: нода, пропавшая
    // больше недели назад, до списка не доживает.
    private fun goneDate(ms: Long): String =
        if (ms <= 0) "?" else java.text.SimpleDateFormat("dd.MM", java.util.Locale.US)
            .format(java.util.Date(ms))

    // ПРИМЕТА НОДЫ — по ней сводим повторы и узнаём ноду после обновления.
    //
    // Раньше приметой были только подписка, адрес с портом и имя. На одном
    // хосте провайдер держит несколько разных серверов — например Reality
    // поверх XHTTP и обычный VLESS-TLS, — и у них совпадает всё, кроме
    // протокола с транспортом. Такие ноды считались одной и той же, и при
    // сведении повторов лишние молча выбрасывались: подписка Sirius отдавала
    // 15 серверов, а в списке оставалось 8.
    //
    // Протокол и транспорт в примету входят, а вот путь, SNI и ключи — нет:
    // одну и ту же ноду разные клиенты подписки отдают то ссылкой, то JSON'ом,
    // и мелкие расхождения разбора раздвоили бы её на пустом месте.
    private fun nodeKey(n: Node): String =
        "${n.sub}|${n.type}|${n.transport}|${n.addr}:${n.port}|${n.name}"

    // Та же примета, но БЕЗ адреса: по ней узнаём ноду, которая просто
    // переехала на другой IP. Для сведения повторов такой ключ не годится
    // (у одного провайдера бывают тёзки — «🇧🇪Belgium [Hysteria2]» в списке
    // fastlanehosting встречается дважды), но чтобы отличить переезд от
    // исчезновения — ровно то, что нужно.
    private fun movedKey(n: Node): String =
        "${n.sub}|${n.type}|${n.transport}|${n.name}"

    // Найти выбранную ноду в новом списке.
    //
    // Три захода, от точного к терпимому:
    //   1) полная примета — адрес и имя совпали;
    //   2) тот же адрес, другое имя — провайдер переименовал ноду;
    //   3) то же имя, другой адрес — провайдер переехал на новый IP.
    //
    // Третьего захода тут не было, и это дорого стоило. Часть провайдеров
    // (fastlanehosting в журнале 4 сентября — прямо в замерах видно, как у
    // одной и той же ноды меняется адрес: 2.27.31.68 → 31.77.105.67) крутит
    // адреса при каждом обновлении подписки. Выбор человека при этом слетал
    // на первую ноду списка, а в журнал шло «прежний сервер пропал» — хотя
    // сервер никуда не девался. Отсюда и жалоба, что серверы сами сбиваются.
    private fun restoreSelection() {
        val key = prefs.selectedKey
        if (key.isEmpty()) { if (selected.intValue >= nodes.size) selected.intValue = 0; return }
        var i = nodes.indexOfFirst { keyOf(it) == key }
        if (i < 0) {
            val addr = key.substringBefore('|')
            i = nodes.indexOfFirst { "${it.addr}:${it.port}" == addr }
        }
        if (i < 0) {
            val name = key.substringAfter('|', "")
            if (name.isNotEmpty()) {
                i = nodes.indexOfFirst { it.name == name }
                if (i >= 0) {
                    log("↔️ «$name» переехала на ${nodes[i].addr}:${nodes[i].port} — выбор сохранён")
                    prefs.selectedKey = keyOf(nodes[i])
                }
            }
        }
        selected.intValue = if (i >= 0) i else 0
        if (i < 0 && nodes.isNotEmpty())
            log("⚠️ Прежнего сервера нет в свежей выдаче — выбран первый в списке")
    }

    // Название аварийной группы. Держится первой в списке: когда режут всё,
    // кроме белого списка, искать нужную ноду среди сотни некогда.
    private val WL_GROUP = "⚪ Белый список (аварийные)"

    // Режим Amnezia: отдельное ядро (AWG 1.5/2.0) и свой список профилей.
    private val awgProfiles = mutableStateListOf<com.zen.blaster.model.AwgProfile>()
    private val selectedAwg = mutableIntStateOf(0)
    private val engine = mutableIntStateOf(0)        // 0 — sing-box/Xray, 1 — Amnezia
    private var pendingAwg: String? = null

    // Ядро Amnezia живёт в отдельном процессе, поэтому шлёт состояние
    // broadcast'ами — обычный колбэк через границу процесса не пролезет.
    private val awgReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (i == null) return
            i.getStringExtra(ZenAwgService.B_LOG)?.let { log(it) }
            i.getStringExtra(ZenAwgService.B_STAGE)?.let { Stats.stage.value = it }
            if (i.hasExtra(ZenAwgService.B_UP)) Stats.up.value = i.getLongExtra(ZenAwgService.B_UP, 0)
            if (i.hasExtra(ZenAwgService.B_DOWN)) Stats.down.value = i.getLongExtra(ZenAwgService.B_DOWN, 0)
            if (i.hasExtra(ZenAwgService.B_PING)) {
                val ms = i.getIntExtra(ZenAwgService.B_PING, -1)
                Stats.ping.value = ms
                Stats.feedPing(ms)
            }
            if (i.hasExtra(ZenAwgService.B_CONNECTED)) {
                val on = i.getBooleanExtra(ZenAwgService.B_CONNECTED, false)
                connState.intValue = if (on) 2 else 0
                if (on) Stats.since.value = System.currentTimeMillis() else Stats.reset()
            }
        }
    }

    // Запрос разрешения VPN (VpnService.prepare).
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            pendingAwg?.let { startAwg(it); pendingAwg = null }
                ?: pendingConfig?.let { startTunnel(it) }
        } else { connState.intValue = 0; pendingAwg = null; log("❌ Разрешение VPN не выдано") }
    }

    // Android 13+ требует runtime-разрешение, иначе уведомление в шторке
    // (скорость/пинг/сервер) просто не покажется.
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) log("⚠️ Без разрешения на уведомления не будет статистики в шторке")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        selected.intValue = prefs.selectedServer
        selectedAwg.intValue = prefs.selectedAwg
        engine.intValue = prefs.engine
        awgProfiles.addAll(prefs.awgProfiles())
        // Номер выбранного профиля мог пережить его удаление — не даём ему
        // указывать в пустоту.
        if (selectedAwg.intValue !in awgProfiles.indices) {
            selectedAwg.intValue = 0; prefs.selectedAwg = 0
        }
        titleText.value = prefs.titleText
        titleFont.intValue = prefs.titleFont
        titleSize.intValue = prefs.titleSize
        splitRu.value = prefs.geoRouting
        useProxy.value = prefs.proxyMode
        tunEngine.intValue = prefs.tunnelEngine
        // Совет подписки переживает перезапуск: иначе «Авто» до первой
        // загрузки подписок оставалось бы ни с чем.
        com.zen.blaster.core.DnsChoice.fromSub = prefs.autoDns
        com.zen.blaster.core.Resolver.preferred = prefs.preDnsUrl()
        com.zen.blaster.core.Resolver.attach(this)
        ConfigBuilder.deviceFp = prefs.deviceFingerprint()

        ZenVpnService.onLog = { runOnUiThread { log(it) } }
        ZenVpnService.onState = { on -> runOnUiThread {
            connState.intValue = if (on) 2 else 0
            // ТУННЕЛЬ ПОДНЯЛСЯ — ЗНАЧИТ НОДА ЖИВАЯ, ЧЕМ БЫ ОНА НИ ОТВЕЧАЛА
            // НА ПРОБЫ. У глухих Hysteria2 это единственное доказательство
            // жизни: пинга у них не будет никогда (открыт один UDP-порт, и
            // тот отвечает только на валидное рукопожатие), а работать они
            // могут прекрасно. Без этой отметки такая нода выглядела бы в
            // списке ровно как мёртвая.
            if (on && engine.intValue != 1) nodes.getOrNull(selected.intValue)?.let { n ->
                prefs.rememberCarrierWin(com.zen.blaster.core.NetInfo.carrierKey(this), traitKey(n))
                cascadeTried.clear(); cascadeIdle = 0
                n.lastOkAt = System.currentTimeMillis()
                n.silentTries = 0
                prefs.cacheNodes(nodes)
                pingTick.intValue++
            }
            // Поднялось со второй попытки, после снятия подмены имени —
            // говорим прямо, в чём было дело.
            if (on && frontFailedFor.isNotEmpty()) {
                val n = nodes.getOrNull(selected.intValue)
                if (n != null && keyOf(n) == frontFailedFor) {
                    log("✅ Поднялось БЕЗ подмены имени — этой ноде подмена мешает.")
                    log("   Если так со всеми серверами, выключите «Режим белых списков»:")
                    log("   он нужен в дни, когда оператор рубит всё, кроме разрешённого.")
                    com.zen.blaster.core.Blackbox.note(
                        "✅ «${n.name}» поднялась ТОЛЬКО без подмены имени — " +
                        "режим подмены вредит этой ноде")
                    // Запоминаем НАВСЕГДА, а не до перезапуска. Иначе каждое
                    // следующее подключение начиналось бы с той же обречённой
                    // попытки: подмена → «peer cert is invalid» → откат.
                    prefs.rememberFrontHostile(traitKey(n))
                    log("   Запомнил: этой ноде подмену больше не предлагаю.")
                }
                frontFailedFor = ""
            }
            if (on && chainFailedFor.isNotEmpty()) {
                val n = nodes.getOrNull(selected.intValue)
                if (n != null && keyOf(n) == chainFailedFor) {
                    log("✅ Поднялось БЕЗ связки с ByeDPI — щит мешал именно этой ноде.")
                    log("   Щит продолжает работать сам по себе. Если так со всеми")
                    log("   серверами — выключите «Гнать ноду через ByeDPI» в режимах.")
                    com.zen.blaster.core.Blackbox.note(
                        "✅ «${n.name}» поднялась ТОЛЬКО без связки с ByeDPI")
                    // Навсегда, а не до перезапуска: иначе каждое подключение
                    // к ней начиналось бы с тех же двадцати секунд впустую.
                    prefs.rememberChainHostile(traitKey(n))
                    log("   Запомнил: эту ноду больше через щит не гоню.")
                }
                chainFailedFor = ""
            }
        } }

        val f = android.content.IntentFilter(ZenAwgService.BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(awgReceiver, f, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(awgReceiver, f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        installCrashLogger()
        // Чёрный ящик открываем до всего остального: пусть застанет и загрузку
        // подписок, и первую попытку подключения.
        com.zen.blaster.core.Blackbox.init(this)
        com.zen.blaster.core.Blackbox.note(
            "▶ приложение открыто · ${com.zen.blaster.core.NetInfo.line(this)}")

        // Сначала показываем кэш — приложение открывается со списком серверов
        // сразу, даже если сеть лежит и подписки не ответят.
        nodes.addAll(prefs.cachedNodes())
        restoreSelection()

        setContent { ZenTheme { HomeScreen() } }
        loadServers()
        // Сначала показываем то, что уже знаем с прошлого раза, — кнопка
        // появляется мгновенно. Поход в сеть идёт следом и молча уточняет.
        update.value = com.zen.blaster.core.Updater.cached(this)
        checkUpdate(force = false)
        // Конфиг Amnezia могли открыть «поделиться» из файлового менеджера.
        intent?.let { handleImportIntent(it) }
    }

    // ------------------------------------------------------------ обновления

    // Проверка молчаливая: если всё свежее, человек об этом не узнаёт и
    // не должен — никаких «у вас последняя версия».
    private fun checkUpdate(force: Boolean) {
        lifecycleScope.launch {
            val r = runCatching { com.zen.blaster.core.Updater.check(this@MainActivity, force) }
                .getOrNull()
            update.value = r
            if (r == null) {
                // Обновление могли уже поставить — тогда и скачанный файл,
                // и кнопка «установить» больше не нужны. Файл сносим сразу:
                // это 25 МБ, которые иначе лежали бы мёртвым грузом.
                updReady.value = null
                updProgress.intValue = -2
                runCatching { com.zen.blaster.core.Updater.apkFile(this@MainActivity).delete() }
                if (force) log("✔ Обновлений нет, стоит свежая версия")
            } else log("⬆️ Есть версия ${r.name} (сборка ${r.code})")
        }
    }

    private fun downloadUpdate() {
        val rel = update.value ?: return
        if (updProgress.intValue != -2) return          // уже качаем
        updProgress.intValue = -1
        log("⬇️ Качаю обновление ${rel.name}…")
        lifecycleScope.launch {
            val f = com.zen.blaster.core.Updater.download(this@MainActivity, rel) { pct ->
                runOnUiThread { updProgress.intValue = pct }
            }
            updProgress.intValue = -2
            if (f == null) { log("❌ Не скачалось — сеть или адрес"); toast("Обновление не скачалось") }
            else { updReady.value = f; log("✔ Скачано, жду нажатия «установить»"); installUpdate() }
        }
    }

    // Отдаём файл штатному установщику. Сами ничего не ставим: тихая
    // установка требует прав владельца устройства, и это правильно —
    // подмена сборки должна упираться в живого человека.
    private fun installUpdate() {
        val f = updReady.value ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            // Разрешение «установка неизвестных приложений» даётся адресно
            // нашему пакету — ведём человека ровно на этот экран.
            log("⚠️ Разрешите установку из этого приложения и нажмите ещё раз")
            toast("Разрешите установку из этого приложения")
            runCatching {
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName")))
            }
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.files", f)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { log("❌ Установщик не открылся: ${it.message}") }
    }

    // ------------------------------------------------- быстрое «+» из буфера
    //
    // Одно нажатие и никаких диалогов: скопировал ссылку в мессенджере —
    // вернулся — вставил. Разбираем сами, что именно лежит в буфере.
    private fun quickAddFromClipboard() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val raw = cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { toast("Буфер пуст"); log("⚠️ Буфер обмена пуст"); return }

        // Конфиг Amnezia узнаётся по заголовку секции или своей схеме.
        if (raw.startsWith("vpn://") || raw.contains("[Interface]")) {
            importAwg(raw); toast("Профиль Amnezia добавлен"); return
        }

        val lines = raw.split('\n', '\r').map { it.trim() }.filter { it.contains("://") }
        if (lines.isEmpty()) { toast("В буфере нет ссылки"); log("⚠️ В буфере нет ссылки"); return }

        val have = prefs.subscriptions().map { it.url }.toHashSet()
        var added = 0
        var dup = 0
        for (u in lines) {
            if (!have.add(u)) { dup++; continue }        // повтор — молча мимо
            val name = when {
                u.contains('#') ->
                    runCatching { java.net.URLDecoder.decode(u.substringAfterLast('#'), "UTF-8") }
                        .getOrDefault("Конфиг")
                u.startsWith("http") ->
                    runCatching { java.net.URI(u).host }.getOrNull() ?: "Подписка"
                else -> "Конфиг"
            }.take(40)
            prefs.addSubscription(name, u)
            added++
        }
        if (added == 0) { toast("Это уже добавлено"); log("• В буфере $dup уже известных ссылок"); return }
        toast(if (added == 1) "Добавлено" else "Добавлено: $added")
        log("➕ Из буфера: $added" + if (dup > 0) " (повторов: $dup)" else "")
        loadServers()
    }

    // Короткое подтверждение действия. Логи — на своей вкладке, а человек,
    // нажавший кнопку в шапке, должен видеть ответ сразу и на месте.
    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleImportIntent(i) }

    // Возврат в приложение (из шторки, из недавних, после сворачивания) должен
    // показывать РЕАЛЬНОЕ состояние туннеля, а не то, что осталось в памяти.
    override fun onResume() {
        super.onResume()
        val on = if (engine.intValue == 1) prefs.awgRunning && vpnActive() else ZenVpnService.running
        when {
            on && connState.intValue != 2 -> {
                connState.intValue = 2
                if (Stats.since.value == 0L) Stats.since.value = System.currentTimeMillis()
                if (Stats.server.value.isEmpty()) Stats.server.value =
                    if (engine.intValue == 1) awgProfiles.getOrNull(selectedAwg.intValue)?.name.orEmpty()
                    else nodes.getOrNull(selected.intValue)?.name.orEmpty()
            }
            // «Подключение…» не трогаем: туннель может как раз подниматься.
            !on && connState.intValue == 2 -> { connState.intValue = 0; Stats.reset() }
        }
    }

    // Есть ли вообще активный VPN у системы — нужно для ядра Amnezia,
    // которое живёт в отдельном процессе и статикой не поделится.
    private fun vpnActive(): Boolean = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        cm.allNetworks.any {
            cm.getNetworkCapabilities(it)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Throwable) { false }

    override fun onDestroy() {
        try { unregisterReceiver(awgReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    // Приём .conf/vpn:// из «Открыть с помощью» и «Поделиться».
    private fun handleImportIntent(i: Intent) {
        val text = when {
            i.data != null -> try {
                if (i.data!!.scheme == "vpn") i.dataString
                else contentResolver.openInputStream(i.data!!)?.bufferedReader()?.use { it.readText() }
            } catch (t: Throwable) { null }
            i.hasExtra(Intent.EXTRA_TEXT) -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        importAwg(text)
    }

    // Импорт профиля Amnezia: .conf, vpn://-ссылка или JSON.
    private fun importAwg(text: String) {
        var p = com.zen.blaster.core.AwgParser.parseAny(text)
        if (p == null) { log("❌ Не разобрал конфиг Amnezia (ждал .conf, vpn:// или JSON)"); return }
        // У голого .conf имени нет, и все такие профили звались «Amnezia» —
        // второй молча затирал первый (14.10.2026: из двух импортированных
        // серверов в списке остался один). Тот же сервер — обновляем, другой —
        // добавляем рядом под именем с адресом.
        val same = prefs.awgProfiles().firstOrNull { it.name == p!!.name }
        if (same != null && same.endpoint != p.endpoint) p = p.copy(name = "${p.name} · ${p.host}")
        prefs.addAwgProfile(p)
        awgProfiles.clear(); awgProfiles.addAll(prefs.awgProfiles())
        selectedAwg.intValue = awgProfiles.indexOfFirst { it.name == p.name }.coerceAtLeast(0)
        prefs.selectedAwg = selectedAwg.intValue
        log("✅ Профиль «${p.name}» добавлен · ${p.badge()} · ${p.host}")
    }

    // ------------------------------------------------------------- логика

    // Падения кладём в файл рядом с настройками. Системный logcat живёт часы,
    // а жалоба «падает» приходит через дни — без этого стек не достать.
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                // Держим только последние падения, файл не должен пухнуть.
                val old = if (f.exists()) f.readText().takeLast(40_000) else ""
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US).format(java.util.Date())
                f.writeText(old + "\n=== $stamp · поток ${t.name} ===\n" +
                    android.util.Log.getStackTraceString(e))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    // Показать сохранённые падения во вкладке логов.
    private fun dumpCrashLog() {
        val f = java.io.File(filesDir, "crash.log")
        if (!f.exists()) { log("✅ Файл падений пуст — с момента установки не падало"); return }
        log("=== журнал падений (${f.length()} байт) ===")
        f.readText().takeLast(4000).lines().forEach { if (it.isNotBlank()) log(it) }
    }

    // Список на вкладке LOGS живёт в памяти и умирает вместе с приложением.
    // Всё то же самое зеркалим в чёрный ящик: во время сеанса — целиком,
    // вне его — только жалобы (см. Blackbox).
    private fun log(msg: String) {
        logLines.add(msg); if (logLines.size > 300) logLines.removeAt(0)
        // Вывод ядер сюда тоже приходит, но в журнал его пишет сам сервис —
        // отфильтрованным и без терминальной раскраски. Иначе каждая строка
        // ложилась бы в файл дважды.
        if (!com.zen.blaster.core.Blackbox.looksLikeCore(msg))
            com.zen.blaster.core.Blackbox.event(msg)
    }

    private fun loadServers() {
        // Двойное нажатие — не два похода в сеть. Но и не тишина: раньше
        // кнопка в этот момент молча ничего не делала, и выглядело это как
        // «обновить сломалась». А если прошлая загрузка почему-то повисла
        // дольше двух минут — не держим кнопку мёртвой, начинаем заново.
        if (subsLoading.value && System.currentTimeMillis() - subsLoadingAt < 120_000) {
            log("⏳ Подписки уже обновляются — дождитесь")
            return
        }
        log("Загружаю подписки…")
        subsLoading.value = true
        subsLoadingAt = System.currentTimeMillis()
        lifecycleScope.launch {
            // Флаг снимаем в любом случае: иначе одна упавшая подписка
            // оставила бы значок крутиться навсегда.
            try {
            // Ручные ссылки идут теми же путями, что и подписки: парсер
            // Subscriptions понимает и голый vless://, и адрес подписки.
            val sources = prefs.wlLinks().map { Subscription(WL_GROUP, it) } +
                prefs.subscriptions() +
                prefs.manualLinks().map { Subscription("Свои ссылки", it) }
            // На сотовой связи замер идёт щадящим шагом: полный веер по всему
            // списку отнимает у туннеля минуту эфира.
            // Пинг всего списка — не чаще раза в полчаса. Подписки тянутся при
            // каждом открытии приложения, и раньше вместе с ними каждый раз
            // шёл полный замер двухсот серверов: отсюда и нагрузка, и список,
            // который перерисовывался всякий раз, как его откроешь.
            val pingDue = System.currentTimeMillis() - prefs.lastPingAll >= PING_EVERY
            val fresh = Subscriptions.loadAll(sources,
                gentle = com.zen.blaster.core.NetInfo.snapshot(this@MainActivity).mobile,
                measure = pingDue)
            if (pingDue && fresh.isNotEmpty()) prefs.lastPingAll = System.currentTimeMillis()
            com.zen.blaster.core.Blackbox.note(
                if (pingDue) "📡 пинги серверов перемерены"
                else "📡 пинги свежие (${(System.currentTimeMillis() - prefs.lastPingAll) / 60_000} мин) — не перемеряю")
            if (fresh.isEmpty()) {
                // ПУСТОЙ ответ — это почти всегда лежащая сеть, а не «серверов
                // не стало». Затирать список нельзя: без нод не подключиться
                // даже туда, где всё работало минуту назад.
                log("⚠️ Подписки не ответили — оставляю прошлый список (${nodes.size})")
                return@launch
            }

            // ОТДЕЛЬНО НЕ ОТВЕТИВШИЙ ИСТОЧНИК ТОЖЕ НЕ ЗАТИРАЕМ.
            //
            // Защита выше срабатывала, только когда молчали ВСЕ разом. Стоило
            // одной подписке не ответить в конкретную секунду — её ноды молча
            // выпадали из списка и, что хуже, из кэша на диске: перезапуск их
            // уже не возвращал. Именно так 10.08.2026 разом исчезла вся
            // основная подписка (97 нод → 76), хотя ссылка была на месте и
            // через минуту отдала всё обратно.
            //
            // Причина одиночного молчания почти всегда снаружи: подписка
            // тянется через поднятый туннель, и восьми секунд на мобильной
            // сети хватает не всегда. Это не повод терять сервера.
            val gotBySub = fresh.groupBy { it.sub }
            val rescued = mutableListOf<Node>()
            // Спасаем ГРУППУ, а не каждую ссылку.
            //
            // Аварийные ссылки все носят одно имя группы. Раньше цикл шёл по
            // источникам и на каждой ссылке добавлял ВЕСЬ прошлый список этой
            // группы заново: четыре ссылки — четыре копии. Список удваивался
            // с каждым обновлением (12 → 48 → 96), одни и те же ноды стояли
            // подряд по пять раз.
            val handled = mutableSetOf<String>()
            for (src in sources) {
                if (!handled.add(src.name)) continue
                if ((gotBySub[src.name]?.size ?: 0) > 0) continue
                val prev = nodes.filter { it.sub == src.name }
                if (prev.isEmpty()) continue
                rescued += prev
                log("⚠️ «${src.name}» промолчала — оставляю прошлые ${prev.size}")
            }
            // НОДЫ, КОТОРЫЕ ПРОПАЛИ ИЗ ПОДПИСКИ, НЕ УДАЛЯЕМ СРАЗУ.
            //
            // Провайдер убирает сервер из выдачи, когда тот лёг: авария в
            // ЦОДе, переезд, блокировка. Со стороны это выглядит так, будто
            // сервер испарился — был вчера и нет сегодня, а вернётся ли он и
            // когда, узнать неоткуда: в подписке его просто нет.
            //
            // Держим такие ноды в списке с датой пропажи. Вернёт провайдер —
            // пометка снимется сама на ближайшем обновлении. Не вернёт за
            // неделю — уберём совсем, чтобы список не превращался в архив.
            //
            // Спасённые (из молчавшей подписки) сюда НЕ попадают: там источник
            // вообще не ответил, и делать вывод о судьбе конкретной ноды
            // нельзя. Пропажа считается только по подпискам, которые ответили.
            val answered = sources.map { it.name }.filter { (gotBySub[it]?.size ?: 0) > 0 }.toSet()
            val freshKeys = fresh.map { nodeKey(it) }.toSet()
            // Примета БЕЗ адреса. Нода, у которой сменился только IP, — это та
            // же нода, а не пропавшая плюс новая. Провайдеры с ротацией адресов
            // иначе каждый раз «теряют» половину списка: в журнале 4 сентября
            // одиннадцать нод fastlanehosting уходили в пропавшие и тут же
            // возвращались, и так по кругу весь день.
            val freshMoved = fresh.map { movedKey(it) }.toSet()
            val now = System.currentTimeMillis()
            val GONE_TTL = 7L * 24 * 60 * 60 * 1000      // неделя
            val vanished = mutableListOf<Node>()
            // Адреса, которые ЕСТЬ в свежей выдаче. По ним узнаём ноду,
            // которую провайдер переименовал: адрес и порт те же, имя другое.
            val freshAddrs = fresh.map { "${it.sub}|${it.addr}:${it.port}" }.toSet()
            for (n in nodes) {
                if (n.sub !in answered) continue          // источник промолчал
                val key = nodeKey(n)
                if (key in freshKeys) continue            // на месте
                if (movedKey(n) in freshMoved) continue   // на месте, сменила адрес
                // ПЕРЕИМЕНОВАННАЯ — ЭТО НЕ ВТОРАЯ НОДА, А ТА ЖЕ САМАЯ.
                //
                // Провайдер переименовывает ноды когда захочет: у Сириуса
                // «ICEbreaker» разом потеряла ведущий пробел и заодно сменила
                // тип на Reality-XHTTP. Старая запись оставалась в списке
                // призраком — тот же адрес, то же имя с точностью до пробела,
                // но помеченная «нет ответа». Человек видел в списке два
                // одинаковых сервера и не понимал, который живой. Свежая
                // версия уже в списке, призрак выбрасываем.
                if ("${n.sub}|${n.addr}:${n.port}" in freshAddrs) continue
                if (n.gone && now - n.goneAt > GONE_TTL) continue   // ждали неделю, хватит
                if (!n.gone) { n.gone = true; n.goneAt = now }
                vanished += n
            }
            if (vanished.isNotEmpty()) {
                log("🕳 Не пришло в выдаче: ${vanished.size} — из списка НЕ убираю")
                vanished.take(5).forEach { log("   ${it.name} (${it.sub})") }
                com.zen.blaster.core.Blackbox.note(
                    "🕳 не пришли в выдаче: " + vanished.joinToString(", ") { "«${it.name}»" }.take(300))
            }
            // Вернувшиеся — снимаем пометку молча.
            fresh.forEach { it.gone = false; it.goneAt = 0L }

            // И на всякий случай убираем повторы вообще: одна и та же нода
            // может прийти из двух источников сразу.
            val beforeDedup = fresh.size + rescued.size + vanished.size
            val list = (fresh + rescued + vanished)
                .distinctBy { nodeKey(it) }
                .sortedWith(compareBy(
                { it.sub },
                { it.badge() },
                { if (it.ping in 1..899) it.ping else 100000 }
            ))
            // Сколько срезало сведение повторов. Строка нужна, чтобы такую
            // пропажу было видно сразу: подписка отдаёт 15 нод, в списке 8 —
            // и без этой цифры непонятно, потерялись они при разборе или при
            // сведении.
            if (beforeDedup > list.size)
                com.zen.blaster.core.Blackbox.note(
                    "♊ повторы сведены: $beforeDedup → ${list.size}")
            // Помечаем аварийные ноды: по этому флагу к ним применяется
            // отдельный, консервативный набор параметров.
            list.forEach { it.wl = it.sub == WL_GROUP }
            // Переносим уже измеренные пинги на совпадающие ноды, чтобы список
            // не «моргал» в «—» после каждого обновления.
            //
            // По адресу, а не по имени: пинг — это время до адреса, и имя тут
            // ни при чём. А имена провайдеры меняют — у переименованной ноды
            // цифра пропадала бы до следующего замера.
            //
            // Удачную свежую цифру держим и тогда, когда свежий замер дал
            // осечку: «999» или «-1» после одной осечки — это морось, а не
            // новость. Час без удачного ответа — тогда уже говорим как есть.
            val old = nodes.associateBy { traitKey(it) }
            val nowMs = System.currentTimeMillis()
            list.forEach { n ->
                old[traitKey(n)]?.let { prev ->
                    val prevFresh = prev.pingAt > 0L &&
                        nowMs - prev.pingAt <= com.zen.blaster.core.Subscriptions.PING_HOLD
                    if (n.ping !in 1..899 && (prevFresh || n.ping < 0)) {
                        n.ping = prev.ping; n.pingExact = prev.pingExact; n.pingAt = prev.pingAt
                    }
                    // История молчания и отметка «через неё поднимался туннель»
                    // живут в старом объекте: при обновлении подписки ноды
                    // пересоздаются с нуля, и без переноса счётчик обнулялся бы
                    // каждый раз — глухую было бы не отличить от той, что
                    // промолчала впервые.
                    if (n.silentTries == 0) n.silentTries = prev.silentTries
                    if (n.lastOkAt == 0L) n.lastOkAt = prev.lastOkAt
                }
            }
            val keep = nodes.getOrNull(selected.intValue)
            nodes.clear(); nodes.addAll(list)
            // Полный замер не подошёл по времени — но новые ноды, которых в
            // прошлом списке не было, цифры не имеют вовсе. Их меряем сразу:
            // ждать полчаса, чтобы узнать пинг только что добавленного
            // сервера, — нелепо. Это единицы, а не весь веер.
            if (!pingDue) {
                val unmeasured = list.filter { it.pingAt == 0L && it.ping < 0 }
                if (unmeasured.isNotEmpty())
                    Subscriptions.reping(unmeasured,
                        gentle = com.zen.blaster.core.NetInfo.snapshot(this@MainActivity).mobile)
            }
            // Отчёт по каждому источнику.
            //
            // Раньше мёртвая подписка проваливалась молча: добавил — нод нет,
            // причины не видно. Теперь сразу видно, кто сколько дал и кто
            // не дал ничего.
            // Считаем по ТОМУ, ЧТО ПРИШЛО СЕЙЧАС, а не по итоговому списку:
            // иначе спасённые ноды маскировали бы мёртвую ссылку — источник
            // годами показывал бы «20 серверов», не отдавая ни одного.
            val rescuedSubs = rescued.map { it.sub }.toSet()
            sources.forEach { src ->
                if (src.name in rescuedSubs) return@forEach   // уже сказали выше
                val cnt = gotBySub[src.name]?.size ?: 0
                if (cnt == 0) {
                    // Показываем ДЛИНУ и хвост ссылки: почти всегда виновата
                    // обрезанная при копировании ссылка, и по длине это видно
                    // сразу, не гадая.
                    val u = src.url
                    log("⚠️ «${src.name}» — ни одного сервера.")
                    when {
                        // Собственный шифроконтейнер Happ. Внутри не адрес, а
                        // шифротекст: разобрать его, не зная ключа Happ, нельзя
                        // никаким парсером. Говорим это прямо, а не «формат не
                        // поддерживается» — человек должен понимать, что делать.
                        u.startsWith("happ://") -> {
                            log("   Это зашифрованная ссылка Happ (happ://crypt…).")
                            log("   Внутри не адрес, а шифротекст — вскрыть без ключа Happ нельзя.")
                            log("   Возьмите у провайдера ОБЫЧНУЮ ссылку подписки (https://…):")
                            log("   она есть всегда, happ:// — лишь обёртка для одного клиента.")
                        }
                        u.startsWith("http") -> {
                            log("   ссылка: ${u.length} символов, хвост …${u.takeLast(14)}")
                            log("   Пробовал представиться: Happ, v2rayNG, Streisand, Shadowrocket,")
                            log("   sing-box, NekoBox, браузером — панель не дала ни одной ноды.")
                            log("   Скорее всего подписка кончилась, либо адрес обрезан при копировании.")
                        }
                        else ->
                            log("   Формат не распознан. Нужны vless://, hysteria2://, trojan://, ss://, tuic:// или https://")
                    }
                } else log("   ${src.name}: $cnt")
            }
            prefs.cacheNodes(list)
            // Выбор держится за САМУ НОДУ, а не за её номер: список только что
            // пересобрался, и номер почти наверняка указывает на другую.
            if (prefs.selectedKey.isEmpty()) keep?.let { prefs.selectedKey = keyOf(it) }
            restoreSelection()
            pingTick.intValue++
            log("Загружено серверов: ${nodes.size}")
            // Разбивка по протоколам. Без неё пропажу целого протокола не
            // заметить: «85 серверов» выглядит прилично и тогда, когда все
            // ноды Hysteria2 из подписки потерялись по дороге.
            nodes.groupingBy { it.type }.eachCount().entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}: ${it.value}" }
                .let { if (it.isNotEmpty()) log("   по протоколам — $it") }
            // Кто из панелей что отдал. Строка на каждое имя клиента: код
            // ответа, размер, сколько нод. По ней сразу видно и «панель нас
            // не пустила», и «под этим именем нод вдвое меньше».
            com.zen.blaster.core.Subscriptions.report.toList()
                .forEach { com.zen.blaster.core.Blackbox.note("   $it") }
            // Резолвер, который назвала подписка. Запоминаем всегда — даже
            // если человек сейчас выбрал другой: передумает, и «Авто» уже
            // будет знать, что подставить.
            val advised = com.zen.blaster.core.Subscriptions.subDns
            if (advised.isNotEmpty() && advised != prefs.autoDns) {
                prefs.autoDns = advised
                com.zen.blaster.core.DnsChoice.fromSub = advised
                log("🌐 Подписка советует резолвер: " +
                    advised.removePrefix("https://").substringBefore('/'))
                // Если человек уже сидит на «Авто» — резолвер сменился прямо
                // сейчас, и клиент должен начать им пользоваться.
                if (prefs.dnsProvider == com.zen.blaster.core.DnsChoice.AUTO)
                    com.zen.blaster.core.Resolver.preferred = prefs.preDnsUrl()
            }

            // Ссылки, которые пришли, но не разобрались. Раньше они исчезали
            // молча, и потерянная нода выглядела как «её нет у провайдера».
            val lost = com.zen.blaster.core.Subscriptions.unparsed.toList()
            if (lost.isNotEmpty()) {
                log("⚠️ Не разобрано ссылок: ${lost.size} — эти серверы потеряны")
                lost.take(5).forEach { log("   $it") }
                if (lost.size > 5) log("   …и ещё ${lost.size - 5}")
            }
            } finally { subsLoading.value = false }
        }
    }

    private fun toggle() {
        if (connState.intValue != 0) { stopTunnel(); return }
        // Нажатие человека — новый каскад с чистого листа.
        cascadeTried.clear(); cascadeIdle = 0
        toggleConnect(force = false)
    }

    // ---------------------------------------------------------- умный каскад
    //
    //  Что уже пробовали в этом каскаде (адрес:порт) и сколько кругов подряд
    //  сеть сама была мертва. Сбрасывается нажатием и успешным подъёмом.
    private val cascadeTried = mutableSetOf<String>()
    private var cascadeIdle = 0

    // Следующая нода для каскада, когда сеть жива, а выбранная не встаёт.
    //
    //  Порядок: (1) поднималась на ЭТОМ операторе — свежие первыми; (2) жила
    //  недавно где угодно; (3) по свежему пингу. В режиме белых списков —
    //  только аварийные. Уже пробованные в этом каскаде пропускаем, пока
    //  не кончатся все.
    private fun nextCascadeNode(whitelist: Boolean): Int? {
        val wins = prefs.carrierWins(com.zen.blaster.core.NetInfo.carrierKey(this))
        val now = System.currentTimeMillis()
        fun pool(skipTried: Boolean) = nodes.withIndex().filter { (_, n) ->
            n.supported() && (!whitelist || n.wl) &&
                (!skipTried || traitKey(n) !in cascadeTried)
        }
        var cand = pool(true)
        if (cand.isEmpty()) {
            // Обошли всё — начинаем обход заново, но с той же очерёдностью.
            val cur = nodes.getOrNull(selected.intValue)?.let { traitKey(it) }
            cascadeTried.clear(); cur?.let { cascadeTried += it }
            cand = pool(true)
        }
        return cand.sortedWith(
            compareByDescending<IndexedValue<com.zen.blaster.model.Node>> { wins[traitKey(it.value)] ?: 0L }
                .thenByDescending { if (now - it.value.lastOkAt < 24 * 3600_000L) it.value.lastOkAt else 0L }
                .thenBy { if (it.value.ping > 0) it.value.ping else Int.MAX_VALUE }
        ).firstOrNull()?.index
    }

    // «пинг» — перемерить задержку до всех серверов, не дёргая подписки.
    private fun repingAll() {
        // Во время подъёма туннеля замер запрещён.
        //
        // Веер из двух сотен коннектов идёт ровно поверх рукопожатия — а на
        // узком канале рукопожатию и так тесно. Так терялись QUIC-ноды: они
        // не успевали встать, потому что мы сами же и забирали у них полосу.
        if (connState.intValue == 1) {
            log("⏳ Идёт подключение — замер отложен, он отнимает канал")
            return
        }
        if (pingBusy) { log("⏳ Замер уже идёт"); return }
        if (subsLoading.value) { log("⏳ Обновляются подписки — они перемерят сами"); return }
        pingBusy = true
        log("📡 Меряю задержку до серверов…")
        // Ручной замер — тоже замер: следующий сам по себе через полчаса.
        prefs.lastPingAll = System.currentTimeMillis()
        // Какой список мерили. Если за время замера его заменило «обновить»,
        // пересортировывать нельзя: раньше замер в конце записывал поверх
        // свежих подписок СТАРЫЙ список — обновление молча пропадало.
        val snap = nodes.toList()
        lifecycleScope.launch {
            try {
            val t = System.currentTimeMillis()
            Subscriptions.reping(snap,
                gentle = com.zen.blaster.core.NetInfo.snapshot(this@MainActivity).mobile)
            if (nodes.size != snap.size || nodes.indices.any { nodes[it] !== snap[it] }) {
                pingTick.intValue++
                log("📡 Замер закончен, но список уже обновился — перемерю при следующем замере")
                return@launch
            }
            val alive = nodes.count { it.ping in 1..899 }
            val quic = nodes.count { it.ping < 0 && it.quic() }
            // Глухие уходят в журнал поимённо: по ним потом видно, лежит ли
            // сервер вторую неделю или молчал один раз на плохой связи.
            val deaf = nodes.filter { it.ping < 0 && it.silentTries >= Node.SILENT_LIMIT }
            val revived = nodes.filter { it.ping > 0 && it.pingExact.not() && it.silentTries == 0 }
            // Пересортировка заодно поднимает быстрые наверх.
            val sorted = nodes.sortedWith(compareBy({ it.badge() },
                { if (it.ping in 1..899) it.ping else 100000 }))
            nodes.clear(); nodes.addAll(sorted)
            restoreSelection()
            prefs.cacheNodes(sorted)
            pingTick.intValue++
            log("📡 Готово за ${System.currentTimeMillis() - t} мс: " +
                "отвечают $alive, молчат $quic, мертвы ${nodes.size - alive - quic}")
            com.zen.blaster.core.Blackbox.note(
                "📡 замер задержки: отвечают $alive из ${nodes.size}" +
                (if (revived.isNotEmpty()) ", по соседнему порту ${revived.size}" else "") +
                (if (deaf.isNotEmpty())
                    ", глухих ${deaf.size}: " + deaf.take(8).joinToString(", ") {
                        "«${it.name}» ${it.addr} (${it.silentTries}-й раз" +
                        (if (it.lastOkAt > 0L) ", но работала" else "") + ")"
                    } else ""))
            } finally { pingBusy = false }
        }
    }

    // «проверка» — настоящий тест ЧЕРЕЗ поднятый туннель силами самого ядра:
    // не «открыт ли порт», а «доходит ли трафик до заблокированных сайтов».
    private fun probeActive() {
        if (connState.intValue != 2) { log("⚠️ Проверка работает на поднятом туннеле"); return }
        if (probeBusy) { log("⏳ Проверка уже идёт"); return }
        probeBusy = true
        log("🔎 Проверяю ответ сервера…")
        val awg = engine.intValue == 1
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
            val targets = listOf(
                "Cloudflare" to "http://cp.cloudflare.com/generate_204",
                "Google" to "https://www.gstatic.com/generate_204",
                "YouTube" to "https://www.youtube.com/generate_204",
                "DNS" to "https://dns.google/generate_204"
            )
            val http = okhttp3.OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
                .proxy(java.net.Proxy.NO_PROXY).build()
            // У Amnezia нет управляющего порта sing-box: раньше проверка
            // спрашивала его и рапортовала «нет ответа» по всем четырём при
            // живом туннеле. Наш собственный трафик Amnezia и так ведёт через
            // туннель — меряем прямым запросом.
            fun viaCore(url: String): Int = try {
                val enc = java.net.URLEncoder.encode(url, "UTF-8")
                val r = com.zen.blaster.core.ClashApi.req("/proxies/proxy/delay?url=$enc&timeout=8000").build()
                val body = http.newCall(r).execute().use { it.body?.string().orEmpty() }
                org.json.JSONObject(body).optInt("delay", -1)
            } catch (t: Throwable) { if (t is java.net.ConnectException) -2 else -1 }
            fun direct(url: String): Int = try {
                val t0 = System.currentTimeMillis()
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.code }
                (System.currentTimeMillis() - t0).toInt()
            } catch (_: Throwable) { -1 }
            // Все четыре РАЗОМ: по очереди с таймаутами это до 48 секунд,
            // человек успевал решить, что кнопка зависла, и нажать ещё раз.
            val res = kotlinx.coroutines.coroutineScope {
                targets.map { (name, url) ->
                    async { name to (if (awg) direct(url) else viaCore(url)) }
                }.map { it.await() }
            }
            runOnUiThread {
                if (res.all { it.second == -2 }) {
                    log("   ❌ Ядро не отвечает на управляющий порт — переподключитесь")
                } else for ((name, ms) in res) {
                    log(if (ms > 0) "   ✅ $name: $ms мс" else "   ❌ $name: нет ответа")
                    if (name == "Cloudflare" && ms > 0) Stats.feedPing(ms)
                }
            }
            } finally { probeBusy = false }
        }
    }

    // Обстановка на момент попытки — то, чего через час не восстановит никто.
    //
    // Половина разборов упирается не в «что было в логе», а в «а с какими
    // настройками это было»: с бронёй или без, через ByeDPI или мимо, на
    // Tele2 или на домашнем Wi-Fi. Пишем всё разом и заранее, до первой
    // строчки лога, — потом настройки успеют смениться.
    //
    // Ключей и UUID здесь нет намеренно: журнал уходит наружу, ноду опознаём
    // именем, адресом и подпиской.
    private fun openBlackboxSession(
        n: com.zen.blaster.model.Node?, vpnOn: Boolean, dpiOn: Boolean, forceXray: Boolean?,
        // Фактические значения этой попытки, а не то, что стоит в настройках:
        // на втором круге отката они как раз и отличаются, и записать надо то,
        // с чем реально пошли к серверу.
        chainOn: Boolean, frontOn: Boolean
    ) {
        val engineName = when {
            forceXray == true -> "Xray (откат со второго ядра)"
            forceXray == false -> "sing-box (откат со второго ядра)"
            prefs.tunnelEngine == 1 -> "sing-box (выбрано вручную)"
            prefs.tunnelEngine == 2 -> "Xray (выбрано вручную)"
            else -> "авто"
        }
        val header = mutableListOf(
            "сеть: ${com.zen.blaster.core.NetInfo.line(this)}"
        )
        if (n != null) header.add(
            "нода: «${n.name}» [${n.badge()}] ${n.addr}:${n.port}" +
                (if (n.wl) " · АВАРИЙНАЯ (белый список)" else "") +
                (if (n.sni.isNotEmpty() || n.host.isNotEmpty())
                    " · имя ${n.sni.ifEmpty { n.host }}" else "") +
                (if (n.sub.isNotEmpty()) " · из «${n.sub}»" else "") +
                (if (n.ping in 1..899) " · пинг ${n.ping} мс" else "")
        )
        else header.add("нода: не нужна, поднимаем только DPI-щит")
        header.add("ядро: $engineName · VPN ${if (vpnOn) "вкл" else "выкл"} · " +
            "DPI-щит ${if (dpiOn) "вкл" else "выкл"}" +
            (if (chainOn) " · нода идёт ЧЕРЕЗ ByeDPI"
             else if (dpiOn && prefs.chainVpnViaDpi) " · связка со щитом СНЯТА (откат)" else ""))
        header.add("режимы: броня ${if (prefs.armor) "вкл" else "выкл"} · " +
            "kill switch ${if (prefs.killSwitch) "вкл" else "выкл"} · " +
            "обход РУ ${if (prefs.geoRouting) "вкл (${prefs.splitApps().size} прил.)" else "выкл"} · " +
            "прокси ${if (prefs.proxyMode) "вкл" else "выкл"}")
        val dnsIn = com.zen.blaster.core.DnsChoice.tunnelName(prefs.dnsProvider, prefs.dnsCustom)
        val dnsPre = com.zen.blaster.core.DnsChoice.preName(prefs.dnsProvider, prefs.dnsCustom)
        header.add("DNS: $dnsIn" + (if (dnsIn != dnsPre) " · до туннеля $dnsPre" else "") +
            (if (frontOn) " · подмена имени на ${prefs.whitelistDomain}"
             else if (prefs.whitelistMode) " · подмена имени СНЯТА (откат либо ноде она не годится)"
             else ""))
        // ЧТО ПРОШИВКА ДЕЛАЕТ С НАМИ В ФОНЕ.
        //
        // Телефон оператора — Redmi, а MIUI поверх штатного Doze морозит
        // фоновые приложения целиком, вместе с ядром. Тогда рвётся не сторож,
        // а сама сессия туннеля — и в журнале это неотличимо от отказа ноды.
        // Happ на этот случай прямо просит поставить фону «Без ограничений»;
        // мы хотя бы записываем, в каких условиях работали.
        powerNote()?.let { header.add(it) }
        com.zen.blaster.core.Blackbox.session(
            n?.let { "«${it.name}» [${it.badge()}] ${it.addr}:${it.port}" } ?: "DPI-щит", header)
    }

    // Душит ли нас система в фоне. null — всё в порядке, писать нечего.
    private fun powerNote(): String? = try {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        val dozed = !pm.isIgnoringBatteryOptimizations(packageName)
        val restricted = android.os.Build.VERSION.SDK_INT >= 28 && am.isBackgroundRestricted
        when {
            restricted -> "ФОН ОГРАНИЧЕН прошивкой — система вправе заморозить ядро в любой момент"
            dozed -> "экономия батареи для нас включена — в глубоком сне туннель может замереть"
            else -> null
        }
    } catch (_: Throwable) { null }

    // ------------------------------------------------- разгон Hysteria2
    //
    //  Заявленные скорости переключают Hysteria2 с BBR на Brutal: ядро держит
    //  темп и не сбавляет его на каждой потере пакета. На нормальном канале
    //  это и есть разница между «еле ползёт» и настоящей скоростью.
    //
    //  Но заявлять надо ПРАВДУ. В настройках стоит 100↓/20↑ и стоит у всех по
    //  умолчанию, а на EDGE реальный канал — сотни килобит. Brutal в этом
    //  случае честно пытается держать сто мегабит: шлёт вслепую, теряет почти
    //  всё, шлёт заново — и топит сам себя. Именно это и значит жалоба
    //  «hysteria-ноды подыхают на H, H+ и E, а в Happ и Karing работают»:
    //  у них Brutal по умолчанию не включён вовсе.
    //
    //  Поэтому: на медленном мобильном канале Brutal не заявляем совсем —
    //  BBR сам нащупает потолок. На обычной сотовой связи заявляем не больше
    //  того, что система намерила. На Wi-Fi и хорошем LTE — как настроено.
    private fun brutal(): Pair<Int, Int> {
        if (!prefs.hyBoost) return 0 to 0
        val want = prefs.hyDownMbps to prefs.hyUpMbps
        if (com.zen.blaster.core.NetInfo.slowMobile(this)) return 0 to 0
        val kbps = com.zen.blaster.core.NetInfo.downKbps(this)
        if (kbps <= 0) return want                    // система не знает — не выдумываем
        // Потолок ставим и на проводе тоже. Заявить 100 Мбит/с на домашнем
        // канале в 68 — это всё та же ложь ядру, просто не смертельная:
        // Brutal будет давить сверх того, что канал уносит, и стабильно
        // терять хвост. Берём с запасом вниз, потому что переоценить канал
        // в Brutal опаснее, чем недооценить.
        val cap = (kbps * 4 / 5 / 1000).coerceAtLeast(1)
        return if (want.first <= cap) want
               else cap to (want.second * cap / want.first).coerceAtLeast(1)
    }

    // forceXray: null — выбрать ядро самим, true/false — поднимать заданным.
    // Второе нужно откату: когда одно ядро не справилось, вторую попытку
    // делаем ЯВНО другим, а не гадаем заново по тем же признакам.
    // noFront: не подменять имя, даже если режим подмены включён. Это второй
    // вид отката — см. сторож в конце функции.
    private fun toggleConnect(force: Boolean, forceXray: Boolean? = null,
                              noFront: Boolean = false, noChain: Boolean = false) {
        if (engine.intValue == 1) { connectAwg(); return }

        val vpnOn = prefs.vpnEnabled
        val dpiOn = prefs.dpiEnabled
        if (!vpnOn && !dpiOn) { log("❌ Включите VPN или DPI-щит в настройках"); return }

        // Сервер нужен только для VPN-режима; «только DPI» работает без него.
        //
        // Ноду, у которой сервер предъявляет чужое имя, отдаём ядру со снятой
        // проверкой внешнего TLS: иначе она не встанет никогда. Выяснили это
        // на прошлой попытке — по жалобе ядра «certificate is valid for …», —
        // и человеку тогда же объяснили. Здесь просто применяем.
        val n = nodes.getOrNull(selected.intValue)?.let { node ->
            if (traitKey(node) in prefs.certMismatch() && !node.insecure)
                node.copy(insecure = true) else node
        }
        if (vpnOn && n == null) { log("❌ Сервер не выбран"); return }
        if (vpnOn && n != null && !n.supported()) {
            log("❌ ${n.badge()}: транспорт ${n.transport} не поддерживается ни одним из ядер.")
            connState.intValue = 0
            return
        }

        connState.intValue = 1
        // Данные для уведомления в шторке.
        Stats.server.value = if (vpnOn && n != null) n.name else "DPI-щит"
        Stats.mode.value = when {
            vpnOn && dpiOn -> "VPN + DPI"
            vpnOn -> n?.badge() ?: "VPN"
            else -> "ByeDPI"
        }
        // Транспорты, которые sing-box не умеет (XHTTP, kcp) → поднимаем Xray,
        // а sing-box ходит к нему через локальный SOCKS. Выбор ядра автоматический.
        // Кто поднимает ноду в режиме «Авто».
        //
        // Раньше: только те транспорты, что sing-box вовсе не умеет (XHTTP,
        // kcp). Остальное шло в sing-box — а у него на ClientHello нет ничего,
        // кроме отпечатка uTLS. Обход в такой связке целиком лежал на ByeDPI,
        // и стоило его выключить, как VPN переставал работать. Зависимость,
        // которой быть не должно: это две независимые функции.
        //
        // Теперь при включённой «Броне» ноду, которую умеют оба ядра, отдаём
        // Xray: у него есть нарезка приветствия и шум перед рукопожатием, то
        // есть VPN обходит блокировку САМ, без чужой помощи.
        // Ядро выбираем ПО ВОЗМОЖНОСТЯМ ТРАНСПОРТА, и только по ним.
        //
        // Была попытка переводить на Xray все ноды при включённой «Броне» —
        // чтобы VPN обходил блокировку сам, своей нарезкой приветствия, а не
        // чужим щитом. Замысел верный, исполнение — нет: на живой сети
        // 14.08.2026 это сменило ядро у нод, которые прекрасно работали через
        // sing-box, и перестали работать ВСЕ разом. Менять рабочее ради
        // красивой идеи нельзя, поэтому откат к прежнему правилу.
        val autoXray = vpnOn && n != null && XrayEngine.handles(n) && XrayEngine.available(this)
        val pinned = prefs.tunnelEngine          // 0 авто, 1 sing-box, 2 Xray
        val viaXray = when {
            forceXray != null -> forceXray && XrayEngine.available(this)  // это откат
            pinned == 1 -> false
            pinned == 2 -> XrayEngine.available(this)
            else -> autoXray
        }
        // Выбранное руками ядро может просто не уметь эту ноду. Молчать об
        // этом нельзя: со стороны будет «нода не поднимается», и человек
        // пойдёт менять сервер вместо того, чтобы вернуть «Авто».
        if (pinned == 1 && autoXray && n != null)
            log("⚠️ Выбран sing-box, но транспорт ${n.transport.uppercase()} умеет только Xray — " +
                "скорее всего не поднимется. Верните «Авто».")
        if (pinned == 2 && !XrayEngine.available(this))
            log("⚠️ Выбран Xray, но его бинарник недоступен — иду через sing-box")
        val fp = if (prefs.armor) ConfigBuilder.pickFingerprint() else ""
        // Режим белых списков: прикрываемся разрешённым доменом.
        // У аварийной ноды прикрытие включается САМО: она для того и заведена,
        // держать ради неё ещё и глобальный переключатель — лишний шаг в момент,
        // когда всё уже горит.
        // Нода, которая УЖЕ маскируется под разрешённый домен, — лучший случай:
        // её нельзя отличить от похода на VK в принципе. Такую не трогаем вовсе.
        // Подменяем имя ТОЛЬКО по явной галке.
        //
        // Раньше это включалось само на всей аварийной группе — и ломало ноды,
        // которые и без того настроены правильно: чужое имя в SNI сервер
        // попросту не обслуживает, и туннель молча не отвечал. Подмена работает
        // лишь там, где сервер согласен на любое имя; это редкость и требует
        // осознанного решения, а не режима по умолчанию.
        val front = when {
            n?.whitelistNative() == true -> ""      // уже маскируется, трогать нечего
            // АВАРИЙНЫМ НОДАМ ИМЯ НЕ ПОДМЕНЯЕМ. НИКОГДА.
            //
            // Они уже настроены провайдером под обход: живут за российским CDN
            // и ходят на СВОЙ домен, под который выписан сертификат. Подмена
            // на наш «разрешённый» домен ломает их наглухо — Xray идёт на
            // sun9-1.userapi.com, получает сертификат VK и падает с
            // «peer cert is invalid». Именно это и уронило разом все три
            // обходные ноды 14.08.2026.
            //
            // Наш список прикрытия (VK, Яндекс) — для нод, у которых своего
            // прикрытия НЕТ. У аварийных оно есть, и оно лучше нашего.
            n?.wl == true -> ""
            // Вторая попытка после отказа: идём к серверу с его собственным
            // именем. Подмена работает только там, где сервер согласен на
            // любое имя, а это редкость — см. сторож ниже.
            noFront -> ""
            // Эта нода уже показала, что подмена ей вредит. Не наступаем
            // второй раз: сервер ходит на своё имя и наше отвергает.
            n != null && traitKey(n) in prefs.frontHostile() -> ""
            prefs.whitelistMode -> prefs.whitelistDomain.trim()
            else -> ""
        }
        // Пускать ли ноду через ByeDPI. Это две независимые функции, и связка
        // помогает не всем: ByeDPI режет приветствие на куски, а часть серверов
        // (и почти всё, что стоит за CDN) на такое приветствие просто не
        // отвечает. Поэтому при отказе пробуем ещё раз напрямую — см. сторож.
        val chainOn = prefs.chainVpnViaDpi && dpiOn && !noChain &&
            // Эта нода уже показала, что через щит не встаёт.
            !(n != null && traitKey(n) in prefs.chainHostile())
        // Запись в журнал открываем здесь: теперь известны ВСЕ решения этой
        // попытки — ядро, подмена имени, связка со щитом.
        openBlackboxSession(n, vpnOn, dpiOn, forceXray, chainOn, front.isNotEmpty())
        when {
            n?.whitelistNative() == true ->
                log("⚪ Нода уже маскируется под ${n.sni.ifEmpty { n.host }} — беру как есть")
            n?.wl == true && front.isEmpty() ->
                log("⚪ Аварийная нода: консервативный профиль, имя не подменяю")
            front.isEmpty() -> {}
            n?.frontable() != true ->
                log("⚠️ ${n?.badge()}: имя подменить нельзя (Reality или без TLS) — иду как есть")
            else -> log("⚪ Подменяю имя на $front — сработает, если сервер принимает любое")
        }
        pendingXray = if (viaXray) {
            log("🧩 Транспорт ${n!!.transport.uppercase()} → ядро Xray")
            // Аварийную ноду через ByeDPI не пускаем: он корёжит то самое
            // приветствие, которое мы делаем похожим на обычный поход на сайт.
            // На стороне sing-box это уже так — приводим Xray к тому же.
            XrayEngine.buildConfig(n, prefs.armor, fp,
                viaDpi = chainOn && !n.wl, frontDomain = front).toString()
        } else null

        val proxyMode = prefs.proxyMode
        if (proxyMode) Stats.mode.value = "Прокси 127.0.0.1:${Prefs.PROXY_PORT}"
        val config = ConfigBuilder.build(
            n, prefs.geoRouting, prefs.ipv6, vpnOn, dpiOn, prefs.geoDir(), prefs.armor, viaXray,
            proxyMode, front,
            hyDown = brutal().first,
            hyUp = brutal().second,
            slowLink = com.zen.blaster.core.NetInfo.slowMobile(this),
            // Приложения мимо туннеля — только при включённом обходе РУ:
            // один переключатель на обе половины, сайты и приложения.
            bypassApps = if (prefs.geoRouting) prefs.splitApps() else emptySet(),
            lanProxy = prefs.lanProxy, lanUser = prefs.lanUser, lanPass = prefs.lanPass,
            dohUrl = prefs.dnsUrl(), directDohUrl = prefs.preDnsUrl(), chainViaDpi = chainOn
        ).toString()
        if (n?.type == "Hysteria2") {
            val (bd, bu) = brutal()
            if (bd > 0) log("⚡ Hysteria2 в режиме Brutal: $bd↓ / $bu↑ Мбит/с")
            else log("🌊 Hysteria2 на BBR — канал слишком узкий для заявленного темпа")
        }
        if (prefs.armor) log("🛡 Броня: отпечаток ${if (viaXray) fp else ConfigBuilder.lastFingerprint}, " +
            "padding, xudp, IPv6 off" + if (dpiOn) ", фрагментация TLS (ByeDPI)" else "")
        // В режиме прокси системный VPN не поднимается — согласие не нужно.
        val prep = if (proxyMode) null else VpnService.prepare(this)
        if (prep != null) { pendingConfig = config; vpnPermission.launch(prep) }
        else startTunnel(config)

        // Откат на второе ядро — в ОБЕ стороны.
        //
        // Нода, которую умеют оба ядра, не обязана подняться у обоих: они
        // расходятся в мелочах TLS-приветствия, и сервер бывает согласен
        // ровно с одним. Раньше отказ выбранного ядра выглядел как «нода
        // мёртвая», хотя вторым она поднималась без вопросов.
        //
        // Пробуем один раз и молча: forceXray != null означает, что это уже
        // и есть вторая попытка — по кругу не ходим.
        // Сторож подключения: откат на второе ядро и разбор, почему не вышло.
        if (vpnOn && n != null) {
            val canFallback = forceXray == null && pinned == 0 &&
                XrayEngine.bothPossible(n) && XrayEngine.available(this)
            val gen = ++connectAttempt
            // СКОЛЬКО ЖДАТЬ, ПРЕЖДЕ ЧЕМ РЕШИТЬ, ЧТО НЕ ВЫШЛО.
            //
            // Раньше стояло 18 секунд на всё подряд — и это убивало ровно те
            // ноды, ради которых всё затевалось. Обходные ноды (XHTTP через
            // CDN, «Обход LTE» у Capybara) поднимаются медленно по своей
            // природе: сессия через CDN плюс шум перед рукопожатием. Сам
            // провайдер честно пишет в подписке: «АВТО ОБХОД подключается за
            // 15-30 секунд». Мы обрывали такую ноду на 18-й секунде и
            // начинали заново — то есть не давали подняться вообще никогда.
            //
            // Поэтому ждём по типу транспорта, а не одинаково.
            //
            // И растягиваем по каналу. Двадцать секунд — это про домашний
            // Wi-Fi; на сотовой связи столько уходит на одно рукопожатие
            // QUIC с полной цепочкой сертификатов, а на узком канале и того
            // больше. За день живых тестов «не поднялось за 20 с» набралось
            // тринадцать штук — и это не ноды не поднимались, это мы не
            // дожидались.
            val pat = com.zen.blaster.core.NetInfo.patience(this@MainActivity)
            val waitMs = when {
                n.transport.lowercase() in setOf("xhttp", "splithttp") -> 45_000L
                n.wl -> 40_000L          // аварийные — тоже через прикрытие
                else -> 20_000L
            } * (if (pat > 2) 2 else 1)  // на заведомо медленном канале — вдвое
            lifecycleScope.launch {
                kotlinx.coroutines.delay(waitMs)
                if (gen != connectAttempt) return@launch      // уже другое подключение
                if (connState.intValue == 2) return@launch    // поднялось, всё хорошо

                // ПЕРВЫЙ ОТКАТ — СНЯТЬ ПОДМЕНУ ИМЕНИ.
                //
                // Подмена помогает ровно в одном случае: сервер согласен
                // обслуживать любое имя. Таких мало. У всех остальных чужое
                // имя в приветствии ломает рукопожатие наглухо: сервер либо
                // не отдаёт нужный сертификат, либо просто не отвечает — а со
                // стороны это выглядит как «сервер сдох». Именно так глобально
                // включённый режим белых списков положил разом десятки живых
                // нод: TCP до них доходил за 50 мс, сеть пропускала всё, а
                // туннель не вставал ни одним ядром.
                //
                // Поэтому первым делом пробуем то же самое, но с родным именем
                // сервера, и только потом идём менять ядро.
                if (front.isNotEmpty() && n.frontable()) {
                    log("⚠️ Серверу, похоже, не нравится подменённое имя — пробую с его собственным")
                    com.zen.blaster.core.Blackbox.outcome(false,
                        "не поднялось за ${waitMs / 1000} с с подменой имени на $front — " +
                        "повторяю без подмены")
                    stopTunnel()
                    kotlinx.coroutines.delay(900)
                    connState.intValue = 1
                    frontFailedFor = keyOf(n)
                    toggleConnect(force = true, forceXray = forceXray,
                        noFront = true, noChain = noChain)
                } else if (chainOn && !n.wl && n.type != "Hysteria2" && n.type != "TUIC") {
                    // ВТОРОЙ ОТКАТ — УБРАТЬ ByeDPI ИЗ ЦЕПОЧКИ.
                    //
                    // Щит и VPN — две независимые функции, и сцепка их в одну
                    // трубу помогает не всегда: ByeDPI нарезает приветствие, а
                    // серверы за CDN и часть панелей на такое не отвечают
                    // вовсе. Симптом обманчив — «не поднимается ни одна нода»,
                    // хотя каждая из них прекрасно встаёт напрямую. Сам щит
                    // при этом остаётся включённым и продолжает работать для
                    // обычного трафика: мы убираем не его, а только связку.
                    log("⚠️ Через ByeDPI нода не встала — пробую напрямую (щит остаётся включённым)")
                    com.zen.blaster.core.Blackbox.outcome(false,
                        "не поднялось за ${waitMs / 1000} с в связке с ByeDPI — " +
                        "повторяю без связки")
                    stopTunnel()
                    kotlinx.coroutines.delay(900)
                    connState.intValue = 1
                    chainFailedFor = keyOf(n)
                    toggleConnect(force = true, forceXray = forceXray,
                        noFront = noFront, noChain = true)
                } else if (canFallback) {
                    val other = !viaXray
                    log("↔ ${if (viaXray) "Xray" else "sing-box"} не поднял ноду — пробую " +
                        (if (other) "Xray" else "sing-box"))
                    // Записываем исход ДО stopTunnel: иначе остановка закроет
                    // сеанс как «прервано» и в журнале пропадёт причина отката.
                    com.zen.blaster.core.Blackbox.outcome(false,
                        "${if (viaXray) "Xray" else "sing-box"} не отозвался за " +
                        "${waitMs / 1000} с — перехожу на второе ядро")
                    stopTunnel()
                    kotlinx.coroutines.delay(900)
                    connState.intValue = 1
                    toggleConnect(force = true, forceXray = other,
                        noFront = noFront, noChain = noChain)
                } else {
                    // Второе ядро уже пробовали (или пробовать нечем) — значит
                    // дело может быть не в ноде. Спрашиваем саму сеть.
                    com.zen.blaster.core.Blackbox.outcome(false,
                        "не поднялось за ${waitMs / 1000} с, второго ядра для этой ноды нет")
                    sniffRegime()
                }
            }
        }
    }

    private fun applyDns(sel: Int, custom: String) {
        prefs.dnsProvider = sel
        prefs.dnsCustom = custom
        com.zen.blaster.core.Resolver.preferred = prefs.preDnsUrl()
        // Показываем оба: они теперь могут отличаться, и человек должен
        // видеть, чем именно разрешается адрес ноды, а чем — сайты.
        val inTun = com.zen.blaster.core.DnsChoice.tunnelName(sel, custom)
        val preTun = com.zen.blaster.core.DnsChoice.preName(sel, custom)
        log(if (inTun == preTun) "🌐 Резолвер: $inTun"
            else "🌐 Резолвер: в туннеле $inTun · до туннеля $preTun")
        if (connState.intValue != 0) {
            log("🔄 Смена DNS — переподключаюсь…")
            stopTunnel()
            lifecycleScope.launch { kotlinx.coroutines.delay(900); toggleConnect(force = true) }
        }
    }

    // Кто из резолверов сегодня жив. Меряем реальным DNS-запросом, а не
    // пингом: пинг может проходить там, где DoH уже прикрыт.
    private fun checkDns() {
        lifecycleScope.launch {
            log("🌐 Проверяю резолверы…")
            val all = com.zen.blaster.core.DnsChoice.LIST.map { it.name to it.url } +
                (if (prefs.dnsCustom.isNotBlank()) listOf("свой" to prefs.dnsCustom) else emptyList())
            for ((name, url) in all) {
                val ms = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val t = System.currentTimeMillis()
                    val ok = runCatching {
                        com.zen.blaster.core.Resolver.probe(url)
                    }.getOrDefault(false)
                    if (ok) System.currentTimeMillis() - t else -1L
                }
                log(if (ms >= 0) "   ✔ $name — $ms мс" else "   ✖ $name — не отвечает")
            }
        }
    }

    // Разбор: сегодня вообще можно наружу или включили белые списки?
    //
    // Вопрос не праздный. В такой день не поднимается НИ ОДНА обычная нода, и
    // человек уходит менять серверы по кругу, хотя менять надо не сервер, а
    // способ. Не гадаем по симптомам — стучимся напрямую в разрешённые адреса
    // и в заграничные и смотрим, что открылось.
    private fun sniffRegime() {
        lifecycleScope.launch {
            log("🔎 Проверяю, что сегодня пропускает сеть…")
            val v = runCatching { com.zen.blaster.core.WhitelistSniff.probe() }.getOrNull()
            if (v == null) { log("… проверку сети выполнить не удалось"); return@launch }
            val wl = nodes.filter { it.wl }
            // Вердикт кладём в журнал отдельной строкой и всегда, даже когда
            // человеку показывать нечего: «в тот вечер сеть пропускала всё» —
            // это ответ на вопрос, виновата нода или оператор.
            com.zen.blaster.core.Blackbox.note(
                "🧾 опрос сети: разрешённые ${v.allowedOk}/3, заграничные ${v.neutralOk}/3 → " +
                v.verdictText() + " · " + com.zen.blaster.core.NetInfo.line(this@MainActivity))
            val lines = com.zen.blaster.core.WhitelistSniff.explain(
                v, wl.size, wl.count { it.frontable() })
            if (lines.isEmpty()) {
                log("   Сеть пропускает и разрешённое, и заграничное " +
                    "(${v.allowedOk}/${v.neutralOk}) — дело в самой ноде, попробуйте другую.")
            } else lines.forEach { log(it) }

            // ПЕРЕБОР НЕ КОНЧАЕТСЯ, ПОКА ЧЕЛОВЕК НЕ СКАЗАЛ «ХВАТИТ».
            //
            // Опрос сети был последним шагом каскада: дальше клиент просто
            // замолкал, и человек оставался с закрытым трафиком и без связи,
            // гадая, чего ждать. Ждать было нечего — никто больше не пробовал.
            // Теперь после паузы заходим на новый круг: сеть за это время
            // могла отпустить, оператор — снять глушение, сервер — вернуться.
            //
            // Раньше каждый новый круг брал ТУ ЖЕ ноду через 8 секунд — и так
            // по 38 кругов за день (14.10.2026), пока рядом стояла нода, которая
            // на этом же операторе поднималась. Теперь круг зависит от вердикта:
            //  • сети нет / рывками — нода ни при чём: не перебираем, а ждём
            //    с растущей паузой и встряхиваем радио (приём из keepmts.sh);
            //  • сеть живая — переходим на следующую ноду, сначала на те, что
            //    поднимались на этом операторе.
            if (connState.intValue != 0) {
                nodes.getOrNull(selected.intValue)?.let { cascadeTried += traitKey(it) }
                if (v.noNetwork || v.spotty) {
                    cascadeIdle++
                    val pause = (8_000L shl cascadeIdle.coerceAtMost(3)).coerceAtMost(60_000L)
                    log("↻ Сеть сама не держит — жду ${pause / 1000} с и пробую снова (остановить — кнопкой).")
                    com.zen.blaster.core.RadioKick.kick(this@MainActivity, "сеть молчит") { m -> runOnUiThread { log(m) } }
                    com.zen.blaster.core.Blackbox.note("↻ каскад: сеть не держит, пауза ${pause / 1000} с")
                    kotlinx.coroutines.delay(pause)
                } else {
                    cascadeIdle = 0
                    val next = nextCascadeNode(v.whitelist)
                    val cur = nodes.getOrNull(selected.intValue)
                    if (next != null && next != selected.intValue) {
                        val nn = nodes[next]
                        val why = if (prefs.carrierWins(com.zen.blaster.core.NetInfo.carrierKey(this@MainActivity))
                                .containsKey(traitKey(nn))) "она поднималась на этом операторе"
                            else if (nn.ping > 0) "пинг ${nn.ping} мс" else "следующая по списку"
                        log("↪ «${cur?.name}» не встаёт при живой сети — перехожу на «${nn.name}» ($why)")
                        com.zen.blaster.core.Blackbox.note("↪ каскад: «${cur?.name}» → «${nn.name}» ($why)")
                        selected.intValue = next
                        prefs.selectedServer = next
                    } else {
                        log("↻ Захожу на новый круг — остановить можно кнопкой.")
                        com.zen.blaster.core.Blackbox.note("↻ каскад пройден, начинаю заново")
                    }
                    kotlinx.coroutines.delay(3_000)
                }
                if (connState.intValue != 0) toggleConnect(force = true)
            }
        }
    }

    // ------------------------------------------------------- режим Amnezia

    private fun connectAwg() {
        val p = awgProfiles.getOrNull(selectedAwg.intValue)
        if (p == null) { log("❌ Нет профилей Amnezia — импортируйте .conf или vpn://"); return }
        connState.intValue = 1
        Stats.server.value = p.name
        Stats.mode.value = p.badge()
        val json = p.toJson().toString()
        val prep = VpnService.prepare(this)
        if (prep != null) { pendingAwg = json; pendingConfig = null; vpnPermission.launch(prep) }
        else startAwg(json)
    }

    private fun startAwg(json: String) {
        prefs.lastAwg = json
        val i = Intent(this, ZenAwgService::class.java).apply {
            action = ZenAwgService.ACTION_START
            putExtra(ZenAwgService.EXTRA_PROFILE, json)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, i)
    }

    private fun stopAwg() {
        ZenAwgService.stopAll(this)
        connState.intValue = 0
    }

    // Смена профиля Amnezia на лету.
    private fun switchAwg(index: Int) {
        selectedAwg.intValue = index
        prefs.selectedAwg = index
        if (connState.intValue == 0) return
        log("🔄 Переключаюсь на ${awgProfiles.getOrNull(index)?.name ?: "профиль"}…")
        connState.intValue = 1
        stopAwg()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(900)
            connState.intValue = 1
            connectAwg()
        }
    }

    private fun startTunnel(config: String) {
        // Сохраняем, чтобы система могла поднять туннель сама (always-on).
        prefs.lastConfig = config
        prefs.lastDpi = prefs.dpiEnabled
        prefs.lastXray = pendingXray ?: ""
        prefs.lastServerName = Stats.server.value
        val i = Intent(this, ZenVpnService::class.java).apply {
            action = ZenVpnService.ACTION_START
            putExtra(ZenVpnService.EXTRA_CONFIG, config)
            putExtra(ZenVpnService.EXTRA_DPI, prefs.dpiEnabled)
            putExtra(ZenVpnService.EXTRA_DPI_STRATEGY, prefs.dpiStrategy)
            pendingXray?.let { putExtra(ZenVpnService.EXTRA_XRAY, it) }
        }
        androidx.core.content.ContextCompat.startForegroundService(this, i)
    }

    // Держим lastConfig в согласии с текущим выбором.
    //
    // Плитка в шторке поднимает туннель по prefs.lastConfig, а он записывался
    // ТОЛЬКО в момент подключения из приложения. Стоило выбрать другой сервер
    // и не нажать «подключить» — и плитка молча уводила на прежнюю ноду.
    // Со стороны это выглядело так: жмёшь плитку, а сервер «вообще другой».
    // Пересобираем конфиг сразу при смене выбора, не дожидаясь подключения.
    private fun refreshLastConfig() {
        val n = nodes.getOrNull(selected.intValue) ?: return
        if (!n.supported() || !prefs.vpnEnabled) return
        try {
            val dpiOn = prefs.dpiEnabled
            val viaXray = XrayEngine.handles(n) && XrayEngine.available(this)
            val fp = if (prefs.armor) ConfigBuilder.pickFingerprint() else ""
            val front = when {
                n.whitelistNative() -> ""
                prefs.whitelistMode -> prefs.whitelistDomain.trim()
                else -> ""
            }
            prefs.lastXray = if (viaXray)
                XrayEngine.buildConfig(n, prefs.armor, fp,
                    viaDpi = prefs.chainVpnViaDpi && dpiOn && !n.wl, frontDomain = front).toString()
            else ""
            prefs.lastConfig = ConfigBuilder.build(
                n, prefs.geoRouting, prefs.ipv6, true, dpiOn, prefs.geoDir(), prefs.armor, viaXray,
                prefs.proxyMode, front,
                hyDown = brutal().first,
                hyUp = brutal().second,
                slowLink = com.zen.blaster.core.NetInfo.slowMobile(this),
                bypassApps = if (prefs.geoRouting) prefs.splitApps() else emptySet(),
                lanProxy = prefs.lanProxy, lanUser = prefs.lanUser, lanPass = prefs.lanPass,
                dohUrl = prefs.dnsUrl(), directDohUrl = prefs.preDnsUrl(),
                chainViaDpi = prefs.chainVpnViaDpi && dpiOn
            ).toString()
            prefs.lastDpi = dpiOn
            prefs.lastServerName = n.name
        } catch (t: Throwable) {
            // Не смогли — оставляем прежний конфиг: он хотя бы рабочий.
            log("⚠️ Не удалось заранее собрать конфиг для плитки: ${t.message}")
        }
    }

    // Смена сервера на лету: гасим туннель и поднимаем заново на новой ноде.
    private fun switchServer(index: Int) {
        selected.intValue = index
        prefs.selectedServer = index
        nodes.getOrNull(index)?.let { prefs.selectedKey = keyOf(it) }
        val n = nodes.getOrNull(index)
        if (n != null && !n.supported()) {
            log("⚠️ ${n.badge()} не поддерживается ядром — выберите другой сервер")
            return
        }
        if (connState.intValue == 0) {
            refreshLastConfig()      // чтобы плитка подняла ИМЕННО этот сервер
            return                   // просто выбор, не подключены
        }
        log("🔄 Переключаюсь на ${n?.name ?: "сервер"}…")
        connState.intValue = 1
        stopTunnel()
        // даём сервису корректно закрыться, затем поднимаем заново
        lifecycleScope.launch {
            kotlinx.coroutines.delay(900)
            connState.intValue = 1
            toggleConnect(force = true)
        }
    }

    // Гасим ядро sing-box всегда, а ядро Amnezia — только если оно вообще
    // запускалось: startService в чужой процесс поднимает его на ровном месте.
    private fun stopTunnel() {
        startService(Intent(this, ZenVpnService::class.java).apply { action = ZenVpnService.ACTION_STOP })
        if (engine.intValue == 1 || prefs.lastAwg.isNotEmpty())
            ZenAwgService.stopAll(this)
        connState.intValue = 0
    }

    // ------------------------------------------------------------- UI

    @Composable
    private fun HomeScreen() {
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(
            containerColor = ZenBg,
            topBar = { TopBar() },
            bottomBar = {
                // Навбар как у Amnezia: только иконки, без подписей, тонкая линия сверху.
                Column {
                    HorizontalDivider(color = ZenDivider, thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth().background(ZenBg).padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavIcon(Icons.Outlined.Home, tab == 0) { tab = 0 }
                        NavIcon(Icons.Outlined.Settings, tab == 1) { tab = 1 }
                        NavIcon(Icons.Outlined.Description, tab == 2) { tab = 2 }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(ZenBg)) {
                when (tab) { 0 -> HomeTab(); 1 -> SettingsTab(); 2 -> LogsTab() }
            }
        }
        if (showPicker.value) ServerPicker()
        if (showTitleDlg.value) TitleDialog()
        if (showAppPicker.value) AppPicker()
        if (showModesDlg.value) ModesDialog()
        if (showJournal.value) JournalDialog()
        if (showLeaks.value) LeaksDialog()
    }

    // ПРОВЕРКА УТЕЧЕК: что о вас видно снаружи.
    //
    // Люди справедливо не верят на слово: выбрал Финляндию, а сайты пишут
    // «Москва». Разбираться приходилось вслепую, потому что источников у
    // такой надписи несколько, и только часть из них вообще связана с VPN.
    // Этот экран показывает все разом и честно говорит, что чинится, а что
    // нет.
    @Composable
    private fun LeaksDialog() {
        var lines by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var busy by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            busy = true
            val out = mutableListOf<Pair<String, String>>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val viaTun = if (connState.intValue == 2)
                    com.zen.blaster.core.LocalProbe.where(viaTunnel = true) else null
                val direct = com.zen.blaster.core.LocalProbe.where(viaTunnel = false)

                out += "Через туннель" to (viaTun?.let { "${it.short()} · ${it.ip}" }
                    ?: if (connState.intValue == 2) "не ответил" else "туннель не поднят")
                out += "Напрямую (без VPN)" to (direct?.let { "${it.short()} · ${it.ip}" }
                    ?: "не ответил")

                // Совпадение адресов означает, что трафик идёт мимо туннеля —
                // это и есть настоящая утечка, всё остальное ниже к VPN
                // отношения не имеет.
                if (viaTun != null && direct != null)
                    out += "Итог" to (if (viaTun.ip == direct.ip)
                        "⚠️ УТЕЧКА: адрес одинаковый, трафик идёт мимо туннеля"
                    else "✅ адреса разные — трафик идёт через туннель")

                // Резолверов теперь два, и в разговоре об утечках это как раз
                // существенно: имена сайтов разрешает первый, адрес ноды —
                // второй, и видят их разные люди.
                val rIn = com.zen.blaster.core.DnsChoice.tunnelName(prefs.dnsProvider, prefs.dnsCustom)
                val rPre = com.zen.blaster.core.DnsChoice.preName(prefs.dnsProvider, prefs.dnsCustom)
                out += "Резолвер в туннеле" to rIn
                if (rIn != rPre) out += "Резолвер до туннеля" to rPre
                out += "Часовой пояс" to java.util.TimeZone.getDefault().id
                out += "Язык системы" to java.util.Locale.getDefault().toString()
                val tm = getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                out += "Страна SIM" to ((tm?.networkCountryIso ?: "").uppercase()
                    .ifEmpty { "нет SIM" })
            }
            lines = out
            busy = false
        }

        AlertDialog(
            onDismissRequest = { showLeaks.value = false },
            containerColor = ZenCard,
            title = { Text("Что о вас видно снаружи", color = Color.White, fontSize = 17.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (busy) Text("Спрашиваю у внешних служб…", color = ZenGray, fontSize = 13.sp)
                    lines.forEach { (k, v) ->
                        Text(k, color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(v, color = when {
                            v.startsWith("⚠️") -> ZenRed
                            v.startsWith("✅") -> ZenCyan
                            else -> Color.White
                        }, fontSize = 14.sp)
                    }
                    if (!busy) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Часовой пояс, язык и SIM видит любой сайт напрямую из системы — " +
                            "им ваш адрес не нужен. Ни один VPN этого не скрывает: " +
                            "«Москва» на сайтах чаще всего именно отсюда, а не из утечки. " +
                            "Google вдобавок помнит ваши прошлые действия — там же, под " +
                            "результатами, есть «Попробовать без персонализации».",
                            color = ZenGray, fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLeaks.value = false }) {
                    Text("Закрыть", color = ZenCyan)
                }
            }
        )
    }

    // Чёрный ящик: что с ним можно сделать руками.
    @Composable
    private fun JournalDialog() {
        val kb = com.zen.blaster.core.Blackbox.size() / 1024
        AlertDialog(
            onDismissRequest = { showJournal.value = false },
            containerColor = ZenCard,
            title = { Text("Журнал отладки", color = Color.White, fontSize = 17.sp) },
            text = {
                Column {
                    Text(
                        "Приложение само записывает каждую попытку подключения: сеть и " +
                        "оператора, ноду, ядро, режимы, ход событий и чем кончилось. " +
                        "Запись идёт на диск и переживает закрытие приложения — именно " +
                        "по ней потом видно, почему нода не поднялась.",
                        color = ZenGray, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Накоплено: $kb КБ", color = ZenCyan, fontSize = 12.sp)
                    Text(com.zen.blaster.core.Blackbox.path(), color = ZenGray, fontSize = 10.sp)
                    Spacer(Modifier.height(12.dp))
                    // Метка ставится в момент странности, а подпись к ней —
                    // одной строкой, пока не забылось.
                    OutlinedTextField(
                        value = markNote.value,
                        onValueChange = { markNote.value = it.take(120) },
                        label = { Text("что случилось (необязательно)", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    // Отступы между пунктами НЕ декоративные. Compose растягивает
                    // область касания у каждого до минимальных 48dp, и при
                    // прежних 10dp зоны наезжали друг на друга на полсантиметра:
                    // человек метил в «пометить», а попадал в «показать здесь».
                    Text("★ пометить этот момент", color = ZenCyan, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp).clickable {
                            com.zen.blaster.core.Blackbox.mark(markNote.value.trim().ifEmpty {
                                "${nodes.getOrNull(selected.intValue)?.name ?: "без сервера"} · " +
                                com.zen.blaster.core.NetInfo.line(this@MainActivity)
                            })
                            markNote.value = ""
                            showJournal.value = false
                            toast("Помечено — ищите ★★★ в журнале")
                            log("★ Момент помечен в журнале")
                        })
                    Spacer(Modifier.height(6.dp))
                    Text("показать здесь", color = ZenCyan, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                            .clickable { showJournal.value = false; dumpJournal() })
                    Spacer(Modifier.height(6.dp))
                    Text("отправить файлом", color = ZenCyan, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                            .clickable { showJournal.value = false; shareJournal() })
                    Spacer(Modifier.height(6.dp))
                    Text("стереть накопленное", color = ZenRed, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp).clickable {
                            com.zen.blaster.core.Blackbox.clear()
                            showJournal.value = false
                            log("🧹 Журнал отладки стёрт — счётчики нод обнулены")
                        })
                }
            },
            confirmButton = {
                TextButton(onClick = { showJournal.value = false }) {
                    Text("Закрыть", color = ZenGray)
                }
            }
        )
    }

    // Показать отчёт прямо на вкладке логов. Хвост, а не весь файл: на экране
    // всё равно читаются последние события, а 300 КБ в список класть незачем.
    private fun dumpJournal() {
        val r = com.zen.blaster.core.Blackbox.report(this)
        val lines = r.lines()
        val head = lines.takeWhile { !it.startsWith("── как оно шло") }
        val tail = lines.drop(head.size).takeLast(150)
        (head + tail).forEach { if (it.isNotBlank()) log(it) }
    }

    // Отдать отчёт наружу — в мессенджер, почтой, куда угодно.
    private fun shareJournal() {
        val f = com.zen.blaster.core.Blackbox.export(this)
        if (f == null) { toast("Не смог собрать отчёт"); return }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ZEN NITRO — журнал отладки")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Отправить журнал"))
        } catch (t: Throwable) {
            log("❌ Не удалось отправить журнал: ${t.message}")
        }
    }

    // Шапка: название по центру готикой, справа две рабочие кнопки.
    //
    // Обе вынесены сюда намеренно: и обновление подписок, и вставка ноды из
    // буфера раньше жили в глубине Настроек, а нужны они чаще всего именно
    // тогда, когда некогда — нода сдохла, ссылку скинули в чат, надо
    // подключиться сейчас. Высота строки 46dp — плотно, кольцо не поджимает.
    @Composable
    private fun TopBar() {
        Box(
            Modifier.fillMaxWidth().statusBarsPadding()
                .height(46.dp).padding(horizontal = 6.dp)
        ) {
            Text(
                titleText.value.ifBlank { "ZEN NITRO" },
                color = Color.White,
                fontFamily = zenTitleFont(titleFont.intValue),
                fontSize = titleSize.intValue.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                // letterSpacing в минус: готика и так узкая, а с обычным
                // трекингом название расползалось под самые кнопки.
                letterSpacing = (-0.5).sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    // Ширину ограничиваем явно: своё длинное название иначе
                    // уехало бы под кнопки справа и накрыло их собой.
                    .fillMaxWidth(0.62f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showTitleDlg.value = true },
                textAlign = TextAlign.Center
            )
            // Слева — режимы: ядро и «туннель или прокси». Раньше они висели
            // двумя рядами кнопок под кольцом и перегружали главный экран.
            Icon(
                Icons.Outlined.Tune, "режимы",
                tint = if (tunEngine.intValue != 0 || useProxy.value) ZenCyan else Color.White,
                modifier = Modifier.align(Alignment.CenterStart).size(34.dp)
                    .clip(CircleShape)
                    .clickable { showModesDlg.value = true }
                    .padding(6.dp)
            )
            Row(
                Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Обновить подписки. Крутится, пока идёт загрузка.
                val spin = rememberInfiniteTransition(label = "subs")
                val a = if (subsLoading.value)
                    spin.animateFloat(0f, 360f,
                        infiniteRepeatable(tween(900, easing = LinearEasing)),
                        label = "subsAngle").value
                else 0f
                Icon(
                    Icons.Outlined.Refresh, "обновить подписки",
                    tint = if (subsLoading.value) ZenCyan else Color.White,
                    modifier = Modifier.size(34.dp)
                        .clip(CircleShape)
                        .clickable { loadServers() }
                        .padding(6.dp)
                        .graphicsLayer { rotationZ = a }
                )
                Spacer(Modifier.width(2.dp))
                // Вставить ноду/подписку из буфера — одно нажатие, без диалогов.
                Icon(
                    Icons.Outlined.Add, "добавить из буфера",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                        .clip(CircleShape)
                        .clickable { quickAddFromClipboard() }
                        .padding(5.dp)
                )
            }
        }
    }

    // Раздельное туннелирование — одна кнопка на главном экране.
    //
    // Смысл: РУ-сайты и выбранные РУ-приложения идут МИМО туннеля своим
    // настоящим адресом, остальное остаётся в туннеле. Банк, госуслуги и
    // маркетплейс видят обычное подключение из России и не поднимают шум,
    // а выключать ради них весь VPN не нужно.
    //
    // Кнопка тут, а не в глубине настроек, потому что жмут её часто — ровно
    // в тот момент, когда некогда искать.
    // Две кнопки под кольцом: чем поднимать ноду и куда пускать РУ.
    // Обе на виду, потому что переключают их часто и обычно в спешке.
    @Composable
    private fun ModeChips() {
        // Под кольцом остаётся ОДНА строка — обход РУ. Его переключают часто
        // и на бегу, поэтому ему место на виду.
        //
        // Выбор ядра и «туннель или прокси» переехали в отдельное окно под
        // значком слева сверху: их трогают редко, а три ряда кнопок под
        // кольцом превращали главный экран в приборную панель.
        SplitRuChip()
    }

    // Окно «Режимы» — то, что раньше висело рядами под кольцом.
    @Composable
    private fun ModesDialog() {
        AlertDialog(
            onDismissRequest = { showModesDlg.value = false },
            containerColor = ZenCard,
            title = { Text("Режимы", color = Color.White, fontSize = 17.sp) },
            text = {
                Column {
                    Text("ЯДРО ТУННЕЛЯ", color = ZenRed, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        listOf(0 to "Авто", 1 to "sing-box", 2 to "Xray").forEach { (v, label) ->
                            val on = tunEngine.intValue == v
                            Surface(
                                color = if (on) ZenCyan.copy(alpha = 0.18f) else ZenBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(end = 5.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { switchEngine(v) }
                            ) {
                                Text(label, color = if (on) ZenCyan else ZenGray, fontSize = 12.sp,
                                    maxLines = 1, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp))
                            }
                        }
                    }
                    Text(
                        "Авто смотрит на транспорт ноды: XHTTP и kcp умеет только Xray, " +
                        "остальное берёт sing-box. Ручной выбор нужен там, где нода " +
                        "поднимается лишь одним из них: TLS-приветствие они собирают " +
                        "по-разному. В ручном режиме откат на второе ядро не срабатывает.",
                        color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("КАК ПОДНИМАТЬ НОДУ", color = ZenRed, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        listOf(false to "Туннель", true to "Прокси").forEach { (v, label) ->
                            val on = useProxy.value == v
                            Surface(
                                color = if (on) ZenRed.copy(alpha = 0.26f) else ZenBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(end = 5.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { switchProxyMode(v) }
                            ) {
                                Text(label, color = if (on) Color.White else ZenGray, fontSize = 12.sp,
                                    maxLines = 1, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp))
                            }
                        }
                    }
                    Text(
                        if (useProxy.value)
                            "Ядро слушает SOCKS5 и HTTP на 127.0.0.1:${Prefs.PROXY_PORT}. " +
                            "Маршруты системы не трогаются, разрешение VPN не нужно — " +
                            "через ноду идёт только тот, кто этот порт себе прописал."
                        else "Системный VPN: в туннель уходит весь трафик телефона.",
                        color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                Text("ГОТОВО", color = ZenCyan, fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { showModesDlg.value = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
        )
    }
    private fun switchEngine(v: Int) {
        if (tunEngine.intValue == v) return
        tunEngine.intValue = v
        prefs.tunnelEngine = v
        log(when (v) {
            1 -> "🧩 Ядро туннеля: только sing-box"
            2 -> "🧩 Ядро туннеля: только Xray"
            else -> "🧩 Ядро туннеля: выбираю сам по транспорту ноды"
        })
        if (connState.intValue != 0) {
            log("🔄 Смена ядра — переподключаюсь…")
            stopTunnel()
            lifecycleScope.launch { kotlinx.coroutines.delay(900); toggleConnect(force = true) }
        }
    }
    private fun switchProxyMode(v: Boolean) {
        if (useProxy.value == v) return
        useProxy.value = v
        prefs.proxyMode = v
        log(if (v) "🔌 Нода как прокси: 127.0.0.1:${Prefs.PROXY_PORT}, маршруты не трогаю"
            else "🛡 Нода как туннель: весь трафик телефона")
        if (connState.intValue != 0) {
            log("🔄 Смена режима — переподключаюсь…")
            stopTunnel()
            lifecycleScope.launch { kotlinx.coroutines.delay(900); toggleConnect(force = true) }
        }
    }

    @Composable
    private fun SplitRuChip() {
        val on = splitRu.value
        val apps = remember(on, showAppPicker.value) { prefs.splitApps().size }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (on) ZenCyan.copy(alpha = 0.16f) else ZenCard,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { toggleSplitRu(!on) }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (on) "🇷🇺" else "🌍", fontSize = 13.sp)
                    Spacer(Modifier.width(7.dp))
                    // Без хвоста «· вкл»: состояние и так читается по цвету и
                    // заливке, а на узком экране эти четыре знака выталкивали
                    // соседнюю кнопку за край — она обрезалась на полуслове.
                    Text("РУ мимо туннеля",
                        color = if (on) ZenCyan else ZenGray, fontSize = 13.sp,
                        maxLines = 1, softWrap = false)
                }
            }
            if (on) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    color = ZenCard,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .clickable { showAppPicker.value = true }
                ) {
                    Text(
                        if (apps > 0) "$apps прил." else "приложения",
                        color = ZenGray, fontSize = 12.sp, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // Переключение маршрутизации на живую.
    //
    // Правила зашиты в конфиг ядра, поэтому «на лету» их не поменять —
    // поднятый туннель надо пересобрать. Делаем это сами, чтобы человек не
    // гадал, почему переключатель щёлкнул, а ничего не изменилось.
    private fun toggleSplitRu(on: Boolean) {
        splitRu.value = on
        prefs.geoRouting = on
        if (on && prefs.splitApps().isEmpty()) {
            // Первое включение: предлагаем то, что похоже на наше, но список
            // сразу показываем — решает человек, а не догадка по имени пакета.
            lifecycleScope.launch {
                val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.zen.blaster.core.RuApps.suggest(this@MainActivity)
                }
                if (found.isNotEmpty()) {
                    prefs.setSplitApps(found)
                    log("🇷🇺 Нашёл российских приложений: ${found.size} — проверьте список")
                    toast("Отобрано приложений: ${found.size}")
                }
                showAppPicker.value = true
            }
        }
        log(if (on) "🇷🇺 РУ-сайты и выбранные приложения идут мимо туннеля"
            else "🌍 Весь трафик идёт в туннель")
        if (connState.intValue != 0) {
            log("🔄 Меняю маршрутизацию — переподключаюсь…")
            stopTunnel()
            lifecycleScope.launch { kotlinx.coroutines.delay(900); toggleConnect(force = true) }
        }
    }

    // Выбор приложений, которые ходят мимо туннеля.
    @Composable
    private fun AppPicker() {
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(prefs.splitApps()) }
        var all by remember { mutableStateOf<List<com.zen.blaster.core.RuApps.Entry>?>(null) }
        // Список установленного собирается сотнями миллисекунд — не на главном
        // потоке, иначе окно открывается рывком.
        LaunchedEffect(Unit) {
            all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.zen.blaster.core.RuApps.installed(this@MainActivity)
            }
        }
        fun save(s: Set<String>) { chosen = s; prefs.setSplitApps(s) }

        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { showAppPicker.value = false },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = ZenCard,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) {}
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.width(34.dp).height(4.dp)
                            .background(Color(0xFF3A3A3F), RoundedCornerShape(2.dp)))
                    }
                    Text("Мимо туннеля", color = Color.White, fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 20.dp))
                    Text(
                        "Эти приложения не заходят в туннель вовсе: видят настоящий адрес " +
                        "и обычную сеть. Отмечено: ${chosen.size}",
                        color = ZenGray, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text("все российские", color = ZenCyan, fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                save(chosen + (all.orEmpty().filter { it.ru }.map { it.pkg }))
                            })
                        Spacer(Modifier.width(16.dp))
                        Text("снять все", color = ZenGray, fontSize = 12.sp,
                            modifier = Modifier.clickable { save(emptySet()) })
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("поиск", color = ZenGray, fontSize = 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ZenCyan, unfocusedBorderColor = ZenDivider,
                            cursorColor = ZenCyan),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                    )

                    val list = all
                    if (list == null) {
                        Text("Читаю список приложений…", color = ZenGray, fontSize = 13.sp,
                            modifier = Modifier.padding(20.dp))
                    } else {
                        val q = query.trim().lowercase()
                        val shown = if (q.isEmpty()) list
                        else list.filter { it.label.lowercase().contains(q) || it.pkg.contains(q) }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(shown, key = { it.pkg }) { e ->
                                val on = e.pkg in chosen
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            save(if (on) chosen - e.pkg else chosen + e.pkg)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.label, color = if (on) ZenCyan else Color.White,
                                            fontSize = 14.sp, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(e.pkg + if (e.ru) "  · российское" else "",
                                            color = ZenGray, fontSize = 10.sp, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    Switch(checked = on, onCheckedChange = {
                                        save(if (on) chosen - e.pkg else chosen + e.pkg)
                                    })
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            showAppPicker.value = false
                            if (connState.intValue != 0) {
                                log("🔄 Список изменился — переподключаюсь…")
                                stopTunnel()
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(900); toggleConnect(force = true)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ZenCyan),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                    ) { Text("ГОТОВО", color = Color.Black) }
                }
            }
        }
    }

    // Настройка надписи сверху — по тапу прямо по ней.
    //
    // Готика нравится не всем, а спорить о вкусах бессмысленно: проще дать
    // человеку поменять. Меняется сразу, без «применить» — видно на самом
    // заголовке за спиной у окошка.
    @Composable
    private fun TitleDialog() {
        AlertDialog(
            onDismissRequest = { showTitleDlg.value = false },
            containerColor = ZenCard,
            title = { Text("Надпись сверху", color = Color.White, fontSize = 17.sp) },
            text = {
                Column {
                    Text("ШРИФТ", color = ZenRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    ZEN_TITLE_FONTS.forEachIndexed { i, (name, family) ->
                        val on = i == titleFont.intValue
                        Surface(
                            color = if (on) ZenCyan.copy(alpha = 0.18f) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable { titleFont.intValue = i; prefs.titleFont = i }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Каждый вариант написан САМИМ собой — иначе
                                // выбирать пришлось бы вслепую по названию.
                                Text(
                                    titleText.value.ifBlank { "ZEN NITRO" },
                                    color = if (on) ZenCyan else Color.White,
                                    fontFamily = family, fontSize = 19.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(name, color = ZenGray, fontSize = 10.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("РАЗМЕР — ${titleSize.intValue}", color = ZenRed, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Slider(
                        value = titleSize.intValue.toFloat(),
                        onValueChange = { titleSize.intValue = it.toInt() },
                        onValueChangeFinished = { prefs.titleSize = titleSize.intValue },
                        valueRange = Prefs.TITLE_MIN.toFloat()..Prefs.TITLE_MAX.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = ZenCyan, activeTrackColor = ZenCyan,
                            inactiveTrackColor = ZenDivider
                        )
                    )

                    Text("СВОЙ ТЕКСТ", color = ZenRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = titleText.value,
                        onValueChange = {
                            // Перевод строки в одну строку заголовка не влезет,
                            // а вставить его из буфера можно легко.
                            val s = it.replace("\n", " ").take(24)
                            titleText.value = s; prefs.titleText = s
                        },
                        placeholder = { Text("ZEN NITRO", color = ZenGray, fontSize = 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ZenCyan, unfocusedBorderColor = ZenDivider,
                            cursorColor = ZenCyan
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    Text("Пусто — вернётся «ZEN NITRO». До 24 знаков.",
                        color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {
                Text("ГОТОВО", color = ZenCyan, fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { showTitleDlg.value = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            },
            dismissButton = {
                Text("СБРОСИТЬ", color = ZenGray, fontSize = 14.sp,
                    modifier = Modifier
                        .clickable {
                            titleText.value = ""; prefs.titleText = ""
                            titleFont.intValue = 0; prefs.titleFont = 0
                            titleSize.intValue = 30; prefs.titleSize = 30
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
        )
    }

    // Кнопка обновления. Её нет вовсе, пока снаружи не появится версия новее.
    //
    // Так и просили: «если всё свежее — не надо чтобы её показывало».
    // Никакого «у вас последняя версия» тут не будет.
    @Composable
    private fun UpdateButton() {
        val rel = update.value ?: return
        val pct = updProgress.intValue
        val ready = updReady.value != null
        val busy = pct != -2

        Surface(
            color = ZenCyan.copy(alpha = 0.14f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.padding(top = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !busy) { if (ready) installUpdate() else downloadUpdate() }
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (ready) Icons.Outlined.Download else Icons.Outlined.FileDownload,
                    null, tint = ZenCyan, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    when {
                        busy && pct >= 0 -> "Качаю… $pct%"
                        busy -> "Качаю…"
                        ready -> "Установить ${rel.name}"
                        else -> "Доступно обновление ${rel.name}"
                    },
                    color = ZenCyan, fontSize = 13.sp, maxLines = 1
                )
            }
        }
        // Что нового — одной строкой и только если это вписали в манифест.
        if (rel.notes.isNotBlank() && !busy)
            Text(rel.notes, color = ZenGray, fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp, start = 24.dp, end = 24.dp))
    }

    // Выбор сервера.
    //
    // Раньше это был ModalBottomSheet, и он был источником вечных жалоб на
    // прокрутку: у шторки собственный жест перетаскивания, который спорит с
    // жестом списка. Тянешь вниз — уезжает не список, а шторка; тянешь вверх —
    // список залипает, пока шторка не решит, что жест её. Никакими настройками
    // это до конца не лечится, поэтому шторки больше нет: обычная панель
    // поверх экрана, единственный скроллируемый элемент — сам список.
    @Composable
    private fun ServerPicker() {
        Box(
            Modifier.fillMaxSize()
                .background(Color(0xCC000000))
                // Тап по затемнению закрывает; клик без ряби, как везде в UI.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { showPicker.value = false },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = ZenCard,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)
                    // Перехватываем клики, чтобы тап по панели её не закрывал.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.width(34.dp).height(4.dp)
                        .background(Color(0xFF3A3A3F), RoundedCornerShape(2.dp)))
                }
                if (engine.intValue == 1) { AwgPickerBody(); return@Column }
                // ПОКАЗЫВАЕМ ВСЁ, ЧТО ПРИСЛАЛА ПОДПИСКА.
                //
                // Раньше список по умолчанию прятал ноды, не ответившие на нашу
                // пробу, — и это врало. Проба стучится по TCP, а живой сервер
                // Hysteria2/QUIC на такой стук молчать вправе: провайдер поднял
                // ноды обратно, человек ходил через них руками, а клиент всё
                // ещё держал их за мёртвые и не показывал вовсе. Проба — это
                // подсказка рядом с именем, а не право вычеркнуть сервер.
                var showAll by remember { mutableStateOf(true) }
                // Читаем тик: он меняется после замера пинга и заставляет
                // список перерисоваться с новыми цифрами.
                @Suppress("UNUSED_EXPRESSION") pingTick.intValue
                // Молчащие ноды прячем: в списке они выглядели живыми, но при
                // выборе просто не подключались. ping = -1 — ещё не мерили.
                val visible = nodes.mapIndexed { i, n -> i to n }
                    .filter { (_, n) -> showAll || (n.supported() && n.ping < 900 && !n.gone) }
                val hidden = nodes.size - visible.size
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Счётчик ушёл строкой ниже: вместе с тремя кнопками
                    // «Серверы (100)» не помещалось и обрезалось многоточием.
                    Text("Серверы", color = Color.White, fontSize = 17.sp, maxLines = 1,
                        softWrap = false, modifier = Modifier.weight(1f).padding(end = 6.dp))
                    // Три разные операции — раньше была одна «обновить», и было
                    // непонятно, что она делает: тянет подписки или мерит.
                    Text("пинг", color = ZenCyan, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier.clickable { repingAll() })
                    Spacer(Modifier.width(12.dp))
                    Text("проверка", color = ZenCyan, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier.clickable { probeActive() })
                    Spacer(Modifier.width(12.dp))
                    Text("обновить", color = ZenCyan, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier.clickable { loadServers() })
                }
                Text(
                    buildString {
                        append("всего ${visible.size}")
                        if (hidden > 0 && !showAll) append(" · не отвечают: $hidden — показать")
                        if (showAll) append(" · показать только рабочие")
                    },
                    color = ZenGray, fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                        .clickable { showAll = !showAll }
                )
                if (nodes.isEmpty()) {
                    Text("Список пуст. Добавьте подписку или конфиг в Настройках.",
                        color = ZenGray, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
                // weight(1f): список занимает ВСЮ оставшуюся высоту панели.
                // Раньше стояло heightIn(max = 460.dp) — при длинном списке
                // получался второй уровень прокрутки внутри уже скроллящейся
                // шторки, отсюда и «залипает».
                // Группируем по подписке: из какого источника какой сервер —
                // видно сразу, без гадания.
                val groups = visible.groupBy { (_, n) -> n.sub.ifEmpty { "Свои ссылки" } }
                    // Аварийная группа всегда сверху.
                    .toList().sortedByDescending { it.first == WL_GROUP }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    groups.forEach { (subName, rows) ->
                        val isWl = subName == WL_GROUP
                        // Полка: нажал на подписку — её серверы свернулись.
                        //
                        // Сотня нод в одном свитке — это не список, а свиток:
                        // чтобы добраться до нужной подписки, приходилось
                        // пролистывать чужие. Открытой держим ту, где лежит
                        // выбранный сервер, — с ней человек и работает.
                        val hasSelected = rows.any { (i, _) -> i == selected.intValue }
                        val open = subOpen[subName] ?: hasSelected
                        item(key = "hdr:$subName") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (isWl) ZenCyan.copy(alpha = 0.12f) else ZenBg)
                                    .clickable { subOpen[subName] = !open }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (open) "▾" else "▸", color = ZenCyan, fontSize = 13.sp)
                                Text("  $subName", color = ZenCyan, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                // На закрытой полке видно, здесь ли выбранный.
                                if (!open && hasSelected)
                                    Text("выбран  ", color = ZenCyan, fontSize = 12.sp)
                                // «Живой» считается нода с ответом, нода, через
                                // которую туннель уже поднимался, и молчун,
                                // которого ещё не признали глухим.
                                // Отвечающая нода считается живой, даже если
                                // выпала из выдачи: счётчик про работоспособность,
                                // а не про то, что панель нам показала.
                                val live = rows.count { (_, n) ->
                                    n.ping in 1..899 || n.lastOkAt > 0L ||
                                    (!n.gone && n.quic() && n.silentTries < Node.SILENT_LIMIT) }
                                Text("$live / ${rows.size}", color = ZenGray, fontSize = 12.sp)
                            }
                        }
                    if (open) items(rows, key = { (i, n) -> "$i|${n.addr}:${n.port}" }) { (i, n) ->
                        val active = i == selected.intValue
                        val ok = n.supported()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    showPicker.value = false
                                    switchServer(i)      // переключение на лету
                                }
                                .background(if (active) ZenCyan.copy(alpha = 0.10f) else Color.Transparent)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(n.name,
                                    color = when {
                                        !ok -> ZenGray
                                        n.gone && !(n.ping in 1..899 || n.lastOkAt > 0L) -> ZenGray
                                        active -> ZenCyan
                                        else -> Color.White
                                    },
                                    fontSize = 16.sp, maxLines = 1)
                                Text(
                                    when {
                                        // ЖИВАЯ НОДА НЕ БЫВАЕТ «ПРОПАВШЕЙ».
                                        //
                                        // «Нет в подписке — сервер лёг?» рядом
                                        // с честным пингом в 136 мс — это ложь
                                        // и повод не выбирать рабочий сервер.
                                        // Пропажа из выдачи и смерть сервера —
                                        // разные вещи: панель просто перестала
                                        // отдавать ноду в том шаблоне, который
                                        // мы спрашиваем, а сервер работает.
                                        // Говорим ровно то, что знаем.
                                        n.gone && (n.ping in 1..899 || n.lastOkAt > 0L) ->
                                            "${n.badge()} · нет в свежей выдаче, но отвечает"
                                        // ГОВОРИМ ТОЛЬКО ТО, ЧТО ЗНАЕМ.
                                        //
                                        // Здесь стояло «НЕТ В ПОДПИСКЕ — сервер
                                        // лёг?» — две догадки в одной строке, и
                                        // обе чаще всего неверные. Панель могла
                                        // просто сменить шаблон выдачи (так у
                                        // Lunaire разом «пропали» двадцать нод,
                                        // живых и работающих), а про сервер мы
                                        // не знаем ничего: он нам не отвечал,
                                        // вот и всё. Пишем ровно это.
                                        n.gone -> "${n.badge()} · нет ответа · с ${goneDate(n.goneAt)} не приходит в выдаче"
                                        !ok -> "${n.badge()} · не поддерживается ядром"
                                        // У аварийных нод сразу видно, годятся
                                        // ли они под подмену имени.
                                        n.whitelistNative() ->
                                            "⚪ маскируется под ${n.sni.ifEmpty { n.host }}"
                                        n.wl && n.frontable() -> "${n.badge()} · прикрытие возможно"
                                        n.wl -> "${n.badge()} · ПРИКРЫТИЕ НЕВОЗМОЖНО"
                                        // Глухая, но однажды поднялась — самый
                                        // важный случай: крест на такой ставить
                                        // нельзя, её просто нечем измерить.
                                        n.ping < 0 && n.lastOkAt > 0L ->
                                            "${n.badge()} · не отвечает на пробы, но работала ${goneDate(n.lastOkAt)}"
                                        n.ping < 0 && n.silentTries >= Node.SILENT_LIMIT ->
                                            "${n.badge()} · молчит ${n.silentTries}-й замер подряд"
                                        else -> "${n.badge()} · ${n.addr}"
                                    },
                                    color = when {
                                        n.gone -> Color(0xFFE87A3A)
                                        !ok -> Color(0xFFE87A3A)
                                        n.ping < 0 && n.lastOkAt > 0L -> ZenGray
                                        n.ping < 0 && n.silentTries >= Node.SILENT_LIMIT ->
                                            Color(0xFFE0483C)
                                        n.whitelistNative() -> ZenCyan
                                        n.wl && n.frontable() -> ZenCyan
                                        n.wl -> Color(0xFFE87A3A)
                                        else -> ZenGray
                                    },
                                    fontSize = 12.sp, maxLines = 1
                                )
                            }
                            Text(
                                when {
                                    !ok -> "✕"
                                    // Тильда = цифра приблизительная: сам порт
                                    // QUIC-ноды молчит, и время померено до
                                    // соседнего порта того же сервера. Путь тот
                                    // же, но выдавать это за точный пинг нельзя.
                                    n.ping in 1..899 ->
                                        if (n.pingExact) "${n.ping} мс" else "~${n.ping} мс"
                                    // Пинга нет, но туннель через неё уже
                                    // поднимался — значит сервер рабочий, и
                                    // крест на нём ставить нельзя.
                                    n.ping < 0 && n.lastOkAt > 0L -> "✓ была"
                                    // Молчит не первый замер подряд — глухая.
                                    // Раньше здесь стояло слово «QUIC»: оно
                                    // ничего не говорило и не менялось никогда,
                                    // сколько бы сервер ни лежал.
                                    n.ping < 0 && n.silentTries >= Node.SILENT_LIMIT -> "✕ нет"
                                    // Промолчала первый раз — это ещё ни о чём:
                                    // бывает от своей же сети. Пробуем дальше.
                                    n.ping < 0 && n.quic() -> "…"
                                    else -> "—"
                                },
                                color = when {
                                    !ok -> Color(0xFFE87A3A)
                                    n.ping in 1..150 -> ZenCyan
                                    n.ping in 151..399 -> Color(0xFFE8B23A)
                                    n.ping in 400..899 -> Color(0xFFE87A3A)
                                    n.ping < 0 && n.lastOkAt > 0L -> ZenCyan
                                    n.ping < 0 && n.silentTries >= Node.SILENT_LIMIT ->
                                        Color(0xFFE0483C)
                                    else -> ZenGray
                                },
                                fontSize = 13.sp
                            )
                        }
                        HorizontalDivider(color = ZenDivider, thickness = 0.5.dp)
                    }
                    }
                }
            }
            }
        }
    }

    // Список профилей Amnezia в той же шторке.
    @Composable
    private fun AwgPickerBody() {
        Text("Профили Amnezia (${awgProfiles.size})", color = Color.White,
            fontSize = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (awgProfiles.isEmpty()) {
            Text("Пусто. Настройки → «Импорт конфига Amnezia»: вставьте .conf " +
                 "или ссылку vpn://, либо откройте файл конфига через «Поделиться».",
                color = ZenGray, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            itemsIndexed(awgProfiles) { i, p ->
                val active = i == selectedAwg.intValue
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { showPicker.value = false; switchAwg(i) }
                        .background(if (active) ZenCyan.copy(alpha = 0.10f) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = if (active) ZenCyan else Color.White,
                            fontSize = 16.sp, maxLines = 1)
                        Text("${p.badge()} · ${p.host}", color = ZenGray, fontSize = 12.sp, maxLines = 1)
                    }
                    Text("удалить", color = Color(0xFFE87A3A), fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            // Выбранный профиль — по ИМЕНИ, а не по номеру: удаление
                            // сдвигает номера. И сохраняем выбор: раньше он правился
                            // только в памяти, после перезапуска указывал на
                            // удалённый профиль — «Нет профилей Amnezia» при живом.
                            val keep = awgProfiles.getOrNull(selectedAwg.intValue)?.name
                            prefs.removeAwgProfile(i)
                            awgProfiles.clear(); awgProfiles.addAll(prefs.awgProfiles())
                            selectedAwg.intValue = awgProfiles.indexOfFirst { it.name == keep }
                                .let { if (it < 0) 0 else it }
                            prefs.selectedAwg = selectedAwg.intValue
                        })
                }
                HorizontalDivider(color = ZenDivider, thickness = 0.5.dp)
            }
        }
    }

    @Composable
    private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
        val ringColor = if (connState.intValue == 0) ZenCyan else ZenRed
        Icon(
            imageVector = icon, contentDescription = null,
            tint = if (active) ringColor else ZenGray,
            modifier = Modifier.size(26.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { onClick() }
        )
    }

    // Главный экран — раскладка 1:1 с Amnezia: кольцо по центру,
    // строка статуса под ним, карточка-шторка сервера снизу.
    @Composable
    private fun HomeTab() {
        val n = nodes.getOrNull(selected.intValue)
        val awg = awgProfiles.getOrNull(selectedAwg.intValue)
        val isAwg = engine.intValue == 1
        val title = if (isAwg) awg?.name ?: "Импортируйте конфиг" else n?.name ?: "Выберите сервер"
        val subtitle = if (isAwg) awg?.let { "${it.badge()} | ${it.host}" } ?: "нет профилей"
                       else n?.let { "${it.badge()} | ${it.addr}" } ?: "нет данных"
        Column(Modifier.fillMaxSize()) {
            // Кольцо: центр примерно на 36% высоты, как у Amnezia.
            // Кнопка обновления живёт вплотную под кольцом и появляется, только
            // когда обновление действительно есть, — в остальное время колонка
            // состоит из одного кольца, и оно стоит ровно там же, где стояло.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ConnectRing()
                    UpdateButton()
                }
            }

            // Строка статуса (у Amnezia тут «Раздельное туннелирование»)
            StatusRow()

            Spacer(Modifier.height(10.dp))
            ModeChips()

            Spacer(Modifier.height(14.dp))

            // Нижняя карточка-шторка с сервером — по тапу открывается выбор
            Surface(
                color = ZenCard,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                modifier = Modifier.fillMaxWidth().clickable { showPicker.value = true }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // «грабер»
                    Box(Modifier.width(34.dp).height(4.dp)
                        .background(Color(0xFF3A3A3F), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(18.dp))
                    // Имена серверов бывают длинными («Moscow-Czechia HYSTERIA2»).
                    // Раньше строка в 30sp без переполнения просто вылезала за
                    // карточку: флаг срезало слева, текст обрубало справа.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            title, color = Color.White,
                            fontSize = if (title.length > 18) 22.sp else 30.sp,
                            fontWeight = FontWeight.Normal, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Color.White,
                            modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, color = ZenGray, fontSize = 15.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }

    // Скорость / пинг / режим — живые цифры из ядра.
    @Composable
    private fun StatusRow() {
        val down by Stats.down.collectAsState()
        val up by Stats.up.collectAsState()
        // Показываем «прибитый» пинг, а не каждый замер сторожа — см. Stats.feedPing.
        val ping by Stats.shownPing.collectAsState()
        val stage by Stats.stage.collectAsState()
        val blocked by Stats.blocked.collectAsState()
        val connected = connState.intValue == 2
        val c = if (connState.intValue == 0) ZenCyan else ZenRed

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Блокировку показываем ПЕРВОЙ. Иначе её перекрыла бы ветка
            // «подключено»: состояние подключения при падении туннеля ещё
            // не сброшено, и человек видел бы бодрые нули скорости вместо
            // объяснения, почему сети нет.
            if (blocked) {
                Icon(Icons.Outlined.Shield, null, tint = ZenRed, modifier = Modifier.size(16.dp))
                Text("  Трафик заблокирован — туннель лежит", color = ZenRed, fontSize = 14.sp)
            } else if (connState.intValue == 1) {
                // Пока крутится кольцо — показываем, что именно проверяется.
                // Цифры рисуем тут же: строка не должна «схлопываться» в пустоту.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stage.ifEmpty { "Запускаю…" }, color = ZenGray, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("↓ ${Stats.fmtSpeed(down)}   ↑ ${Stats.fmtSpeed(up)}   " +
                         (if (ping >= 0) "$ping мс" else "— мс"),
                        color = ZenGray, fontSize = 12.sp)
                }
            } else if (connected) {
                Icon(Icons.Outlined.ArrowDownward, null, tint = c, modifier = Modifier.size(16.dp))
                Text(" ${Stats.fmtSpeed(down)}", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Outlined.ArrowUpward, null, tint = c, modifier = Modifier.size(16.dp))
                Text(" ${Stats.fmtSpeed(up)}", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(16.dp))
                Text(if (ping >= 0) "$ping мс" else "— мс", color = ZenGray, fontSize = 14.sp)
            } else {
                Icon(Icons.Outlined.Shield, null, tint = ZenGray, modifier = Modifier.size(16.dp))
                Text(
                    "  " + when {
                        prefs.vpnEnabled && prefs.dpiEnabled -> "VPN + DPI-щит"
                        prefs.vpnEnabled -> "Режим VPN"
                        prefs.dpiEnabled -> "Режим DPI-щита"
                        else -> "Режим не выбран"
                    },
                    color = ZenGray, fontSize = 14.sp
                )
            }
        }

        // Строка «фактический выход … · проверить утечки» жила здесь и была
        // убрана по просьбе оператора: главный экран — это кнопка, режим и
        // сервер, а справка о выходе на нём лишняя. Сама проверка никуда не
        // делась — она в настройках, в разделе «Защита и роутинг», и на
        // вкладке журнала кнопкой «утечки».
    }

    @Composable
    private fun ConnectRing() {
        val state = connState.intValue

        // ЦВЕТ КНОПКИ: покой = аква; подключение = плавный морф аква → кровь за 5с
        // (пока крутится комета); подключено = красный. Отключение = обратно в акву.
        val ringColor by animateColorAsState(
            targetValue = if (state == 0) ZenCyan else ZenRed,
            animationSpec = tween(durationMillis = if (state == 1) 5000 else 500, easing = LinearEasing),
            label = "ringColor"
        )

        // Анимации крутим ТОЛЬКО когда они видны.
        // Раньше обе висели постоянно: комета перерисовывала Canvas ~130 раз в
        // секунду даже в покое и под открытой шторкой серверов — из-за этого
        // список и подтормаживал при прокрутке.
        val angle = if (state == 1) {
            // Быстрое вращение, как у Amnezia: ~2.2 оборота в секунду.
            val spin = rememberInfiniteTransition(label = "spin")
            spin.animateFloat(0f, 360f,
                infiniteRepeatable(tween(450, easing = LinearEasing)), label = "angle").value
        } else 0f

        val glowPulse = if (state == 2) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            pulse.animateFloat(0.35f, 0.75f,
                infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "glow").value
        } else 0.5f

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(250.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { toggle() }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val w = 3.dp.toPx()                       // тонкое кольцо, как у Amnezia
                val r = size.minDimension / 2f - 12.dp.toPx()

                // Тонкий плотный ореол вплотную к кольцу (как у Amnezia),
                // а не широкое размытое пятно.
                val glowAlpha = when (state) { 2 -> 0.30f + glowPulse * 0.22f; 1 -> 0.34f; else -> 0.22f }
                val gr = r * 1.13f
                drawCircle(
                    brush = Brush.radialGradient(
                        0.865f to Color.Transparent,
                        0.925f to ringColor.copy(alpha = glowAlpha),
                        1f to Color.Transparent,
                        center = center, radius = gr
                    ),
                    radius = gr, center = center
                )

                if (state == 1) {
                    // Тусклая база + яркая вращающаяся дуга (механика Amnezia).
                    drawCircle(ringColor.copy(alpha = 0.25f), r, center, style = Stroke(w))
                    rotate(degrees = angle, pivot = center) {
                        // Короткая дуга с затухающим хвостом — вращение хорошо читается.
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.00f to Color.Transparent,
                                0.28f to ringColor.copy(alpha = 0.55f),
                                0.38f to ringColor,
                                0.40f to Color.Transparent,
                                1.00f to Color.Transparent,
                                center = center
                            ),
                            startAngle = -90f, sweepAngle = 360f, useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(width = w * 1.4f, cap = StrokeCap.Round)
                        )
                    }
                } else {
                    // Сплошное кольцо.
                    drawCircle(ringColor, r, center, style = Stroke(w))
                }
            }
            Text(
                when (state) { 2 -> "Подключено"; 1 -> "Подключение…"; else -> "Подключиться" },
                color = if (state == 2) ringColor else Color.White,
                fontWeight = FontWeight.Normal, fontSize = 26.sp
            )
        }
    }

    // Выбор ядра и всё, что относится к режиму Amnezia.
    @Composable
    private fun EngineSection() {
        Text("ЯДРО ТУННЕЛЯ", color = ZenRed, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf(
                0 to "sing-box + Xray",
                1 to "Amnezia (AWG)"
            ).forEach { (id, label) ->
                Surface(
                    color = if (engine.intValue == id) ZenRed.copy(alpha = 0.30f) else ZenCard,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).padding(end = 6.dp).clickable {
                        if (engine.intValue != id) {
                            engine.intValue = id; prefs.engine = id
                            if (connState.intValue != 0) {
                                log("🔄 Смена ядра — переподключаюсь…")
                                stopTunnel()
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(900); toggleConnect(force = true)
                                }
                            }
                        }
                    }
                ) {
                    Text(label, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(10.dp))
                }
            }
        }
        Text(
            if (engine.intValue == 1)
                "VLESS/Hysteria2/TUIC · протоколы Amnezia: AWG 1.5 и 2.0 (Jc/S/H/I), " +
                "ядро amneziawg-go ${com.zen.blaster.core.AmneziaCore.BUILD}"
            else "VLESS (Reality/Vision/WS/gRPC/XHTTP/mKCP), Hysteria2, TUIC, Trojan, Shadowsocks",
            color = ZenGray, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        if (engine.intValue == 1) {
            Spacer(Modifier.height(14.dp))
            Text("ИМПОРТ КОНФИГА AMNEZIA", color = ZenRed, fontWeight = FontWeight.Bold)
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("вставьте .conf или ссылку vpn://…", color = ZenGray, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 200.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(
                    onClick = { if (text.isNotBlank()) { importAwg(text); text = "" } },
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenRed)
                ) { Text("ДОБАВИТЬ", color = Color.White, fontSize = 13.sp) }
                Button(
                    onClick = {
                        val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val s = cb.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                        if (s.isNullOrBlank()) log("⚠️ Буфер обмена пуст") else importAwg(s)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenCard)
                ) { Text("ИЗ БУФЕРА", color = Color.White, fontSize = 13.sp) }
            }

            val p = awgProfiles.getOrNull(selectedAwg.intValue)
            if (p != null) {
                Spacer(Modifier.height(10.dp))
                Text("АКТИВНЫЙ ПРОФИЛЬ", color = ZenRed, fontWeight = FontWeight.Bold)
                Text(
                    "${p.name}\n${p.badge()} · ${p.endpoint}\n" +
                    "маскировка: " + (if (p.junk.isEmpty()) "нет (чистый WireGuard)"
                                      else p.junk.entries.joinToString(" ") { "${it.key}=${it.value}" }),
                    color = ZenGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    @Composable
    private fun SettingsTab() {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(scroll)) {
            Text("НАСТРОЙКИ", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            EngineSection()
            Spacer(Modifier.height(20.dp))

            Text("ВЫБОР СЕРВЕРА", color = ZenRed, fontWeight = FontWeight.Bold)
            // Тот же принцип, что и в нижнем списке: сперва подписка, под ней
            // её серверы. Иначе сотня нод сливается в кашу.
            nodes.withIndex()
                .groupBy { (_, n) -> n.sub.ifEmpty { "Свои ссылки" } }
                .forEach { (subName, rows) ->
                    val live = rows.count { (_, n) -> n.ping in 1..899 || n.lastOkAt > 0L ||
                        (n.quic() && n.silentTries < Node.SILENT_LIMIT) }
                    // Подписка — это полка, и она закрыта, пока её не откроют.
                    //
                    // Раньше все сотни нод вываливались списком разом, и до
                    // остальных настроек приходилось листать минуту. Открытой
                    // держим ту полку, где лежит выбранный сервер, — чтобы,
                    // зайдя в настройки, человек сразу видел, на чём сидит.
                    val hasSelected = rows.any { (i, _) -> i == selected.intValue }
                    val open = subOpen[subName] ?: hasSelected
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)
                            .clickable { subOpen[subName] = !open },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (open) "▾" else "▸", color = ZenCyan, fontSize = 13.sp)
                        Text("  $subName", color = ZenCyan, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        // На закрытой полке видно, что внутри: сколько живых из
                        // скольких и не здесь ли выбранный сервер.
                        if (!open && hasSelected)
                            Text("выбран  ", color = ZenRed, fontSize = 12.sp)
                        Text("$live / ${rows.size}", color = ZenGray, fontSize = 12.sp)
                    }
                    if (open) rows.forEach { (i, n) ->
                        Surface(
                            color = if (i == selected.intValue) ZenRed.copy(alpha = 0.25f) else ZenCard,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                selected.intValue = i; prefs.selectedServer = i
                            }) {
                            Text(n.display(), color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            Button(onClick = { loadServers() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZenRed)) {
                Text("ОБНОВИТЬ И ЗАГРУЗИТЬ СЕРВЕРЫ", color = Color.White)
            }
            Button(onClick = { autoSelectBest() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZenCyan)) {
                Text("🚀 НАЙТИ ЛУЧШИЙ (по пингу)", color = Color.Black)
            }

            // ---------- ПОДПИСКИ И КОНФИГИ ----------
            Spacer(Modifier.height(22.dp))
            Text("ПОДПИСКИ И КОНФИГИ", color = ZenRed, fontWeight = FontWeight.Bold)

            var subs by remember { mutableStateOf(prefs.subscriptions()) }
            subs.forEachIndexed { i, s ->
                Surface(color = ZenCard, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                            Text(s.url.take(46) + if (s.url.length > 46) "…" else "",
                                color = ZenGray, fontSize = 11.sp, maxLines = 1)
                        }
                        Text("удалить", color = ZenRed, fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                prefs.removeSubscription(i)
                                subs = prefs.subscriptions()
                                loadServers()
                            })
                    }
                }
            }
            if (subs.isEmpty())
                Text("Пока пусто. Вставьте ссылку подписки или конфиг ниже.",
                    color = ZenGray, fontSize = 12.sp)

            var input by remember { mutableStateOf("") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("https://… или vless:// hysteria2:// trojan:// ss:// tuic://",
                    color = ZenGray, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ZenCyan, unfocusedBorderColor = ZenDivider,
                    cursorColor = ZenCyan
                ),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(
                    onClick = {
                        // Можно вставить сразу пачку строк — добавляем каждую.
                        val lines = input.split('\n', '\r')
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isEmpty()) return@Button
                        var added = 0
                        for (raw in lines) {
                            val name = when {
                                raw.contains('#') ->
                                    runCatching { java.net.URLDecoder.decode(raw.substringAfterLast('#'), "UTF-8") }
                                        .getOrDefault("Конфиг")
                                raw.startsWith("http") ->
                                    runCatching { java.net.URI(raw).host }.getOrNull() ?: "Подписка"
                                else -> "Конфиг"
                            }.take(40)
                            prefs.addSubscription(name, raw)
                            added++
                        }
                        subs = prefs.subscriptions()
                        input = ""
                        log("➕ Добавлено записей: $added")
                        loadServers()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenCyan),
                    modifier = Modifier.weight(1f)
                ) { Text("ДОБАВИТЬ", color = Color.Black) }

                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val t = cb.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (t.isNotBlank()) input = t.trim() else log("⚠️ Буфер обмена пуст")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("ИЗ БУФЕРА", color = ZenCyan) }
            }
            Text("Поддерживаются подписки (http/https) и прямые конфиги. Можно вставить несколько строк.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(20.dp))
            Text("РЕЖИМ РАБОТЫ", color = ZenRed, fontWeight = FontWeight.Bold)
            ToggleRow("VPN (сервер из списка, меняет IP)", prefs.vpnEnabled) { prefs.vpnEnabled = it }
            ToggleRow("DPI-щит ByeDPI (обход без VPN)", prefs.dpiEnabled) { prefs.dpiEnabled = it }
            Text(
                "Оба сразу = «Режим БОГА»: VPN-хендшейк прячется за ByeDPI. " +
                "Конфликта нет — туннель один (его держит ядро), ByeDPI работает как локальный SOCKS.",
                color = ZenGray, fontSize = 11.sp
            )

            Spacer(Modifier.height(14.dp))
            Text("СТРАТЕГИЯ ОБХОДА DPI", color = ZenRed, fontWeight = FontWeight.Bold)
            var stratSel by remember { mutableIntStateOf(prefs.dpiStrategy) }
            com.zen.blaster.core.ByeDpi.STRATEGIES.forEachIndexed { i, s ->
                Surface(color = if (i == stratSel) ZenCyan.copy(alpha = 0.22f) else ZenCard,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                        stratSel = i; prefs.dpiStrategy = i
                    }) {
                    Text(s.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                }
            }
            Text("Не открывается сайт — смени стратегию.", color = ZenGray, fontSize = 11.sp)

            Spacer(Modifier.height(20.dp))
            Text("🛡 БРОНЯ ОТ ДЕТЕКТА (PDP)", color = ZenRed, fontWeight = FontWeight.Bold)
            ToggleRow("Маскировка туннеля под обычный HTTPS", prefs.armor) { prefs.armor = it }
            Text(
                "Оператор ловит не VPN, а сам ФАКТ туннеля — и душит абонента. Броня прячет признаки:\n" +
                "• TLS-отпечаток меняется каждую сессию (ломает JA3/JA4-профиль)\n" +
                "• padding ломает анализ размеров и равномерности пакетов\n" +
                "• xudp прячет UDP-паттерн, IPv6 выключен (утечка выдаёт туннель)\n" +
                "• фрагментация ClientHello — включите DPI-щит, её делает ByeDPI",
                color = ZenGray, fontSize = 11.sp
            )

            Spacer(Modifier.height(20.dp))
            Text("ДОБАВИТЬ СЕРВЕР ССЫЛКОЙ", color = ZenRed, fontWeight = FontWeight.Bold)
            var link by remember { mutableStateOf("") }
            OutlinedTextField(
                value = link, onValueChange = { link = it },
                placeholder = { Text("vless://…  hysteria2://…  ss://…", color = ZenGray, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(
                    onClick = {
                        val v = link.trim()
                        if (v.contains("://")) {
                            prefs.addManualLink(v); link = ""
                            log("➕ Ссылка добавлена, обновляю список"); loadServers()
                        } else log("❌ Это не похоже на ссылку сервера")
                    },
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenRed)
                ) { Text("ДОБАВИТЬ", color = Color.White, fontSize = 13.sp) }
                Button(
                    onClick = {
                        val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val s = cb.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim()
                        if (s.isNullOrEmpty() || !s.contains("://")) log("⚠️ В буфере нет ссылки")
                        else { prefs.addManualLink(s); log("➕ Из буфера добавлено"); loadServers() }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenCard)
                ) { Text("ИЗ БУФЕРА", color = Color.White, fontSize = 13.sp) }
            }
            // «Свои ссылки» — раньше их можно было только добавить. Список
            // копился, мёртвые ноды оставались в нём навсегда и каждый раз
            // тормозили обновление подписок, а убрать их было нечем.
            var mine by remember { mutableStateOf(prefs.manualLinks()) }
            if (mine.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Свои ссылки: ${mine.size}", color = ZenGray, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    Text("удалить все", color = ZenRed, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            mine.forEach { prefs.removeManualLink(it) }
                            mine = prefs.manualLinks()
                            log("🗑 Вкладка «Свои ссылки» очищена")
                            loadServers()
                        })
                }
                mine.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(l.take(44) + if (l.length > 44) "…" else "",
                            color = ZenGray, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        Text("удалить", color = Color(0xFFE87A3A), fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                prefs.removeManualLink(l)
                                mine = prefs.manualLinks()
                                log("🗑 Ссылка удалена")
                                loadServers()
                            })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("РАЗГОН HYSTERIA2", color = ZenRed, fontWeight = FontWeight.Bold)
            var boost by remember { mutableStateOf(prefs.hyBoost) }
            var hDown by remember { mutableStateOf(prefs.hyDownMbps.toString()) }
            var hUp by remember { mutableStateOf(prefs.hyUpMbps.toString()) }
            ToggleRow("Держать темп (режим Brutal)", boost) { boost = it; prefs.hyBoost = it }
            if (boost) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = hDown,
                        onValueChange = {
                            hDown = it.filter { c -> c.isDigit() }.take(5)
                            hDown.toIntOrNull()?.let { v -> prefs.hyDownMbps = v }
                        },
                        label = { Text("приём, Мбит/с", color = ZenGray, fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                    OutlinedTextField(
                        value = hUp,
                        onValueChange = {
                            hUp = it.filter { c -> c.isDigit() }.take(5)
                            hUp.toIntOrNull()?.let { v -> prefs.hyUpMbps = v }
                        },
                        label = { Text("отдача, Мбит/с", color = ZenGray, fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                "Без заданных скоростей Hysteria2 работает на BBR: при потере пакетов честно " +
                "сбавляет темп. На мобильной сети потери есть всегда — отсюда и вечное " +
                "«еле ползёт». Заданные значения включают режим Brutal: ядро держит заявленный " +
                "темп, не отступая на каждую потерю.\n\n" +
                "Важно: ставить БОЛЬШЕ реального канала нельзя — станет только хуже, клиент " +
                "начнёт забивать канал и топить сам себя. Ставьте примерно свою реальную " +
                "скорость или чуть ниже. Часть серверов такие заявки игнорирует — тогда " +
                "останется BBR, вреда не будет.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))
            Text("АВАРИЙНЫЕ СЕРВЕРЫ (БЕЛЫЙ СПИСОК)", color = ZenRed, fontWeight = FontWeight.Bold)
            Text(
                "Отдельный арсенал: эти ноды идут первой группой в списке и получают " +
                "собственный, максимально консервативный набор параметров — отпечаток chrome, " +
                "обычный TLS вместо QUIC, только IPv4, одна длинная сессия вместо пачки " +
                "коротких, без цепочки через DPI-щит. Скорость приносится в жертву " +
                "проходимости: под белым списком у нас один выстрел.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
            var wlLink by remember { mutableStateOf("") }
            OutlinedTextField(
                value = wlLink, onValueChange = { wlLink = it },
                placeholder = { Text("ссылка ноды или подписки для аварий", color = ZenGray, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(
                    onClick = {
                        val v = wlLink.trim()
                        if (v.contains("://")) {
                            prefs.addWlLink(v); wlLink = ""
                            log("⚪ Добавлено в аварийный арсенал"); loadServers()
                        } else log("❌ Это не похоже на ссылку")
                    },
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenRed)
                ) { Text("В АВАРИЙНЫЕ", color = Color.White, fontSize = 13.sp) }
                Button(
                    onClick = {
                        val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val s = cb.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim()
                        if (s.isNullOrEmpty() || !s.contains("://")) log("⚠️ В буфере нет ссылки")
                        else { prefs.addWlLink(s); log("⚪ Из буфера в аварийные"); loadServers() }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenCard)
                ) { Text("ИЗ БУФЕРА", color = Color.White, fontSize = 13.sp) }
            }
            prefs.wlLinks().forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(l.take(46) + if (l.length > 46) "…" else "",
                        color = ZenGray, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    Text("удалить", color = Color(0xFFE87A3A), fontSize = 11.sp,
                        modifier = Modifier.clickable { prefs.removeWlLink(l); loadServers() })
                }
            }
            // Сразу показываем, сколько из них реально годится под подмену.
            val wlNodes = nodes.filter { it.wl }
            if (wlNodes.isNotEmpty()) {
                val good = wlNodes.count { it.frontable() }
                Text(
                    "В арсенале ${wlNodes.size}, прикрытие возможно у $good." +
                    if (good < wlNodes.size)
                        " Остальные — Reality или голый TCP: у них имя подменить нельзя."
                    else "",
                    color = if (good > 0) ZenCyan else Color(0xFFE87A3A),
                    fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("ОБХОД БЕЛЫХ СПИСКОВ", color = ZenRed, fontWeight = FontWeight.Bold)
            var wl by remember { mutableStateOf(prefs.whitelistMode) }
            var wlDomain by remember { mutableStateOf(prefs.whitelistDomain) }
            ToggleRow("Прикрываться разрешённым доменом", wl) { wl = it; prefs.whitelistMode = it }
            if (wl) {
                OutlinedTextField(
                    value = wlDomain,
                    onValueChange = { wlDomain = it; prefs.whitelistDomain = it.trim() },
                    placeholder = { Text("www.gosuslugi.ru", color = ZenGray, fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("CDN VK", color = ZenGray, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf("sun9-1.userapi.com", "vk.com", "vkvideo.ru").forEach { d ->
                        Text(d, color = ZenCyan, fontSize = 11.sp,
                            modifier = Modifier.padding(end = 12.dp)
                                .clickable { wlDomain = d; prefs.whitelistDomain = d })
                    }
                }
                Text("CDN Яндекс", color = ZenGray, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf("yastatic.net", "avatars.mds.yandex.net", "mc.yandex.ru").forEach { d ->
                        Text(d, color = ZenCyan, fontSize = 11.sp,
                            modifier = Modifier.padding(end = 12.dp)
                                .clickable { wlDomain = d; prefs.whitelistDomain = d })
                    }
                }
            }
            Text(
                "Прикрываться лучше раздатчиками статики VK и Яндекса: картинки, скрипты и " +
                "видео с этих адресов ходят у всех и постоянно, поэтому такой трафик вопросов " +
                "не вызывает.\n\n" +
                "Когда оператор рубит всё, кроме списка разрешённых сайтов, наружу проходит " +
                "только то, что ВЫГЛЯДИТ походом на разрешённый домен. Имя сайта DPI читает из " +
                "открытой части TLS-приветствия (SNI) и из заголовка Host — их и подменяем, " +
                "соединяясь при этом с настоящим адресом сервера.\n\n" +
                "Главная граница: это работает, когда оператор фильтрует по ИМЕНИ сайта. " +
                "Если он режет по списку разрешённых IP-адресов, подмена имени не поможет " +
                "ничем — пакет просто не выйдет за пределы сети. Проверяется быстро: если " +
                "с прикрытием не поднимается ни одна нода, значит фильтр по адресам.\n\n" +
                "Честно о границах: лучше всего работает на WS и XHTTP через CDN. " +
                "Reality не подменяется — там имя жёстко связано с настройкой сервера, " +
                "да он и так маскируется под чужой сайт. При подмене проверка имени в " +
                "сертификате отключается (шифрование остаётся) — иначе рукопожатие не пройдёт.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))
            Text("КАК ИСПОЛЬЗОВАТЬ СЕРВЕР", color = ZenRed, fontWeight = FontWeight.Bold)
            var proxy by remember { mutableStateOf(prefs.proxyMode) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                listOf(false to "VPN (весь телефон)", true to "Прокси (порт)").forEach { (v, label) ->
                    Surface(
                        color = if (proxy == v) ZenRed.copy(alpha = 0.30f) else ZenCard,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).padding(end = 6.dp).clickable {
                            if (proxy != v) {
                                proxy = v; prefs.proxyMode = v
                                if (connState.intValue != 0) {
                                    log("🔄 Смена режима — переподключаюсь…")
                                    stopTunnel()
                                    lifecycleScope.launch {
                                        kotlinx.coroutines.delay(900); toggleConnect(force = true)
                                    }
                                }
                            }
                        }
                    ) { Text(label, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(10.dp)) }
                }
            }
            Text(
                if (proxy)
                    "Ядро слушает SOCKS5 и HTTP на 127.0.0.1:${Prefs.PROXY_PORT}. " +
                    "Системные маршруты не трогаются, разрешение VPN не нужно — " +
                    "прокси прописывается в приложении вручную (браузер, Termux и т.п.). " +
                    "Остальной трафик телефона идёт напрямую."
                else "Поднимается системный VPN: в туннель уходит весь трафик телефона.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )

            // ---------- РАЗДАЧА НА КОМПЬЮТЕР ----------
            Spacer(Modifier.height(20.dp))
            Text("РАЗДАЧА НА КОМПЬЮТЕР", color = ZenRed, fontWeight = FontWeight.Bold)
            var lan by remember { mutableStateOf(prefs.lanProxy) }
            ToggleRow("Пускать в прокси из локальной сети", lan) {
                lan = it
                prefs.lanProxy = it
                if (it) prefs.ensureLanPass()
                if (connState.intValue != 0) {
                    log("🔄 Раздача изменилась — переподключаюсь…")
                    stopTunnel()
                    lifecycleScope.launch { kotlinx.coroutines.delay(900); toggleConnect(force = true) }
                }
            }
            if (lan) {
                val addrs = remember(lan, connState.intValue) {
                    com.zen.blaster.core.LanInfo.addresses()
                }
                Text("Пропишите на компьютере прокси SOCKS5 или HTTP:",
                    color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                if (addrs.isEmpty())
                    Text("  адрес появится, когда включите режим модема",
                        color = ZenGray, fontSize = 12.sp)
                addrs.forEach { a ->
                    Text("  ${a.ip}:${Prefs.PROXY_PORT}   — ${a.kind}",
                        color = ZenCyan, fontSize = 13.sp)
                }
                Text("  логин ${prefs.lanUser}   пароль ${prefs.lanPass}",
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("новый пароль", color = ZenCyan, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            prefs.lanPass = ""; prefs.ensureLanPass()
                            log("🔑 Пароль раздачи изменён — пропишите новый на компьютере")
                            lan = false; lan = true
                        })
                    Spacer(Modifier.width(16.dp))
                    Text("скопировать", color = ZenCyan, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val a = addrs.firstOrNull()?.ip ?: "127.0.0.1"
                            cb.setPrimaryClip(android.content.ClipData.newPlainText("zen",
                                "$a:${Prefs.PROXY_PORT} ${prefs.lanUser} ${prefs.lanPass}"))
                            toast("Скопировано")
                        })
                }
                Text(
                    "Телефон в режиме модема раздаёт интернет, а компьютер ходит через этот " +
                    "прокси — и попадает в туннель вместе с телефоном. Хотите на компьютере " +
                    "без VPN — просто не прописывайте прокси: раздача продолжит работать " +
                    "напрямую. Переключать что-то на телефоне для этого не нужно.\n\n" +
                    "Почему именно прокси, а не «весь трафик модема в туннель»: Android не " +
                    "пускает трафик раздачи в VPN. Пакеты чужих устройств пересылает ядро " +
                    "системы, а правила VPN применяются по владельцу сокета, которого у " +
                    "такого трафика нет. Без root это не обходится — и это ровно та причина, " +
                    "по которой «раздача через VPN» криво работает у всех клиентов.\n\n" +
                    "Пароль обязателен не для красоты: прокси слушает всю сеть, и без пароля " +
                    "им воспользуется любой сосед по Wi-Fi, а наружу это пойдёт как ваш трафик.",
                    color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }

            // ---------- DNS ----------
            Spacer(Modifier.height(20.dp))
            Text("РЕЗОЛВЕР DNS", color = ZenRed, fontWeight = FontWeight.Bold)
            var dnsSel by remember { mutableIntStateOf(prefs.dnsProvider) }
            var dnsOwn by remember { mutableStateOf(prefs.dnsCustom) }

            // АВТО — первым, потому что это лучший ответ из возможных.
            //
            // Подписка в формате конфига несёт секцию dns, и провайдер кладёт
            // туда резолвер, который для ЕГО нод отдаёт настоящие адреса, а не
            // подменённые оператором. Наш собственный выбор из списка — всегда
            // догадка, а тут ответ прислали вместе с серверами. Пока никто
            // ничего не прислал, «Авто» ведёт себя как выбор по умолчанию.
            val autoName = com.zen.blaster.core.DnsChoice.fromSub
            Surface(
                color = if (dnsSel == com.zen.blaster.core.DnsChoice.AUTO)
                    ZenCyan.copy(alpha = 0.18f) else ZenCard,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        dnsSel = com.zen.blaster.core.DnsChoice.AUTO
                        applyDns(dnsSel, dnsOwn)
                    }
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Авто — как советует подписка",
                        color = if (dnsSel == com.zen.blaster.core.DnsChoice.AUTO) ZenCyan else Color.White,
                        fontSize = 14.sp)
                    // Показываем и то, что назвал провайдер, и то, куда мы
                    // на самом деле пойдём: известные имена подменяются на
                    // адреса, иначе резолвер пришлось бы резолвить.
                    val said = autoName.removePrefix("https://").substringBefore('/')
                    val real = com.zen.blaster.core.DnsChoice.autoUrl()
                        .removePrefix("https://").substringBefore('/')
                    Text(
                        when {
                            autoName.isEmpty() ->
                                "подписка пока ничего не назвала — беру " +
                                com.zen.blaster.core.DnsChoice.LIST[
                                    com.zen.blaster.core.DnsChoice.PREFERRED].name
                            said != real -> "провайдер назвал $said — иду на $real"
                            else -> "провайдер назвал $said"
                        },
                        color = ZenGray, fontSize = 11.sp)
                    // Совет провайдера годится ВНУТРИ туннеля: там запрос
                    // зашифрован до сервера и никто его не режет. Снаружи,
                    // пока канала нет, тот же адрес легко оказывается тем,
                    // по которому ТСПУ и бьёт, — поэтому адрес ноды
                    // разрешаем заведомо живучим.
                    if (autoName.isNotEmpty())
                        Text("адрес ноды при этом разрешает " +
                             com.zen.blaster.core.DnsChoice.LIST[
                                 com.zen.blaster.core.DnsChoice.PREFERRED].name +
                             " — снаружи туннеля совет провайдера не годится",
                            color = ZenGray, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp))
                }
            }

            com.zen.blaster.core.DnsChoice.LIST.forEachIndexed { i, s ->
                Surface(
                    color = if (i == dnsSel) ZenCyan.copy(alpha = 0.18f) else ZenCard,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dnsSel = i; applyDns(i, dnsOwn) }
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(s.name, color = if (i == dnsSel) ZenCyan else Color.White, fontSize = 14.sp)
                        Text(s.hint, color = ZenGray, fontSize = 11.sp)
                    }
                }
            }
            // Системный — отдельно и с честной оговоркой: обычно он худший,
            // но в день ограничений единственный живой.
            Surface(
                color = if (dnsSel == com.zen.blaster.core.DnsChoice.SYSTEM)
                    ZenCyan.copy(alpha = 0.18f) else ZenCard,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        dnsSel = com.zen.blaster.core.DnsChoice.SYSTEM
                        applyDns(dnsSel, dnsOwn)
                    }
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Системный (оператора)",
                        color = if (dnsSel == com.zen.blaster.core.DnsChoice.SYSTEM) ZenCyan else Color.White,
                        fontSize = 14.sp)
                    Text("оператор видит имена, зато работает когда режут DoH",
                        color = ZenGray, fontSize = 11.sp)
                }
            }
            Surface(
                color = if (dnsSel == com.zen.blaster.core.DnsChoice.CUSTOM)
                    ZenCyan.copy(alpha = 0.18f) else ZenCard,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        dnsSel = com.zen.blaster.core.DnsChoice.CUSTOM
                        applyDns(dnsSel, dnsOwn)
                    }
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Свой адрес",
                        color = if (dnsSel == com.zen.blaster.core.DnsChoice.CUSTOM) ZenCyan else Color.White,
                        fontSize = 14.sp)
                    Text("любой DoH — например свой сервер", color = ZenGray, fontSize = 11.sp)
                }
            }
            if (dnsSel == com.zen.blaster.core.DnsChoice.CUSTOM)
                OutlinedTextField(
                    value = dnsOwn,
                    onValueChange = { dnsOwn = it; applyDns(dnsSel, it) },
                    placeholder = { Text("https://адрес/dns-query", color = ZenGray, fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenCyan, unfocusedBorderColor = ZenDivider,
                        cursorColor = ZenCyan),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            Button(
                onClick = { checkDns() },
                colors = ButtonDefaults.buttonColors(containerColor = ZenCard),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("ПРОВЕРИТЬ ВСЕ РЕЗОЛВЕРЫ", color = Color.White, fontSize = 13.sp) }

            // ---- СБРОС DNS-КЕША ----
            //
            // ТСПУ отравляет DNS-кеш фейковыми ответами с огромным TTL.
            // Одна инъекция — и устройство «помнит» мусор часами, даже если
            // потом выйдет на чистую сеть. Сброс чистит три кеша: наш
            // (Resolver), ядра (sing-box) и даёт системе намёк пересмотреть
            // свой. Кнопка для ручного, переключатель для автоматического.
            Spacer(Modifier.height(10.dp))
            Text("DNS-КЕШ", color = ZenRed, fontWeight = FontWeight.Bold)

            // Индикатор здоровья DNS
            val dnsHealth = remember { mutableIntStateOf(com.zen.blaster.core.DnsCacheManager.healthStatus) }
            val lastFlush = remember { mutableLongStateOf(com.zen.blaster.core.DnsCacheManager.lastFlushAt) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Кнопка сброса кеша
                Button(
                    onClick = {
                        com.zen.blaster.core.DnsCacheManager.onLog = { msg -> runOnUiThread { log(msg) } }
                        com.zen.blaster.core.DnsCacheManager.flush()
                        lastFlush.longValue = com.zen.blaster.core.DnsCacheManager.lastFlushAt
                        // Кеш ядра через его API не чистится — в нашей версии
                        // такого вызова нет. Чистый кеш ядра бывает только у
                        // заново поднятого ядра, поэтому при живом туннеле
                        // переподнимаем его.
                        if (connState.intValue != 0) {
                            log("🔄 Переподнимаю ядро — у нового кеш имён пустой…")
                            stopTunnel()
                            lifecycleScope.launch { kotlinx.coroutines.delay(600); toggleConnect(force = true) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenCard),
                    modifier = Modifier.weight(1f)
                ) { Text("🧹 СБРОСИТЬ КЕШ", color = Color.White, fontSize = 12.sp) }

                // Кнопка проверки здоровья DNS
                Button(
                    onClick = {
                        com.zen.blaster.core.DnsCacheManager.onLog = { msg -> runOnUiThread { log(msg) } }
                        log("🩺 Проверяю DNS…")
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val r = com.zen.blaster.core.DnsCacheManager.checkHealth()
                            dnsHealth.intValue = r.status
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (dnsHealth.intValue) {
                            1 -> Color(0xFF1B5E20).copy(alpha = 0.3f) // зелёный
                            2 -> Color(0xFFF57F17).copy(alpha = 0.3f) // жёлтый
                            3 -> Color(0xFFB71C1C).copy(alpha = 0.3f) // красный
                            else -> ZenCard
                        }
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (dnsHealth.intValue) {
                            1 -> "🟢 DNS ЗДОРОВ"
                            2 -> "🟡 DNS МЕДЛЕННЫЙ"
                            3 -> "🔴 DNS ОТРАВЛЕН"
                            else -> "🔍 ПРОВЕРИТЬ DNS"
                        },
                        color = Color.White, fontSize = 12.sp
                    )
                }
            }

            // Время последнего сброса
            if (lastFlush.longValue > 0) {
                val ago = (System.currentTimeMillis() - lastFlush.longValue) / 1000
                Text(
                    "последний сброс: " + when {
                        ago < 5 -> "только что"
                        ago < 60 -> "${ago}с назад"
                        ago < 3600 -> "${ago / 60} мин назад"
                        else -> "${ago / 3600}ч назад"
                    },
                    color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Переключатель автоматического сброса при смене сети
            var autoFlush by remember { mutableStateOf(prefs.autoFlushDns) }
            ToggleRow("Авто-сброс при смене сети", autoFlush) {
                autoFlush = it
                prefs.autoFlushDns = it
                com.zen.blaster.core.DnsCacheManager.setAutoFlush(it)
                log(if (it) "🧹 Авто-сброс DNS при смене сети: включён"
                    else "🧹 Авто-сброс DNS при смене сети: выключен")
            }

            Text(
                "Адреса числовые, а не доменные, и это нарочно: чтобы разрешить имя " +
                "резолвера, нужен резолвер — получилась бы петля.\n\n" +
                "Резолвер важнее, чем кажется. Раньше он был один и зашит намертво, и " +
                "в день, когда оператор прикрыл Cloudflare, приложение осталось без имён " +
                "вообще: ни ноду разрешить, ни подписку загрузить. Если что-то «не " +
                "грузится вообще» — сначала смените здесь адрес и нажмите проверку.\n\n" +
                "«Сбросить кеш» забывает адреса нод, а при поднятом туннеле ещё и " +
                "переподнимает ядро — у нового ядра кеш имён пустой. Системный кеш " +
                "Android без root не чистится, но он и не мешает: пока туннель поднят, " +
                "приложения спрашивают имена у ядра, а не у оператора.\n\n" +
                "Подменить ответ шифрованного резолвера на проводе нельзя, поэтому " +
                "запоминаем только их. Если DoH молчал и адрес пришлось взять у " +
                "оператора — такой ответ используется один раз и не хранится.\n\n" +
                "«Проверить» отвечает на два вопроса: работает ли наш резолвер (от " +
                "этого зависит, встанут ли ноды с доменным адресом) и подменяет ли " +
                "ответы DNS оператора — второе для справки, мы им не пользуемся.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))
            Text("ЗАЩИТА И РОУТИНГ", color = ZenRed, fontWeight = FontWeight.Bold)

            // ГДЕ МЫ ВЫШЛИ НА САМОМ ДЕЛЕ.
            //
            // Имя ноды пишет провайдер, и оно врёт: каскадные ноды называют
            // точку ВХОДА, а выходят в третьей стране — «🇫🇮 Каскад NoV-FI»
            // на проверке вышла в Румынии. Человек выбирает страну осознанно,
            // так что настоящую надо показать. Но на главном экране этой
            // строке не место — там кнопка и сервер, остальное мешает.
            val exit by Stats.exitPlace.collectAsState()
            val exitIp by Stats.exitIp.collectAsState()
            Surface(
                color = ZenCard, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)
                    .clickable { showLeaks.value = true }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("ПРОВЕРИТЬ УТЕЧКИ", color = ZenCyan, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                    Text(
                        if (exit.isNotEmpty())
                            "фактический выход: $exit" +
                            (if (exitIp.isNotEmpty()) " ($exitIp)" else "")
                        else "нажмите, чтобы сверить адрес в туннеле и без него",
                        color = ZenGray, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            ToggleRow("РУ-сайты и приложения мимо туннеля", splitRu.value) { toggleSplitRu(it) }
            if (splitRu.value) {
                Button(
                    onClick = { showAppPicker.value = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenCard),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text("ВЫБРАТЬ ПРИЛОЖЕНИЯ (${prefs.splitApps().size})",
                        color = Color.White, fontSize = 13.sp)
                }
            }
            Text(
                "Выключено — весь трафик и весь DNS идут в туннель, сайты видят только " +
                "страну сервера.\n\n" +
                "Включено — раздельное туннелирование: РУ-сайты и отмеченные приложения " +
                "ходят напрямую, своим настоящим адресом, а всё остальное остаётся в " +
                "туннеле. Банк, госуслуги и маркетплейс видят обычное подключение из " +
                "России и не поднимают шум — выключать ради них весь VPN не нужно.\n\n" +
                "Приложение, выведенное из туннеля, получает настоящий IP и настоящую " +
                "активную сеть: по адресу его не отличить от «VPN выключен». Но если оно " +
                "специально перебирает сетевые интерфейсы и ищет tun0, оно увидит, что " +
                "VPN на телефоне вообще есть. Спрятать это без root не может никто.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )

            Spacer(Modifier.height(14.dp))
            Text("KILL SWITCH", color = ZenRed, fontWeight = FontWeight.Bold)
            ToggleRow("Рвать трафик, если туннель упал", prefs.killSwitch) { prefs.killSwitch = it }
            Text(
                "Туннель может упасть сам: сервер отвалился, сеть моргнула, ядро умерло. " +
                "Обычно в этот момент трафик молча уходит в открытую сеть с настоящим " +
                "адресом — и именно там вас и видят.\n\n" +
                "Включено — интерфейс остаётся поднятым, но за ним никого нет: пакеты " +
                "умирают в нём. Снаружи это выглядит как «интернета нет», и это правильно: " +
                "лучше без сети, чем с засвеченным адресом. Приложения, выведенные мимо " +
                "туннеля, продолжают работать — иначе защита рубила бы связь как раз тем, " +
                "ради кого делался обход. Выпустить трафик можно только вручную, отключив " +
                "туннель в приложении.\n\n" +
                "Граница честно: это работает, пока живо само приложение. Если система " +
                "выгрузит его целиком, защищать станет некому. От этого спасает только " +
                "системная настройка — «Постоянная VPN» вместе с «Блокировать соединения " +
                "без VPN». Её нельзя включить из приложения, только руками.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )
            Button(
                onClick = {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                    }.onFailure { log("⚠️ Не открылись настройки VPN: ${it.message}") }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ZenCard),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("ОТКРЫТЬ СИСТЕМНЫЕ НАСТРОЙКИ VPN", color = Color.White, fontSize = 13.sp) }
            ToggleRow("Только IPv4 → IPv6", prefs.ipv6) { prefs.ipv6 = it }
            ToggleRow("Авто-реконнект при обрыве", prefs.autoReconnect) { prefs.autoReconnect = it }

            Spacer(Modifier.height(20.dp))
            Text("ВНЕШНИЙ ВИД", color = ZenRed, fontWeight = FontWeight.Bold)
            Text(
                "Надпись сверху настраивается: нажмите прямо на неё. Можно сменить " +
                "шрифт (готика не обязательна), размер или вписать своё название.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
            Button(
                onClick = { showTitleDlg.value = true },
                colors = ButtonDefaults.buttonColors(containerColor = ZenCard),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("НАСТРОИТЬ НАДПИСЬ", color = Color.White, fontSize = 13.sp) }

            // ---------- ОБНОВЛЕНИЯ ----------
            Spacer(Modifier.height(22.dp))
            Text("ОБНОВЛЕНИЯ", color = ZenRed, fontWeight = FontWeight.Bold)
            val upd = com.zen.blaster.core.Updater
            Text("Сейчас стоит ${upd.myName(this@MainActivity)} (сборка ${upd.myCode(this@MainActivity)})",
                color = ZenGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

            var updUrl by remember { mutableStateOf(prefs.updateUrl) }
            OutlinedTextField(
                value = updUrl,
                onValueChange = { updUrl = it; prefs.updateUrl = it },
                placeholder = { Text("https://…/update.json — пусто = не проверять",
                    color = ZenGray, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ZenCyan, unfocusedBorderColor = ZenDivider,
                    cursorColor = ZenCyan
                ),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = { checkUpdate(force = true) },
                colors = ButtonDefaults.buttonColors(containerColor = ZenCard),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("ПРОВЕРИТЬ СЕЙЧАС", color = Color.White, fontSize = 13.sp) }
            Text(
                "Приложение проверяет обновления раз в сутки и, если снаружи есть версия " +
                "новее, показывает кнопку под кольцом. Само оно ничего не ставит: файл " +
                "скачивается, установку подтверждаете вы. Молчаливой подмены сборки в " +
                "антицензурном клиенте быть не должно — это готовый канал для подсовывания " +
                "чужого кода.\n\n" +
                "Ядра Xray и sing-box лежат ВНУТРИ приложения: Android не исполняет " +
                "скачанный после установки код, поэтому ядра приезжают только вместе с " +
                "новым APK. Отдельно и молча обновляются лишь гео-правила — это списки " +
                "доменов, а не код.\n\n" +
                "Ключ подписи тот же, что и у стоящей версии, — обновление ложится " +
                "поверх, серверы и настройки остаются на месте.",
                color = ZenGray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))
            Text("ℹ️ Обход DPI на Android обеспечивают сами протоколы (Reality/Hysteria2+Salamander/Gecko). " +
                "tpws/nfqws на Android недоступны.", color = ZenGray, fontSize = 11.sp)
        }
    }

    @Composable
    private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var checked by remember { mutableStateOf(initial) }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
        }
    }

    @Composable
    private fun LogsTab() {
        val scroll = rememberScrollState()
        LaunchedEffect(logLines.size) { scroll.animateScrollTo(scroll.maxValue) }
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LOGS", color = Color.White, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Тот же разбор, что срабатывает при неудачном подключении,
                // но по нажатию: спросить сеть можно и до того, как всё встало.
                Text("сеть", color = ZenCyan, fontSize = 13.sp,
                    modifier = Modifier.clickable { sniffRegime() })
                Spacer(Modifier.width(14.dp))
                // Спросить не про сеть, а про себя: что о нас видно снаружи.
                // Доступно всегда, а не только при поднятом туннеле — вопрос
                // «а он вообще работает?» возникает именно тогда, когда что-то
                // не так.
                Text("утечки", color = ZenCyan, fontSize = 13.sp,
                    modifier = Modifier.clickable { showLeaks.value = true })
                Spacer(Modifier.width(14.dp))
                Text("падения", color = ZenCyan, fontSize = 13.sp,
                    modifier = Modifier.clickable { dumpCrashLog() })
                Spacer(Modifier.width(14.dp))
                // Журнал переживает закрытие приложения — в отличие от того,
                // что видно на этой вкладке.
                Text("журнал", color = ZenCyan, fontSize = 13.sp,
                    modifier = Modifier.clickable { showJournal.value = true })
                Spacer(Modifier.width(14.dp))
                Text("очистить", color = ZenGray, fontSize = 13.sp,
                    modifier = Modifier.clickable { logLines.clear() })
            }
            Spacer(Modifier.height(12.dp))
            logLines.forEach { Text(it, color = ZenCyan, fontSize = 11.sp) }
        }
    }

    private fun autoSelectBest() {
        val best = nodes.withIndex()
            .filter { it.value.ping in 1..899 && it.value.supported() }
            .minByOrNull { it.value.ping }
        if (best != null) { selected.intValue = best.index; prefs.selectedServer = best.index
            log("🚀 Авто-выбор: ${best.value.name} (${best.value.ping} ms)") }
        else log("⚠️ Нет живых нод")
    }
}
