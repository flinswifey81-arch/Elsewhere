package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val blocks = markdownBlocks(markdown)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Blank -> Spacer(Modifier.height(4.dp))
                is MarkdownBlock.Rule -> HorizontalDivider(color = color.copy(alpha = 0.35f))
                is MarkdownBlock.Code -> Text(
                    text = block.content,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
                is MarkdownBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    )
                    Text(
                        text = parseInlineMarkdown(block.content),
                        color = color.copy(alpha = 0.9f),
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                is MarkdownBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(block.marker, color = color, modifier = Modifier.width(24.dp))
                    Text(parseInlineMarkdown(block.content), color = color, modifier = Modifier.weight(1f))
                }
                is MarkdownBlock.Paragraph -> Text(parseInlineMarkdown(block.content), color = color)
            }
        }
    }
}

internal fun parseInlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val tokens = listOf(
        "***" to SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
        "___" to SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
        "**" to SpanStyle(fontWeight = FontWeight.Bold),
        "__" to SpanStyle(fontWeight = FontWeight.Bold),
        "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough),
        "`" to SpanStyle(fontFamily = FontFamily.Monospace),
        "*" to SpanStyle(fontStyle = FontStyle.Italic),
        "_" to SpanStyle(fontStyle = FontStyle.Italic)
    )

    var index = 0
    while (index < source.length) {
        val token = tokens.firstOrNull { (delimiter, _) -> source.startsWith(delimiter, index) }
        if (token == null) {
            append(source[index])
            index++
            continue
        }

        val (delimiter, style) = token
        val contentStart = index + delimiter.length
        val close = source.indexOf(delimiter, contentStart)
        if (close < 0 || close == contentStart) {
            append(delimiter)
            index = contentStart
            continue
        }

        pushStyle(style)
        append(source.substring(contentStart, close))
        pop()
        index = close + delimiter.length
    }
}

private sealed interface MarkdownBlock {
    data object Blank : MarkdownBlock
    data object Rule : MarkdownBlock
    data class Code(val content: String) : MarkdownBlock
    data class Quote(val content: String) : MarkdownBlock
    data class ListItem(val marker: String, val content: String) : MarkdownBlock
    data class Paragraph(val content: String) : MarkdownBlock
}

private fun markdownBlocks(markdown: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val code = mutableListOf<String>()
    var inFence = false

    fun flushCode() {
        if (code.isNotEmpty() || inFence) {
            result += MarkdownBlock.Code(code.joinToString("\n"))
            code.clear()
        }
    }

    markdown.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inFence) flushCode()
            inFence = !inFence
            return@forEach
        }
        if (inFence) {
            code += line
            return@forEach
        }

        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> result += MarkdownBlock.Blank
            trimmed.matches(Regex("^([-*_])\\1{2,}$")) -> result += MarkdownBlock.Rule
            line.trimStart().startsWith(">") -> result += MarkdownBlock.Quote(line.trimStart().removePrefix(">").trimStart())
            line.matches(Regex("^\\s*[-+*]\\s+.+$")) -> result += MarkdownBlock.ListItem("•", line.replaceFirst(Regex("^\\s*[-+*]\\s+"), ""))
            line.matches(Regex("^\\s*\\d+[.)]\\s+.+$")) -> {
                val marker = Regex("^\\s*(\\d+[.)])\\s+").find(line)?.groupValues?.get(1) ?: "1."
                result += MarkdownBlock.ListItem(marker, line.replaceFirst(Regex("^\\s*\\d+[.)]\\s+"), ""))
            }
            else -> result += MarkdownBlock.Paragraph(line)
        }
    }
    if (inFence) flushCode()
    return result.ifEmpty { listOf(MarkdownBlock.Paragraph("")) }
}
