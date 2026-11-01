package com.zen.blaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ссылки из проверки на эмуляторе 01.11.2026: Reality с Finalmask (fm=…) и
// голый VLESS по mKCP. Обе должны разобраться в ноды, fm — дойти до ноды JSON-ом.
class FinalmaskLinkTest {
    private val withFm = "vless://3f1c2a4e-8b7d-4c21-9e55-0a6b7c8d9e10@203.0.113.26:443?security=reality&sni=www.microsoft.com&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12&flow=xtls-rprx-vision&type=tcp&fp=chrome&fm=%7B%22tcp%22%3A%5B%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%22tlshello%22%2C%22lengths%22%3A%5B%223-5%22%2C%226-8%22%5D%2C%22delays%22%3A%5B%2210-20%22%5D%7D%7D%5D%7D#ZEN-TEST-finalmask"
    private val bare = "vless://3f1c2a4e-8b7d-4c21-9e55-0a6b7c8d9e10@203.0.113.23:4443?security=none&type=kcp&headerType=wechat-video&seed=s33d#ZEN-TEST-kcp"

    @Test fun finalmaskLinkParses() {
        val n = Subscriptions.decode(withFm).single()
        assertEquals("ZEN-TEST-finalmask", n.name)
        assertTrue(n.fm, n.fm.startsWith("{\"tcp\":[{\"type\":\"fragment\""))
        assertTrue(XrayEngine.handles(n))
    }

    @Test fun bareKcpLinkParses() {
        val n = Subscriptions.decode(bare).single()
        assertEquals("kcp", n.transport)
        assertEquals("none", n.security)
    }
}
