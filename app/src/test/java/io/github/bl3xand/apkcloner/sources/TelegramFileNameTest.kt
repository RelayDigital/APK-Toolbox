package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.telegram.TelegramFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramFileNameTest {
    private fun family(name: String) = TelegramFileName.family(name)

    /** Names really posted in channels, each with the kind, title and version it is read as. */
    @Test
    fun realNamesAreReadAsRecorded() {
        val lines = javaClass.getResourceAsStream("/telegram_file_names.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size > 500)
        for (line in lines) {
            val (name, kind, title, version) = line.split('\t')
            val parsed = TelegramFileName.parse(name)
            assertEquals(name, kind, parsed.family)
            assertEquals(name, title, parsed.title)
            assertEquals(name, version, parsed.version ?: "-")
        }
    }

    @Test
    fun releasesOfOneAppStayTogether() {
        assertEquals(family("TikTok_You-4.4-(46.8.3).apk"), family("TikTok_You-4.5-(47.0.1).apk"))
        assertEquals(family("TikTok_You-1.2.4-(43.9.3, Beta).apk"), family("TikTok_You-1.0.0-(Beta).apk"))
        assertEquals(family("Instander ver.17.2 Clone.apk"), family("Instander ver.13.1-clone.apk"))
        assertEquals(family("SD Maid SE_2.2.0-rc0.apk"), family("SD Maid SE_1.7.4-rc0.apk"))
        assertEquals(family("Nova Launcher-88700 (8.8.7)-079r302842 (1).apk"), family("Nova Launcher-81018 (8.2.8)-96cr264778.apk"))
        assertEquals(family("Psiphon Pro-464-d15r286697 (1).apk"), family("Psiphon Pro-432-4b9r236435.apk"))
        assertEquals(family("Subnautica+1.22.83416-@EasyAPK.apk"), family("Subnautica+1.23-@EasyAPK.apk"))
        assertEquals(family("1.1.1.1_6.38.9.apk"), family("1.1.1.1_6.38.7.apk"))
        assertEquals(family("Grok-1.1.29-release.08-980r269809.apk"), family("Grok_1.2.30-release.01.apk"))
        assertEquals(family("Wavelet_26.05(260508).apk"), family("Wavelet_26.07(260711).apk"))
    }

    @Test
    fun differentAppsAndBuildsStayApart() {
        assertNotEquals(family("TikTok_You-4.4-(46.8.3).apk"), family("TikTok_You-1.2.4-(43.9.3, Beta).apk"))
        assertNotEquals(family("Instander ver.17.2.apk"), family("Instander ver.17.2 Clone.apk"))
        assertNotEquals(family("LADB_2.6-arm64-v8a.apk"), family("LADB_2.6-armeabi-v7a.apk"))
        assertNotEquals(family("LADB_2.6-arm64-v8a.apk"), family("LADB_2.6-universal.apk"))
        assertNotEquals(family("Duolingo Max v6.100.3.apk"), family("Duolingo Pro v5.168.2.apk"))
        assertNotEquals(family("TikTok-39.7.6.apk"), family("TikTok Lite-46.8.2-1a5r317288.apk"))
        assertNotEquals(family("husi_1.2.5.apk"), family("husi_1.2.5_(armeabi-v7a).apk"))
    }

    @Test
    fun versionAndTitleAreRead() {
        assertEquals("4.4-46.8.3", TelegramFileName.version("TikTok_You-4.4-(46.8.3).apk"))
        assertEquals("TikTok You", TelegramFileName.title("TikTok_You-4.4-(46.8.3).apk"))
        assertEquals("TikTok You (Beta)", TelegramFileName.title("TikTok_You-1.2.4-(43.9.3, Beta).apk"))
        assertEquals("Instander (Clone)", TelegramFileName.title("Instander ver.17.2 Clone.apk"))
        assertEquals("17.2", TelegramFileName.version("Instander ver.17.2 Clone.apk"))
        assertEquals("9.0-21", TelegramFileName.version("Anixart_9.0 BETA 21.apk"))
        assertEquals("LADB (arm64-v8a)", TelegramFileName.title("LADB_2.6-arm64-v8a.apk"))
        assertEquals("486", TelegramFileName.version("Psiphon Pro v486.apk"))
        assertNull(TelegramFileName.version("Telegram.apk"))
        assertEquals("tiktok you", TelegramFileName.appOf(family("TikTok_You-1.0.0-(Beta).apk")))
    }
}
