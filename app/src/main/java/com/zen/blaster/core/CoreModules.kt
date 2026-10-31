package com.zen.blaster.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================
//  Модули ядер — обновление Xray и AmneziaWG без выпуска нового Zen.
//
//  Зачем. Ядра обновляются чаще приложения, а игра с ТСПУ идёт как раз на
//  их стороне. Пересобирать весь Zen ради новой версии Xray — долго и
//  лишний раз гонять всех через обновление. Модуль — это одно ядро под
//  одну архитектуру, собранное нашим же рецептом (native/modules), с нашей
//  JNI-обёрткой.
//
//  Откуда. Манифест cores.json + подпись cores.json.sig лежат на нашем
//  сервере рядом с обновлениями APK (/updates/zen/cores/) и, по желанию, в
//  любом другом источнике (релизы GitHub) — адрес задаётся в настройках.
//
//  Почему подпись обязательна. Ядро видит ВЕСЬ трафик человека. Модуль без
//  проверки — дверь для любого, кто подменит файл по дороге. Манифест
//  подписан ключом ECDSA P-256, открытая часть зашита ниже; в манифесте —
//  sha256 и размер каждого файла. Скачанное, не сошедшееся до бита, не
//  ставится.
//
//  Как грузится.
//   • AmneziaWG — библиотека: System.load(путь) в процессе :awg вместо
//     встроенной libwg.so. JNI-имена те же (com.zen.blaster.core.AmneziaCore).
//   • Xray — программа. Запускать скачанные программы Android 10+ не даёт
//     (exec из папки приложения запрещён), поэтому запускаем системный
//     загрузчик /system/bin/linker64 и отдаём ему наш файл — он загружает
//     его как библиотеку, а это разрешено. Модуль Xray собран под Android
//     (GOOS=android, PIE), встроенный — как раньше, из nativeLibraryDir.
//
//  Страховка. Встроенные ядра остаются в APK. Модуль, который трижды
//  подряд не поднялся по своей вине (не загрузился, не принял конфиг,
//  процесс умер), выключается сам — Zen возвращается на встроенное и пишет
//  об этом в журнал.
//
//  Состояние — в ФАЙЛЕ cores/active.json, не в настройках: Amnezia живёт в
//  своём процессе, а у SharedPreferences в каждом процессе своя копия.
// ============================================================================
object CoreModules {

    // Открытый ключ подписи манифеста (SubjectPublicKeyInfo, base64). Пара
    // к ~/.zen-nitro-cores.pem на машине сборки. Сменили ключ — новая
    // сборка Zen, иначе модули от нового ключа не примутся.
    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZOE8NSyPGlkGW7//J4rx5BR/OTjn4vc0nCafedO+SSgPsool37TxIl34EmJjFfUcvK4iPQXktZJuwLCWMBWuBQ=="

    // Наш сервер — тот же, что раздаёт обновления самого Zen.
    const val DEFAULT_SOURCE = "https://storage-mess.online/updates/zen/cores/"

    // Какие уровни интерфейса понимает ЭТА сборка Zen. Модуль с уровнем
    // выше — для более нового Zen, ставить его нельзя.
    //   awg 1 — функции 3.4; awg 2 — + awgRebind (смена порта, 3.5).
    //   xray 1 — командная строка «run -c», «version».
    private val API = mapOf("awg" to 2, "xray" to 1)

    // Ключи слотов: основное ядро Amnezia, запасное (AWG 1.x) и Xray.
    const val AWG = "awg"
    const val AWG_LEGACY = "awg1"
    const val XRAY = "xray"

    private const val MAX_FAILS = 3

    data class Module(
        val core: String,        // awg | xray
        val variant: String,     // main | legacy (у xray — main)
        val version: String,
        val api: Int,
        val abi: String,
        val file: String,        // имя файла в источнике
        val size: Long,
        val sha256: String,
        val minApp: Int,
        val notes: String,
        val source: String = "",  // откуда качать: у каждого модуля свой источник
    ) {
        val slot: String get() = when {
            core == "awg" && variant == "legacy" -> AWG_LEGACY
            core == "awg" -> AWG
            else -> XRAY
        }
    }

    data class Active(val version: String, val path: String, val sha256: String, val fails: Int)

    // ------------------------------------------------------------- подпись

    // Подпись — ECDSA P-256 над точными байтами cores.json (DER, base64).
    // SHA256withECDSA есть в Android с самых первых версий — никаких
    // сторонних библиотек.
    fun verify(manifest: ByteArray, sigBase64: String, publicKey: String = PUBLIC_KEY): Boolean = try {
        val kf = java.security.KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(b64(publicKey)))
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub)
        s.update(manifest)
        s.verify(b64(sigBase64.trim()))
    } catch (_: Throwable) { false }

    // java.util.Base64 — с Android 8 (и на JVM в тестах); на 7.x его нет,
    // там системный android.util.Base64.
    private fun b64(s: String): ByteArray = try {
        java.util.Base64.getDecoder().decode(s.trim())
    } catch (_: NoClassDefFoundError) {
        android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT)
    }

    // Разбор манифеста. Чужие ABI, слишком новый интерфейс и модули для
    // более нового Zen отбрасываем здесь же.
    fun parse(json: String, abi: String, appCode: Int): List<Module> = try {
        val arr = JSONObject(json).optJSONArray("modules") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val m = Module(
                core = o.optString("core"), variant = o.optString("variant", "main"),
                version = o.optString("version"), api = o.optInt("api", 1),
                abi = o.optString("abi"), file = o.optString("file"),
                size = o.optLong("size"), sha256 = o.optString("sha256").lowercase(),
                minApp = o.optInt("minApp", 0), notes = o.optString("notes"))
            val maxApi = API[m.core] ?: return@mapNotNull null
            if (m.abi != abi || m.api > maxApi || m.minApp > appCode) return@mapNotNull null
            if (m.version.isEmpty() || m.file.isEmpty() || m.size <= 0 || m.sha256.length != 64) return@mapNotNull null
            if (m.file.contains('/') || m.file.contains("..")) return@mapNotNull null
            m
        }
    } catch (_: Throwable) { emptyList() }

    // Архитектура телефона из тех трёх, под которые собираем.
    fun abi(): String = android.os.Build.SUPPORTED_ABIS
        .firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") } ?: "arm64-v8a"

    // ------------------------------------------------------------- источники

    fun sources(ctx: Context): List<String> {
        val custom = Prefs(ctx).coresSource.trim()
        // http — только до своей машины (127.0.0.1 через adb reverse — проверка
        // свежих модулей до выкладки). Подмены это не открывает: манифест
        // подписан, у каждого файла sha256 — чужое не встанет и по http.
        val local = custom.startsWith("http://127.0.0.1") || custom.startsWith("http://localhost")
        return (listOf(DEFAULT_SOURCE) + listOf(custom).filter { it.startsWith("https://") || local })
            .map { if (it.endsWith("/")) it else "$it/" }.distinct()
    }

    private fun http(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Наш сервер раздаёт обновления только своему клиенту — по имени в
    // User-Agent; без него отвечает 403.
    private fun req(url: String) = okhttp3.Request.Builder().url(url)
        .header("User-Agent", "ZenNitro/${com.zen.blaster.BuildConfig.VERSION_NAME}").build()

    data class Catalog(val source: String, val modules: List<Module>)

    // ВСЕ источники, чьи манифесты скачались И прошли проверку подписи.
    // Неподписанный или битый манифест — как будто источника нет.
    //
    // Раньше брался ПЕРВЫЙ ответивший: пока отвечал наш сервер со старым
    // каталогом, второй источник со свежими ядрами не спрашивался вовсе.
    // Теперь модули всех источников вместе, у каждого — свой адрес, а
    // выбор «что новее» делает тот, кто предлагает (coreOffers).
    fun fetch(ctx: Context, onLog: (String) -> Unit = {}): Catalog? {
        val c = http()
        val all = mutableListOf<Module>()
        var first: String? = null
        for (src in sources(ctx)) {
            try {
                val body = c.newCall(req(src + "cores.json")).execute().use {
                    if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
                    it.body?.bytes() ?: ByteArray(0)
                }
                val sig = c.newCall(req(src + "cores.json.sig")).execute().use {
                    if (!it.isSuccessful) throw java.io.IOException("подпись: HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
                if (!verify(body, sig)) {
                    onLog("⛔ Модули ядер: подпись манифеста не сошлась (${host(src)}) — не беру")
                    continue
                }
                all += parse(String(body), abi(), com.zen.blaster.BuildConfig.VERSION_CODE)
                    .map { it.copy(source = src) }
                if (first == null) first = src
            } catch (t: Throwable) {
                onLog("… модули ядер: ${host(src)} не ответил (${t.message})")
            }
        }
        return first?.let { Catalog(it, all) }
    }

    private fun host(u: String) = try { java.net.URI(u).host } catch (_: Exception) { u }

    // ------------------------------------------------------------- установка

    private fun root(ctx: Context) = File(ctx.filesDir, "cores").apply { mkdirs() }
    private fun stateFile(ctx: Context) = File(root(ctx), "active.json")

    @Synchronized
    private fun readState(ctx: Context): JSONObject =
        try { JSONObject(stateFile(ctx).readText()) } catch (_: Throwable) { JSONObject() }

    @Synchronized
    private fun writeState(ctx: Context, o: JSONObject) {
        val f = stateFile(ctx)
        val tmp = File(f.parentFile, "active.json.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(f)
    }

    // Скачать, сверить до бита, положить и сделать активным.
    // Возвращает null при успехе, иначе — причину словами.
    fun install(ctx: Context, cat: Catalog, m: Module, onProgress: (String) -> Unit = {}): String? {
        val dir = File(root(ctx), "${m.slot}/${m.version}").apply { mkdirs() }
        val dst = File(dir, if (m.core == "xray") "xray" else "libwg.so")
        val tmp = File(dir, "download.tmp")
        try {
            onProgress("Качаю ${m.core} ${m.version}…")
            http().newCall(req(m.source.ifEmpty { cat.source } + m.file)).execute().use { r ->
                if (!r.isSuccessful) return "сервер ответил HTTP ${r.code}"
                val md = java.security.MessageDigest.getInstance("SHA-256")
                var got = 0L
                r.body!!.byteStream().use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            got += n
                            if (got > m.size) return "файл больше заявленного — не беру"
                            md.update(buf, 0, n); out.write(buf, 0, n)
                        }
                    }
                }
                if (got != m.size) return "размер не сошёлся: $got из ${m.size}"
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != m.sha256) return "контрольная сумма не сошлась — файл подменён или побит"
            }
            if (dst.exists()) { dst.setWritable(true); dst.delete() }
            if (!tmp.renameTo(dst)) return "не удалось сохранить файл"
            // Android 14+ требует, чтобы подгружаемый код был только для
            // чтения; для библиотек это не обязательно, но правильно.
            dst.setReadOnly()
            val st = readState(ctx)
            st.put(m.slot, JSONObject().put("version", m.version).put("path", dst.absolutePath)
                .put("sha256", m.sha256).put("fails", 0))
            writeState(ctx, st)
            cleanup(ctx, m.slot, keep = m.version)
            Blackbox.note("🧩 Модуль ядра ${m.slot} ${m.version} поставлен")
            return null
        } catch (t: Throwable) {
            return t.message ?: "ошибка загрузки"
        } finally {
            tmp.delete()
        }
    }

    // Старые версии слота удаляем — кроме активной.
    private fun cleanup(ctx: Context, slot: String, keep: String) {
        File(root(ctx), slot).listFiles()?.forEach { d ->
            if (d.isDirectory && d.name != keep) d.deleteRecursively()
        }
    }

    // Активный модуль слота — только если файл на месте и размер цел.
    fun active(ctx: Context, slot: String): Active? = try {
        val o = readState(ctx).optJSONObject(slot) ?: return null
        val a = Active(o.optString("version"), o.optString("path"), o.optString("sha256"), o.optInt("fails"))
        if (a.path.isEmpty() || !File(a.path).canRead()) null else a
    } catch (_: Throwable) { null }

    // Вернуться на встроенное ядро.
    fun revert(ctx: Context, slot: String, why: String) {
        val st = readState(ctx)
        if (!st.has(slot)) return
        st.remove(slot)
        writeState(ctx, st)
        File(root(ctx), slot).deleteRecursively()
        Blackbox.note("↩️ Модуль ядра $slot выключен ($why) — работаю на встроенном")
    }

    // Итог запуска ядра из модуля. Считаем только отказы САМОГО ядра — не
    // поднялась нода, сервер молчит, сеть пропала — это не повод снимать.
    // Возвращает true, если модуль после этого выключен.
    fun report(ctx: Context, slot: String, ok: Boolean, why: String = ""): Boolean {
        val st = readState(ctx)
        val o = st.optJSONObject(slot) ?: return false
        if (ok) {
            if (o.optInt("fails") != 0) { o.put("fails", 0); writeState(ctx, st) }
            return false
        }
        val fails = o.optInt("fails") + 1
        if (fails >= MAX_FAILS) {
            revert(ctx, slot, "$fails отказа подряд: $why")
            return true
        }
        o.put("fails", fails)
        writeState(ctx, st)
        return false
    }

    // Модуль, который новее того, что стоит сейчас (модуль или встроенное).
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v').split('.', '-').map { it.toLongOrNull() ?: 0L }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
