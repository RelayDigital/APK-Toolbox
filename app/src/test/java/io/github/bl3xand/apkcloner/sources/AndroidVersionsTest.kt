package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.androidSdkOf
import io.github.bl3xand.apkcloner.sources.core.androidVersionName
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidVersionsTest {
    @Test
    fun versionsAsStoresWriteThem() {
        assertEquals(26, androidSdkOf("8.0"))
        assertEquals(26, androidSdkOf("8"))
        assertEquals(27, androidSdkOf("8.1"))
        assertEquals(19, androidSdkOf("4.4.2"))
        assertEquals(23, androidSdkOf("6.0"))
        assertEquals(34, androidSdkOf("14"))
        assertEquals(32, androidSdkOf("12L"))
        assertEquals(null, androidSdkOf("soon"))
    }

    @Test
    fun namesOfLevels() {
        assertEquals("8.1", androidVersionName(27))
        assertEquals("API 99", androidVersionName(99))
    }
}
