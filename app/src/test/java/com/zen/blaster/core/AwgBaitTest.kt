package com.zen.blaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Грамматика — как у Xray 26 (infra/conf/transport_finalmask.go, parseNoiseExp)
// и у I1–I5 в AmneziaWG 1.5: битая приманка роняет весь конфиг Xray.
class AwgBaitTest {
    @Test fun validBaits() {
        assertTrue(AwgBait.valid("<b 0xc70000000108ce1bc3a4><c><t><r 50>"))
        assertTrue(AwgBait.valid("<r 16-64>"))
        assertTrue(AwgBait.valid(" <b 0x0d0a> <rc 8> <rd 4> "))
        assertTrue(AwgBait.valid("<b c7 00 00 00 01>"))   // пробелы внутри hex ядро склеивает
    }

    @Test fun invalidBaits() {
        assertFalse(AwgBait.valid(""))
        assertFalse(AwgBait.valid("<b 0xabc>"))           // нечётное число hex-знаков
        assertFalse(AwgBait.valid("<b 0xzz>"))
        assertFalse(AwgBait.valid("<r>"))                 // размер обязателен
        assertFalse(AwgBait.valid("<r 70000>"))           // больше 65535
        assertFalse(AwgBait.valid("<r 64-16>"))           // перевёрнутый диапазон
        assertFalse(AwgBait.valid("<t 5>"))               // <t> без аргумента
        assertFalse(AwgBait.valid("<x 1>"))               // неизвестный тег
        assertFalse(AwgBait.valid("мусор<r 8>"))          // текст между тегами
    }

    @Test fun noiseMaskShape() {
        val m = AwgBait.noiseMask(listOf("<r 8>"))
        assertEquals("noise", m.getString("type"))
        val items = m.getJSONObject("settings").getJSONArray("noise")
        assertEquals(4, items.length())                   // приманка + три мусорных
        assertEquals("exp", items.getJSONObject(0).getString("type"))
        assertEquals("40-120", items.getJSONObject(1).getString("rand"))
    }
}
