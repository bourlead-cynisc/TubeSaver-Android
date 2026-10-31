package com.example.tubesaver

import org.junit.Assert.*
import org.junit.Test

class SizeEstimatorTest {
    private fun fmt(h: Int = 0, ext: String = "mp4", v: Boolean = true,
                    a: Boolean = false, br: Double? = null, exact: Long? = null,
                    approx: Long? = null) =
        SizeEstimator.Format(h, ext, v, a, br, exact, approx)

    @Test fun exactIsPreferredToEstimate() {
        assertEquals(SizeEstimator.Size(50_000_000, false),
            SizeEstimator.measure(fmt(720, br = 4000.0, exact = 50_000_000, approx = 40_000_000), 200.0))
    }
    @Test fun bitrateTimesDurationIsApproximate() {
        val formats = listOf(fmt(1080, br = 2000.0),
            fmt(ext = "m4a", v = false, a = true, br = 128.0))
        assertEquals(SizeEstimator.Size(53_200_000, true), SizeEstimator.select(formats, 200.0, 1080))
    }
    @Test fun exactVideoAndAudioAreSummed() {
        val formats = listOf(fmt(1080, exact = 80_000_000),
            fmt(ext = "m4a", v = false, a = true, exact = 8_000_000),
            fmt(1080, a = true, exact = 45_000_000))
        assertEquals(SizeEstimator.Size(88_000_000, false), SizeEstimator.select(formats, 60.0, 1080))
    }
    @Test fun cappedSelectionUsesLowerHeight() {
        val formats = listOf(fmt(2160, exact = 200_000_000),
            fmt(720, exact = 50_000_000),
            fmt(ext = "m4a", v = false, a = true, exact = 5_000_000))
        assertEquals(SizeEstimator.Size(55_000_000, false), SizeEstimator.select(formats, 120.0, 1080))
    }
    @Test fun absentBitrateAndDurationIsUnknown() {
        assertNull(SizeEstimator.measure(fmt(1080), null))
    }
    @Test fun suppliedApproximationIsLabeled() {
        assertEquals(SizeEstimator.Size(54_000_000, true),
            SizeEstimator.measure(fmt(360, approx = 54_000_000), 100.0))
    }
    @Test fun combinedMp4IsSupported() {
        assertEquals(SizeEstimator.Size(17_000_000, false),
            SizeEstimator.select(listOf(fmt(480, a = true, exact = 17_000_000)), 100.0, 480))
    }
}
