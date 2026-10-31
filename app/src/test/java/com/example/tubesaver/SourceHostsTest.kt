package com.example.tubesaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceHostsTest {
    @Test fun supportsKnownHosts() {
        assertEquals(SourceHosts.Platform.YOUTUBE, SourceHosts.detect("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(SourceHosts.Platform.RUTUBE, SourceHosts.detect("https://rutube.ru/video/example/"))
        assertEquals(SourceHosts.Platform.VK, SourceHosts.detect("https://vkvideo.ru/video-1_2"))
    }
    @Test fun rejectsLookalikesAndUnencryptedLinks() {
        assertNull(SourceHosts.detect("http://youtube.com/watch?v=1"))
        assertNull(SourceHosts.detect("https://youtube.com.attacker.invalid/watch?v=1"))
        assertNull(SourceHosts.detect("javascript:alert(1)"))
    }
}
