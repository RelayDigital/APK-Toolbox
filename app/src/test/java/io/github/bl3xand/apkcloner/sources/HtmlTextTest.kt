package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.htmlToText
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    @Test
    fun breaksBecomeLines() {
        assertEquals(
            "Added:\n\n• Added support for custom fonts\n\nFixed:\n\n• Fixed overlap/truncation in stopwatch lap times",
            htmlToText("Added:<br><br>• Added support for custom fonts<br><br>Fixed:<br><br>• Fixed overlap/truncation in stopwatch lap times <br><br>"),
        )
    }

    @Test
    fun tagsAndEntities() {
        assertEquals("• One & two\n• <three>", htmlToText("<ul><li>One &amp; two</li><li>&lt;three&gt;</li></ul>"))
        assertEquals("a b’c", htmlToText("<p>a&nbsp;b&#8217;c</p>"))
    }

    @Test
    fun plainTextIsLeftAlone() {
        val notes = "В этом обновлении исправлены ошибки.\nВторая строка."
        assertEquals(notes, htmlToText(notes))
    }
}
