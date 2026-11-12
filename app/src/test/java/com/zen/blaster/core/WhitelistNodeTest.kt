package com.zen.blaster.core

import com.zen.blaster.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Белосписочные ноды (11–12.11.2026): узнаём их по названию, подписке и
// разрешённому имени — и не режем им приветствие.
class WhitelistNodeTest {

    private val uuid = "3f1c2a4e-8b7d-4c21-9e55-0a6b7c8d9e10"
    private val pbk = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"

    private fun reality(name: String, sni: String, sub: String = "") =
        Node("VLESS", name, "81.94.148.186", 1202, uuid = uuid, sni = sni, pbk = pbk, sid = "ab12",
            flow = "xtls-rprx-vision", security = "reality", fp = "chrome", sub = sub)

    @Test fun namesFromRealSubscriptions() {
        assertTrue(reality("🇫🇷 Белые списки #1", "www.microsoft.com").wlNamed())
        assertTrue(reality("🇸🇴 АВТО ОБХОД ( БС ) 🐾", "www.microsoft.com").wlNamed())
        assertTrue(reality("🇦🇱🌐 AUTO Белые списки | Умный выбор", "a.example").wlNamed())
        assertTrue(reality("node", "a.example", sub = "🔲WL | siriusvpn_1158883_1").wlNamed())
        assertTrue(reality("node", "a.example", sub = "⚪ Белый список (аварийные)").wlNamed())
        // «БС» и «WL» — только отдельным словом.
        assertFalse(reality("🇸🇴 ОБХОД № 3 🐾", "a.example").wlNamed())
        assertFalse(reality("БСД-сервер", "a.example").wlNamed())
        assertFalse(reality("Bowling", "a.example").wlNamed())
    }

    @Test fun permittedSniMeansWholeHello() {
        assertTrue(reality("x", "x5media.ru").wlLike())
        assertTrue(reality("x", "eh7.vk.com").wlLike())
        assertTrue(reality("x", "cloud.mail.ru").wlLike())
        assertFalse(reality("x", "www.microsoft.com").wlLike())
        assertFalse(reality("x", "api.ferrin.uk").wlLike())
        // Российский SNI сам по себе — ещё не «годится для белых списков»:
        // сервер Reality с avito.ru бывает за границей.
        assertTrue(reality("x", "www.avito.ru").wlLike())
        assertFalse(reality("x", "www.avito.ru").wlCapable())
        assertTrue(reality("x", "sun9-1.userapi.com").wlCapable())
    }

    private fun dialer(n: Node): String =
        XrayEngine.buildConfig(n, armor = true, fp = "chrome").getJSONArray("outbounds").getJSONObject(0)
            .optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy").orEmpty()

    @Test fun noFragmentForWhitelistNodes() {
        XrayEngine.fragOn = true
        assertEquals(XrayEngine.FRAG_TAG, dialer(reality("обычная", "www.microsoft.com")))
        assertEquals("", dialer(reality("🇫🇷 Белые списки #1", "x5media.ru")))
        assertEquals("", dialer(reality("какая-то", "cloud.mail.ru")))
        assertEquals("", dialer(reality("БС-нода", "www.microsoft.com", sub = "🔲WL | sirius")))
    }
}
