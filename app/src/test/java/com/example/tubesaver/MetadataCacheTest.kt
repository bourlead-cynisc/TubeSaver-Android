package com.example.tubesaver

import org.junit.Assert.*
import org.junit.Test

class MetadataCacheTest {
    @Test fun metadataRemainsAvailableForImmediateDownload() {
        val now = 2_000_000L
        assertTrue(MetadataCache.validAge(now - 1_000L, now))
        assertTrue(MetadataCache.validAge(now - 90_000L, now))
    }

    @Test fun expiredOrFutureUrlsAreNotUsed() {
        val now = 2_000_000L
        assertFalse(MetadataCache.validAge(now - 90_001L, now))
        assertFalse(MetadataCache.validAge(now + 1_000L, now))
        assertFalse(MetadataCache.validAge(0L, now))
    }
}
