package com.zen.blaster.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Адреса — из живой проверки 14.10.2026 (см. Cloudflare.kt).
class CloudflareTest {
    @Test fun cloudflareAddresses() {
        for (ip in listOf("104.16.132.229", "104.16.124.96", "162.159.128.233", "172.67.1.1",
                "188.114.97.3", "2606:4700::6810:84e5"))
            assertTrue(ip, Cloudflare.owns(ip))
    }

    @Test fun othersAreNot() {
        for (ip in listOf("142.251.152.119", "213.133.116.44", "77.88.44.242", "1.0.0.1",
                "1.1.1.1", "104.15.255.255", "104.32.0.0", "2a01:4f8::1", "cdn.example.com", ""))
            assertFalse(ip, Cloudflare.owns(ip))
    }

    @Test fun fingerprints() {
        for (fp in listOf("chrome", "randomized", "random", "", null)) assertTrue("$fp", Cloudflare.risky(fp))
        for (fp in listOf("firefox", "edge", "safari", "ios", "android", "Firefox")) assertFalse(fp, Cloudflare.risky(fp))
    }
}
