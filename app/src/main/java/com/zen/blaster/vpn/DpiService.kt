package com.zen.blaster.vpn

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.romanvht.byedpi.core.ByeDpiProxy
import kotlin.concurrent.thread

// ============================================================================
//  DPI-щит в СВОЁМ процессе.
//
//  ByeDPI — чужой код на C, и он падает. Системный архив падений телефона за
//  сентябрь 2026 показал два случая, оба внутри libbyedpi.so:
//
//   • 29.09 — «fdsan: attempted to close file descriptor 258 … owned by
//     SocketImpl». Щит закрыл свой сокет дважды. Между двумя закрытиями наш
//     же Java-код получил тот же номер под свой сокет, и щит закрыл чужой.
//     Android такое ловит и убивает процесс.
//   • 01.10 — SIGSEGV в mem_add ← on_trigger: обращение к почти-нулевому
//     указателю в авто-режиме щита.
//
//  Пока щит жил внутри приложения, его падение уносило ВСЁ: приложение,
//  ядро туннеля, сам туннель — человек оставался без связи до ручного
//  перезапуска. Чинить чужой C-код изнутри не наш путь. Наш путь — тот же,
//  что уже работает для Xray и AmneziaWG: отдельный процесс.
//
//  Теперь падение щита убивает только этот процесс. Туннель живёт дальше, а
//  система, раз мы держим привязку с BIND_AUTO_CREATE, сама поднимает процесс
//  заново — и щит запускается снова на том же порту. Отдельно от этого
//  у щита ещё и пропадает способ навредить: в своём процессе ему просто
//  нечего закрыть, кроме собственных сокетов.
//
//  Управляется через привязку (ByeDpi.start / ByeDpi.stop), а не через
//  startService: привязка с BIND_IMPORTANT отдаёт этому процессу приоритет
//  самого VPN-сервиса, и система не убьёт его раньше туннеля.
// ============================================================================
class DpiService : Service() {

    override fun onBind(intent: Intent): IBinder {
        val args = intent.getStringArrayExtra(EXTRA_ARGS) ?: arrayOf("ciadpi")
        thread(name = "byedpi", isDaemon = true) {
            try {
                // Блокируется, пока прокси жив. Если вернулся сам — значит не
                // смог встать (порт, аргументы). Процесс при этом не гасим:
                // иначе система тут же поднимала бы его заново, и щит падал бы
                // по кругу. Основной процесс увидит, что порт не слушает.
                val code = ByeDpiProxy().startProxy(args)
                android.util.Log.w(TAG, "ByeDPI завершился сам, код $code")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "ByeDPI: ${t.message}")
            }
        }
        return Binder()
    }

    // Привязку сняли — щит больше не нужен. Гасим процесс целиком: JNI-цикл
    // ByeDPI иначе надёжно не остановить, а порт должен освободиться сразу,
    // чтобы следующий запуск встал на него без ожидания.
    override fun onUnbind(intent: Intent): Boolean {
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }

    companion object {
        const val EXTRA_ARGS = "args"
        private const val TAG = "ZenDpi"
    }
}
