package com.zen.blaster.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// ============================================================================
//  Обновления — как в HAPP: приложение САМО ничего не ставит.
//
//  Оно только ходит за списком версий и, если снаружи лежит что-то новее,
//  зажигает кнопку. Дальше решает человек. Молчаливой подмены нет и не будет:
//  антицензурный клиент, который сам себе перезаписывает код, — это готовый
//  канал для подмены сборки, и доверять ему нельзя даже своему.
//
//  Что реально обновляемо:
//    • САМО ПРИЛОЖЕНИЕ — APK ставится поверх, ключ подписи тот же.
//    • ГЕО-ПРАВИЛА (.srs) — обычные файлы данных, тянутся отдельно и дёшево.
//    • ЯДРА Xray и sing-box — НЕТ. Это нативные библиотеки внутри APK, и
//      Android не даёт исполнять код, скачанный после установки. Ядра
//      приезжают только вместе с приложением — это ограничение системы,
//      а не лень.
// ============================================================================
object Updater {

    // Что лежит снаружи.
    data class Release(
        val code: Int,          // versionCode новой сборки
        val name: String,       // versionName, для показа человеку
        val notes: String,      // что нового, одной строкой
        val url: String,        // APK под АРХИТЕКТУРУ этого телефона
        val size: Long          // байт, 0 — если в манифесте не указано
    )

    // Представляемся во ВСЕХ запросах канала, не только в проверке версии.
    //
    // На сервере мессенджера стоит фильтр, который отбивает запросы без
    // имени и с именами сканеров (curl, wget, python-requests). Безымянный
    // клиент однажды попадёт под ту же гребёнку — а заодно по имени видно
    // в журнале сервера, кто тянет сборки.
    private val UA = "ZenNitro"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", UA).build()
            )
        }
        .build()

    // Раз в сутки. Чаще нет смысла, а на мобильном трафике каждый лишний
    // запрос — это ещё и лишний след наружу.
    private const val PERIOD_MS = 22L * 60 * 60 * 1000

    fun myCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Throwable) { 0 }

    fun myName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    // Последнее, что мы знаем о новой версии, — без похода в сеть.
    // Нужно при запуске: кнопка должна быть на месте сразу, а не через
    // секунды ожидания ответа сервера.
    fun cached(ctx: Context): Release? {
        val p = Prefs(ctx)
        if (p.updCode <= myCode(ctx) || p.updUrl.isEmpty()) return null
        return Release(p.updCode, p.updName, p.updNotes, p.updUrl, p.updSize)
    }

    // Возвращает null, если обновления нет ИЛИ проверять ещё рано.
    // force = true — человек нажал «обновить» руками, идём в сеть сразу.
    suspend fun check(ctx: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        val src = p.updateUrl.trim()
        if (src.isEmpty()) return@withContext null

        val now = System.currentTimeMillis()
        // Раз в сутки — но ТОЛЬКО пока нам нечего показывать.
        //
        // Если предложение уже висит, переспрашиваем при каждом запуске.
        // Невыполненное обещание надо перепроверять: сборку могли отозвать,
        // манифест поправить, а человек тем временем уже поставил новую
        // версию другим путём — и кнопка «Доступно обновление» осталась бы
        // висеть сутки, ведя в никуда. Один запрос на запуск того стоит.
        if (!force && cached(ctx) == null && now - p.updCheckedAt < PERIOD_MS)
            return@withContext null

        var httpCode = 0
        val body = try {
            http.newCall(Request.Builder().url(src).build())
                .execute().use { r ->
                    httpCode = r.code
                    if (r.isSuccessful) r.body?.string().orEmpty() else ""
                }
        } catch (_: Exception) { "" }

        if (body.isBlank()) {
            // Разделяем «не смог спросить» и «спросил, ответили что нечего».
            //
            // 4xx — по этому адресу канала обновлений нет (снесли файл, сменили
            // адрес, закрыли доступ). Прошлое предложение забываем, иначе
            // кнопка «Доступно обновление» висела бы вечно и вела в никуда.
            //
            // Обрыв связи (код 0) или 5xx — сервер жив или просто не отвечает
            // сейчас. Знание сохраняем: сеть у нас лежит регулярно, и терять
            // из-за этого готовое обновление незачем.
            if (httpCode in 400..499) { p.updCheckedAt = now; p.forgetUpdate(); return@withContext null }
            return@withContext cached(ctx)
        }

        p.updCheckedAt = now
        val o = try { JSONObject(body) } catch (_: Exception) { return@withContext cached(ctx) }

        // Гео-правила живут своей жизнью: их можно обновить прямо сейчас,
        // молча и без спроса — это просто списки доменов, не код.
        try { syncGeo(ctx, o.optJSONObject("geo")) } catch (_: Throwable) {}

        val code = o.optInt("versionCode", 0)
        val url = pickApk(o.optJSONObject("apk"))
        if (code <= myCode(ctx) || url.isEmpty()) {
            // Свежее некуда — стираем прошлое предложение, иначе кнопка
            // «Доступно обновление» останется висеть после установки.
            p.forgetUpdate()
            return@withContext null
        }

        p.updCode = code
        p.updName = o.optString("versionName", "")
        p.updNotes = o.optString("notes", "")
        p.updUrl = url
        p.updSize = o.optLong("size", 0L)
        cached(ctx)
    }

    // APK собираются на каждую архитектуру отдельно (иначе три ядра мёртвым
    // грузом). Берём тот, что подходит ИМЕННО этому телефону, а universal —
    // только как последнее средство.
    private fun pickApk(apk: JSONObject?): String {
        if (apk == null) return ""
        for (abi in Build.SUPPORTED_ABIS) {
            val u = apk.optString(abi, "")
            if (u.isNotEmpty()) return u
        }
        return apk.optString("universal", "")
    }

    // ------------------------------------------------------------ гео-правила
    //
    // Файлы .srs читает само ядро с диска, поэтому подменить их можно на
    // живую — достаточно положить новые рядом. Пишем через .tmp и только
    // потом переименовываем: оборванная закачка не должна оставить ядру
    // обрубок вместо списка доменов.
    private fun syncGeo(ctx: Context, geo: JSONObject?) {
        if (geo == null) return
        val p = Prefs(ctx)
        val ver = geo.optInt("version", 0)
        if (ver <= p.geoVersion) return
        val files = geo.optJSONObject("files") ?: return
        val dir = File(ctx.filesDir, "geo").apply { mkdirs() }
        var ok = 0
        val it = files.keys()
        while (it.hasNext()) {
            val name = it.next()
            // Имя приходит снаружи — в каталог выше по дереву оно не уедет.
            if (name.contains('/') || name.contains("..")) continue
            val url = files.optString(name, "")
            if (url.isEmpty()) continue
            val tmp = File(dir, "$name.tmp")
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val bytes = r.body?.bytes() ?: return@use
                    if (bytes.size < 100) return@use          // пустышка вместо правил
                    tmp.writeBytes(bytes)
                    if (tmp.renameTo(File(dir, name))) ok++
                }
            } catch (_: Exception) {} finally { tmp.delete() }
        }
        if (ok > 0) p.geoVersion = ver
    }

    // ---------------------------------------------------------------- закачка
    //
    // Кладём во «внешние файлы приложения»: оттуда установщик системы может
    // прочитать файл по FileProvider, а при удалении приложения он уберётся
    // сам, не оставляя 25 МБ мусора в памяти телефона.
    fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "update/ZenNitro-update.apk")

    // onProgress(0..100); -1 — размер неизвестен, показываем просто «качаю».
    suspend fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val out = apkFile(ctx)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".part")
            var total = 0L
            try {
                http.newCall(Request.Builder().url(rel.url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val body = r.body ?: return@withContext null
                    // Длину берём ИЗ ОТВЕТА, а не из манифеста: размер в
                    // манифесте один на все архитектуры, а APK у arm64, arm32
                    // и x86_64 разного веса — проценты бы врали, а проверка
                    // целостности резала бы годную закачку.
                    val declared = body.contentLength()
                    total = if (declared > 0) declared else rel.size
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                    // Дёргаем UI только на смене процента,
                                    // а не 400 раз в секунду.
                                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                                } else onProgress(-1)
                            }
                        }
                    }
                }
                // Явная проверка: оборванная закачка почти всегда короче.
                if (total > 0 && tmp.length() < total) { tmp.delete(); return@withContext null }
                out.delete()
                if (!tmp.renameTo(out)) { tmp.delete(); return@withContext null }
                out
            } catch (_: Exception) { tmp.delete(); null }
        }
}
