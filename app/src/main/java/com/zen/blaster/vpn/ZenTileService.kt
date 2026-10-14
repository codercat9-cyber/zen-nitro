package com.zen.blaster.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.zen.blaster.MainActivity
import com.zen.blaster.core.Prefs

// ============================================================================
//  Плитка в шторке: включить/выключить туннель, не открывая приложение.
//  Как у v2RayTun, Hiddify и самой Amnezia.
//
//  Живёт в основном процессе, поэтому состояние sing-box видит напрямую через
//  ZenVpnService.running, а состояние ядра Amnezia (оно в процессе :awg) —
//  через флаг в настройках.
// ============================================================================
class ZenTileService : TileService() {

    override fun onStartListening() { sync() }

    // Плитка живёт, пока открыта шторка, поэтому отложенные проверки шлём
    // на главный поток и снимаем их в onStopListening.
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onClick() {
        val prefs = Prefs(this)
        if (isOn(prefs)) {
            stopAll(prefs)
            // Гасим плитку СРАЗУ, не спрашивая состояние.
            //
            // Раньше здесь звался sync(): он читал ZenVpnService.running в тот
            // же миг, когда команда «стоп» только ушла в сервис и ещё не была
            // обработана. Флаг был ещё true — плитка оставалась гореть, хотя
            // туннель уже падал, и висела включённой до закрытия шторки.
            // Ровно это и выглядит как «отжал, VPN выключился, а значок горит».
            showState(false, "выключаю…")
            resyncLater()
            return
        }

        // Разрешение VPN и первый выбор сервера можно дать только в приложении.
        if (VpnService.prepare(this) != null || !hasConfig(prefs)) { openApp(); return }

        if (prefs.engine == 1) {
            ContextCompat.startForegroundService(this,
                Intent(this, ZenAwgService::class.java).apply {
                    action = ZenAwgService.ACTION_START
                    putExtra(ZenAwgService.EXTRA_PROFILE, prefs.lastAwg)
                })
        } else {
            ContextCompat.startForegroundService(this,
                Intent(this, ZenVpnService::class.java).apply {
                    action = ZenVpnService.ACTION_START
                    putExtra(ZenVpnService.EXTRA_CONFIG, prefs.lastConfig)
                    putExtra(ZenVpnService.EXTRA_DPI, prefs.lastDpi)
                    putExtra(ZenVpnService.EXTRA_DPI_STRATEGY, prefs.dpiStrategy)
                    if (prefs.lastXray.isNotEmpty())
                        putExtra(ZenVpnService.EXTRA_XRAY, prefs.lastXray)
                })
        }
        // Плитку помечаем «включается» сразу, а точное состояние перепроверяем
        // сами: ждать следующего открытия шторки нельзя — человек смотрит на
        // неё прямо сейчас, и застывшая надпись выглядит как зависание.
        showState(true, "включаю…")
        resyncLater()
    }

    override fun onStopListening() { ui.removeCallbacksAndMessages(null) }

    // Перепроверяем реальное состояние несколько раз: ядро поднимается
    // секунды, а остановка — доли секунды. Одной проверки мало для обоих.
    private fun resyncLater() {
        ui.removeCallbacksAndMessages(null)
        for (d in longArrayOf(1200, 3000, 6000, 10000)) ui.postDelayed({ sync() }, d)
    }

    private fun showState(on: Boolean, hint: String) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "ZEN NITRO"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = hint
            updateTile()
        }
    }

    // Состояние берём не только из статики: процесс приложения система может
    // прибить, и тогда ZenVpnService.running врёт «выключено», хотя туннель
    // системе известен. Поэтому дополнительно спрашиваем саму систему.
    private fun isOn(prefs: Prefs) =
        ZenVpnService.running || prefs.awgRunning || ownVpnUp()

    // Поднят ли ИМЕННО НАШ туннель.
    //
    // Раньше здесь считался включённым любой VPN-транспорт в системе. Если у
    // человека работал чужой клиент, плитка показывала «включено», а клик по
    // ней уходил в stopAll — то есть в попытку выключить то, чего мы не
    // включали. Со стороны это выглядело так: жмёшь плитку, и ничего не
    // происходит, либо она гаснет сама. Теперь сверяем владельца сети:
    // ownerUid заполняется только для собственных сетей приложения.
    private fun ownVpnUp(): Boolean = try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val me = android.os.Process.myUid()
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || c.ownerUid == me)
        }
    } catch (_: Throwable) { false }

    private fun hasConfig(prefs: Prefs) =
        if (prefs.engine == 1) prefs.lastAwg.isNotEmpty() else prefs.lastConfig.isNotEmpty()

    private fun stopAll(prefs: Prefs) {
        startService(Intent(this, ZenVpnService::class.java).apply { action = ZenVpnService.ACTION_STOP })
        if (prefs.awgRunning)
            ZenAwgService.stopAll(this)
    }

    private fun sync() {
        val prefs = Prefs(this)
        val on = isOn(prefs)
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "ZEN NITRO"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                subtitle = if (on) "включён" else "выключен"
            updateTile()
        }
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // На Android 14+ старый startActivityAndCollapse(Intent) кидает
            // исключение — нужен именно PendingIntent.
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
