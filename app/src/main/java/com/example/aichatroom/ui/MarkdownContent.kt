package com.example.aichatroom.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A small, explicit Markdown subset avoids adding a parser, WebView or syntax engine to the APK. */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
}
object MarkdownParser {
    private fun cells(line: String) = line.trim().trim('|').split(Regex("(?<!\\\\)\\|"))
        .map { it.trim().replace("\\|", "|") }
    private fun separator(line: String) = cells(line).all { it.matches(Regex(":?-{3,}:?")) }
    fun parse(text: String): List<MarkdownBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = Regex("^\\s*(`{3,}|~{3,})(.*)$").matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val code = mutableListOf<String>(); i++
                while (i < lines.size && !lines[i].trim().matches(Regex("${Regex.escape(marker[0].toString())}{${marker.length},}"))) code += lines[i++]
                if (i < lines.size) i++
                out += MarkdownBlock.Code(fence.groupValues[2].trim(), code.joinToString("\n")); continue
            }
            if (i + 1 < lines.size && line.contains('|') && separator(lines[i + 1])) {
                val rows = mutableListOf(cells(line)); i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++])
                out += MarkdownBlock.Table(rows); continue
            }
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            val item = Regex("^\\s*([-+*]|\\d+[.)])\\s+(.+)$").matchEntire(line)
            when {
                heading != null -> out += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                item != null -> out += MarkdownBlock.ListItem(if (item.groupValues[1].length == 1) "•" else item.groupValues[1], item.groupValues[2])
                line.isNotBlank() -> out += MarkdownBlock.Paragraph(line)
            }
            i++
        }
        return out
    }
    /** Bounded recursion handles nested emphasis; unknown syntax stays visible as ordinary text. */
    fun inline(text: String, depth: Int = 0): AnnotatedString = buildAnnotatedString {
        if (depth > 8) { append(text); return@buildAnnotatedString }
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) { append(text[i + 1]); i += 2; continue }
            val marker = listOf("***", "**", "__", "*", "_", "`").firstOrNull { text.startsWith(it, i) }
            val end = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            if (marker != null && end > i + marker.length) {
                val inner = text.substring(i + marker.length, end)
                val style = when (marker) {
                    "***" -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "`" -> SpanStyle(fontFamily = FontFamily.Monospace)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) { append(if (marker == "`") AnnotatedString(inner) else inline(inner, depth + 1)) }
                i = end + marker.length
            } else { append(text[i]); i++ }
        }
    }
}

/** Lightweight lexical highlighting covers common keywords, numbers, strings and comments.
 * It is intentionally not a compiler: unsupported languages still get readable, copyable code.
 */
fun highlightCode(code: String, accent: Color, stringColor: Color, commentColor: Color): AnnotatedString {
    val tokens = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b(?:fun|val|var|class|object|if|else|return|import|from|def|const|let|function|true|false|null|None|public|private|suspend|for|while|SELECT|FROM|WHERE)\\b|\\b\\d+(?:\\.\\d+)?\\b")
    return buildAnnotatedString {
        append(code)
        tokens.findAll(code).forEach { match ->
            val color = when {
                match.value.startsWith('"') || match.value.startsWith('\'') -> stringColor
                match.value.startsWith("//") || match.value.startsWith('#') -> commentColor
                else -> accent
            }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}

@Composable
fun MarkdownContent(text: String) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> SelectionContainer { Text(MarkdownParser.inline(block.text)) }
                is MarkdownBlock.Heading -> Text(MarkdownParser.inline(block.text), fontWeight = FontWeight.Bold,
                    style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                is MarkdownBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker); Text(MarkdownParser.inline(block.text), Modifier.weight(1f))
                }
                is MarkdownBlock.Code -> CodeBlock(block)
                is MarkdownBlock.Table -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                    val count = block.rows.maxOfOrNull { it.size } ?: 0
                    Column {
                        block.rows.forEachIndexed { index, row ->
                            Surface(color = if (index == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
                                Row { repeat(count) { cell -> Text(MarkdownParser.inline(row.getOrElse(cell) { "" }),
                                    Modifier.width(170.dp).padding(10.dp), fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal) } }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifBlank { "Code" }, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) { Text(if (copied) "Copied" else "Copy code") }
            }
            SelectionContainer {
                Text(highlightCode(block.text, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.onSurfaceVariant), Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace)
            }
        }
    }
}
