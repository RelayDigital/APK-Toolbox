package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.telegram.TelegramFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramFileNameTest {
    @Test
    fun versionIsReadFromTheName() {
        assertEquals("1.2.3", TelegramFileName.version("App_1.2.3.apk"))
        assertEquals("1.2.3", TelegramFileName.version("App-v1.2.3-release.apk"))
        assertEquals("4.4-46.8.3", TelegramFileName.version("TikTok You 4.4 (46.8.3).apk"))
        assertEquals("57", TelegramFileName.version("build_57.apk"))
        assertNull(TelegramFileName.version("Telegram.apk"))
    }

    @Test
    fun architectureIsNotAVersion() {
        assertEquals("1.5", TelegramFileName.version("app-arm64-v8a-1.5.apk"))
        assertEquals("1.5", TelegramFileName.version("app_1.5_x86_64.apk"))
        assertEquals("2.0", TelegramFileName.version("app-armeabi-v7a-2.0.apk"))
    }

    @Test
    fun filesOfOneKindShareAFamilyWhateverTheVersion() {
        assertEquals(TelegramFileName.family("App_1.2.3_arm64.apk"), TelegramFileName.family("app_1.3.0_arm64.apk"))
        assertEquals(TelegramFileName.family("TikTok You 4.4 (46.8.3).apk"), TelegramFileName.family("TikTok You 4.5 (46.9.1).apk"))
    }

    @Test
    fun differentKindsAreDifferentFamilies() {
        assertNotEquals(TelegramFileName.family("App_1.2_arm64-v8a.apk"), TelegramFileName.family("App_1.2_armeabi-v7a.apk"))
        assertNotEquals(TelegramFileName.family("App_1.2.apk"), TelegramFileName.family("App_Lite_1.2.apk"))
        // The author started naming files differently: it no longer counts as the same thing.
        assertNotEquals(TelegramFileName.family("App_1.2.apk"), TelegramFileName.family("App-release-1.3.apk"))
        assertNotEquals(TelegramFileName.family("App_1.2.apk"), TelegramFileName.family("App_1.2.xapk"))
    }

    @Test
    fun patternShowsWhereTheVersionWas() {
        assertEquals("TikTok You * (*).apk", TelegramFileName.pattern("TikTok You 4.4 (46.8.3).apk"))
        assertEquals("app-arm64-v8a-*.apk", TelegramFileName.pattern("app-arm64-v8a-1.5.apk"))
        assertEquals("Telegram.apk", TelegramFileName.pattern("Telegram.apk"))
    }
}
