package io.github.romanvht.byedpi.core

// ============================================================================
//  JNI-мост к ядру ByeDPI (libbyedpi.so).
//
//  ВАЖНО: имя пакета и класса менять НЕЛЬЗЯ — символы в нативной библиотеке
//  жёстко привязаны к нему (Java_io_github_romanvht_byedpi_core_ByeDpiProxy_*).
//
//  Ядро ByeDPI (C, лицензия MIT) — github.com/hufrea/byedpi,
//  Android-обвязка — github.com/romanvht/ByeByeDPI.
// ============================================================================
class ByeDpiProxy {

    companion object {
        init { System.loadLibrary("byedpi") }
    }

    // Запускает прокси. БЛОКИРУЮЩИЙ вызов — крутится, пока прокси жив.
    // args — аргументы командной строки ByeDPI (argv, включая argv[0]).
    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    external fun jniForceClose(): Int

    fun startProxy(args: Array<String>): Int = jniStartProxy(args)
    fun stopProxy(): Int = jniStopProxy()
}
