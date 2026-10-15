package com.zen.blaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

// Модули ядер: подпись манифеста, фильтр, сравнение версий (CoreModules.kt).
class CoreModulesTest {

    private val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun sign(b: ByteArray): String {
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initSign(pair.private); s.update(b)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private val sha = "a".repeat(64)
    private val manifest = """
        {"schema":1,"modules":[
         {"core":"xray","variant":"main","version":"26.11.1","api":1,"abi":"arm64-v8a","file":"xray-26.11.1-arm64-v8a","size":100,"sha256":"$sha","minApp":28},
         {"core":"xray","variant":"main","version":"26.11.1","api":1,"abi":"x86_64","file":"xray-26.11.1-x86_64","size":100,"sha256":"$sha","minApp":28},
         {"core":"xray","variant":"main","version":"27.1.1","api":2,"abi":"arm64-v8a","file":"xray-27-arm64-v8a","size":100,"sha256":"$sha","minApp":28},
         {"core":"awg","variant":"main","version":"v3.2.1","api":2,"abi":"arm64-v8a","file":"awg-v3.2.1-arm64-v8a.so","size":100,"sha256":"$sha","minApp":40},
         {"core":"awg","variant":"legacy","version":"v1.0.5","api":1,"abi":"arm64-v8a","file":"awg1-v1.0.5-arm64-v8a.so","size":100,"sha256":"$sha","minApp":28},
         {"core":"awg","variant":"main","version":"v3.2.2","api":2,"abi":"arm64-v8a","file":"../evil.so","size":100,"sha256":"$sha","minApp":28},
         {"core":"singbox","variant":"main","version":"1.15","api":1,"abi":"arm64-v8a","file":"x","size":100,"sha256":"$sha","minApp":28}
        ]}""".trimIndent()

    @Test fun signatureAcceptedOnlyIntact() {
        val b = manifest.toByteArray()
        val sig = sign(b)
        assertTrue(CoreModules.verify(b, sig, pub))
        // Один изменённый байт — подпись не сходится.
        val tampered = manifest.replace("26.11.1", "26.11.2").toByteArray()
        assertFalse(CoreModules.verify(tampered, sig, pub))
        // Подпись другим ключом.
        val other = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initSign(other.private); s.update(b)
        assertFalse(CoreModules.verify(b, Base64.getEncoder().encodeToString(s.sign()), pub))
        // Мусор вместо подписи.
        assertFalse(CoreModules.verify(b, "AAAA", pub))
    }

    @Test fun parseFiltersForThisPhone() {
        val m = CoreModules.parse(manifest, "arm64-v8a", appCode = 28)
        // Остаются: xray 26.11.1 (api 1), awg1 v1.0.5. Отсеяны: чужая ABI,
        // слишком новый интерфейс (xray api 2), модуль для Zen 40+, путь
        // с «..», неизвестное ядро.
        assertEquals(listOf("xray 26.11.1", "awg1 v1.0.5"), m.map { "${it.slot} ${it.version}" })
    }

    @Test fun versionOrder() {
        assertTrue(CoreModules.newer("26.11.1", "26.10.10"))
        assertTrue(CoreModules.newer("v3.1.20260901", "v3.1.20260828"))
        assertFalse(CoreModules.newer("26.10.10", "26.10.10"))
        assertFalse(CoreModules.newer("26.9.30", "26.10.10"))
        assertTrue(CoreModules.newer("26.10.10.1", "26.10.10"))
    }
}
