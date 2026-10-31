package com.example.tubesaver

import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test fun secondStreamDoesNotRestartProgress() {
        val m = TransferProgress(null, false, 2)
        val first = m.read("download:OFFTUBE_DATA:80|100|NA|20|1")!!
        val second = m.read("download:OFFTUBE_DATA:10|100|NA|20|5")!!
        assertTrue(second.percent >= first.percent)
        assertTrue(second.message.contains("МБ"))
    }
    @Test fun bytesSpeedAndEtaShown() {
        val m = TransferProgress(20_000_000L, true, 1)
        val p = m.read("download:OFFTUBE_DATA:5000000|20000000|NA|1000000|15")!!
        assertTrue(p.message.contains("МБ/с"))
        assertTrue(p.message.contains("≈"))
        assertTrue(p.percent in 1..95)
    }
    @Test fun finalProgressIsLessThan100UntilSaved() {
        val m = TransferProgress(100L, false, 1)
        assertEquals(95, m.read("OFFTUBE_DATA:100|100|NA|100|0")!!.percent)
    }
}