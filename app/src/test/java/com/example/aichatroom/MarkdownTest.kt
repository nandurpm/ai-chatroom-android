package com.example.aichatroom

import androidx.compose.ui.graphics.Color
import com.example.aichatroom.ui.*
import org.junit.Assert.*
import org.junit.Test

/** Parser tests protect literal code copying and graceful behavior for unfinished model output. */
class MarkdownTest {
    @Test fun parsesRequiredBlockTypes() {
        val blocks = MarkdownParser.parse("# Title\n- one\n2. two\n| A | B |\n| --- | --- |\n| 1 | 2 |\n```kotlin\nval x = 1\n```")
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals("•", (blocks[1] as MarkdownBlock.ListItem).marker)
        assertEquals("2.", (blocks[2] as MarkdownBlock.ListItem).marker)
        assertEquals(2, (blocks[3] as MarkdownBlock.Table).rows.size)
        assertEquals("val x = 1", (blocks[4] as MarkdownBlock.Code).text)
    }
    @Test fun codePreservesWhitespaceAndDoesNotParseMarkdownInside() {
        val code = MarkdownParser.parse("```text\n  **literal**\n\n  last\n```").single() as MarkdownBlock.Code
        assertEquals("  **literal**\n\n  last", code.text)
    }
    @Test fun unclosedFenceRemainsReadableAndCopyable() {
        assertEquals("unfinished", (MarkdownParser.parse("~~~js\nunfinished").single() as MarkdownBlock.Code).text)
    }
    @Test fun emphasisAndEscapesHaveReadableText() {
        val result = MarkdownParser.inline("**bold** *italic* `code` \\*literal\\*")
        assertEquals("bold italic code *literal*", result.text); assertEquals(3, result.spanStyles.size)
    }
    @Test fun escapedTablePipesDoNotCreateExtraCells() {
        val table = MarkdownParser.parse("| A | B |\n| --- | --- |\n| a\\|b | c |").single() as MarkdownBlock.Table
        assertEquals(listOf("a|b", "c"), table.rows.last())
    }
    @Test fun highlightingNeverChangesCopiedCode() {
        val code = "val x = \"string\" // comment"
        val highlighted = highlightCode(code, Color.Blue, Color.Green, Color.Gray)
        assertEquals(code, highlighted.text); assertEquals(3, highlighted.spanStyles.size)
    }
}
