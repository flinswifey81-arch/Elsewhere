package com.example

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.ui.components.parseInlineMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun inlineMarkdownProducesStyledTextWithoutChangingRawSource() {
        val raw = "*action* **strong** ***both*** ~~gone~~ `code`"

        val rendered = parseInlineMarkdown(raw)

        assertEquals("action strong both gone code", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic })
        assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun incompleteStreamingMarkdownRemainsVisibleAndDoesNotThrow() {
        val rendered = parseInlineMarkdown("*He starts an unfinished action")

        assertEquals("*He starts an unfinished action", rendered.text)
    }
}
