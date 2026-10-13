package com.rumi.hermesvoice.watch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.rumi.hermesvoice.core.text.Markdown
import com.rumi.hermesvoice.core.text.MarkdownLines

/**
 * The Watch's compact reading of a message: Markdown becomes one styled column (bold / italic / strike / monospace, bullets,
 * quote bars, tables as one line per row). Text without Markdown is returned unchanged, so existing and new messages read alike.
 * Links are shown as their label only; opening one stays a Phone action.
 */
internal fun watchMessageText(text: String, truncated: Boolean, codeBackground: Color): AnnotatedString {
    val suffix = if (truncated) " (more on phone)" else ""
    val blocks = Markdown.parse(text) ?: return AnnotatedString(text + suffix)
    val lines = MarkdownLines.lines(blocks)
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            line.forEach { run ->
                val decorations = ArrayList<TextDecoration>(2)
                if (run.strike) decorations += TextDecoration.LineThrough
                if (run.url != null) decorations += TextDecoration.Underline
                pushStyle(
                    SpanStyle(
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        fontFamily = if (run.code) FontFamily.Monospace else null,
                        background = if (run.code) codeBackground else Color.Unspecified,
                        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                    ),
                )
                append(run.text)
                pop()
            }
        }
        append(suffix)
    }
}
