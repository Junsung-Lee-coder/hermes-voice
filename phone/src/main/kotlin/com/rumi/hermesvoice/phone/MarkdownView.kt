package com.rumi.hermesvoice.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rumi.hermesvoice.core.text.Markdown
import com.rumi.hermesvoice.core.text.MdAlign
import com.rumi.hermesvoice.core.text.MdBlock
import com.rumi.hermesvoice.core.text.MdInline
import com.rumi.hermesvoice.core.text.MdRun

private const val MD_URL_TAG = "URL"

/**
 * The Phone's presentation of a Markdown message, one composable per block. It only draws: the stored text is untouched, links open
 * only on a short tap through [onOpenLink] (which validates again), images are never loaded, and nothing here runs a script or a
 * web view. The first block carries [tag] (as the plain message text always did); later and nested blocks add a suffix.
 */
@Composable
internal fun MarkdownBlocks(blocks: List<MdBlock>, tag: String, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block -> MarkdownBlockView(block, if (index == 0) tag else "${tag}_b$index", onOpenLink) }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock, tag: String, onOpenLink: (String) -> Unit) {
    when (block) {
        is MdBlock.Paragraph -> MarkdownText(block.inlines, LocalTextStyle.current, tag, onOpenLink)
        is MdBlock.Heading -> {
            val typography = MaterialTheme.typography
            val style = when (block.level) {
                1 -> typography.titleLarge
                2 -> typography.titleMedium
                3 -> typography.titleSmall
                else -> typography.bodyLarge
            }
            MarkdownText(block.inlines, style.merge(TextStyle(fontWeight = FontWeight.Bold, color = LocalContentColor.current)), tag, onOpenLink)
        }
        is MdBlock.Quote -> {
            val bar = LocalContentColor.current.copy(alpha = 0.45f)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(bar, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.85f)) {
                    MarkdownBlocks(block.blocks, tag, onOpenLink, Modifier.weight(1f))
                }
            }
        }
        is MdBlock.ListBlock -> Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
            block.items.forEachIndexed { index, item ->
                val marker = when {
                    item.check == true -> "☑"
                    item.check == false -> "☐"
                    block.ordered -> "${block.start + index}."
                    else -> "•"
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(marker, Modifier.widthIn(min = 22.dp).padding(end = 6.dp))
                    MarkdownBlocks(item.blocks, "${tag}_i$index", onOpenLink, Modifier.weight(1f))
                }
            }
        }
        is MdBlock.CodeBlock -> Box(
            Modifier.fillMaxWidth()
                .background(LocalContentColor.current.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                .padding(8.dp),
        ) {
            Text(
                block.text.ifEmpty { " " },
                Modifier.testTag(tag),
                style = LocalTextStyle.current.merge(TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = LocalContentColor.current)),
            )
        }
        is MdBlock.Table -> MarkdownTable(block, tag, onOpenLink)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(LocalContentColor.current.copy(alpha = 0.35f)))
    }
}

/** Every cell's text is kept; a wide table scrolls sideways inside its own box instead of widening the message. */
@Composable
private fun MarkdownTable(table: MdBlock.Table, tag: String, onOpenLink: (String) -> Unit) {
    val line = LocalContentColor.current.copy(alpha = 0.35f)
    val widths = remember(table) {
        table.aligns.indices.map { column ->
            val longest = (listOf(table.header) + table.rows).maxOf { row -> Markdown.plainText(row.getOrElse(column) { emptyList() }).length }
            (longest.coerceIn(4, 28) * 8 + 16).dp
        }
    }
    val rows = listOf(table.header) + table.rows
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("${tag}_scroll")) {
        Column(Modifier.border(1.dp, line)) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.forEachIndexed { c, cell ->
                        val align = when (table.aligns.getOrElse(c) { MdAlign.START }) {
                            MdAlign.CENTER -> TextAlign.Center
                            MdAlign.END -> TextAlign.End
                            MdAlign.START -> TextAlign.Start
                        }
                        val base = LocalTextStyle.current.merge(TextStyle(textAlign = align, fontWeight = if (r == 0) FontWeight.Bold else null))
                        Box(Modifier.width(widths[c]).fillMaxHeight().border(0.5.dp, line).padding(horizontal = 6.dp, vertical = 4.dp)) {
                            MarkdownText(cell, base, "${tag}_r${r}c$c", onOpenLink)
                        }
                    }
                }
            }
        }
    }
}

/** One run of inline Markdown as selectable text; links open on a short tap only (a long press starts the selection instead). */
@Composable
internal fun MarkdownText(inlines: List<MdInline>, style: TextStyle, tag: String, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val codeBackground = LocalContentColor.current.copy(alpha = 0.14f)
    val runs = remember(inlines) { Markdown.runs(inlines) }
    val annotated = remember(runs, codeBackground) { annotateRuns(runs, codeBackground) }
    val links = remember(runs) { runs.mapNotNull { it.url }.distinct() }
    val full = style.merge(TextStyle(color = LocalContentColor.current))
    if (links.isEmpty()) {
        Text(annotated, modifier.testTag(tag), style = full)
        return
    }
    val actions = remember(links) {
        links.map { url -> CustomAccessibilityAction("Open link to ${LinkOpener.hostOf(url)}") { onOpenLink(url); true } }
    }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        modifier.testTag(tag).semantics { customActions = actions }.pointerInput(annotated) {
            detectTapGestures(
                onLongPress = { },
                onTap = { position -> layout.value?.let { result -> annotated.urlAt(result.getOffsetForPosition(position))?.let(onOpenLink) } },
            )
        },
        style = full,
        onTextLayout = { layout.value = it },
    )
}

internal fun annotateRuns(runs: List<MdRun>, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val decorations = ArrayList<TextDecoration>(2)
        if (run.strike) decorations += TextDecoration.LineThrough
        if (run.url != null) decorations += TextDecoration.Underline
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else if (run.url != null) FontWeight.SemiBold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) codeBackground else Color.Unspecified,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        )
        if (run.url != null) pushStringAnnotation(MD_URL_TAG, run.url!!)
        pushStyle(style)
        append(run.text)
        pop()
        if (run.url != null) pop()
    }
}

/** A just-sent message in the same Markdown presentation as the stored one it becomes (no selection, as before). */
@Composable
internal fun SentMessageText(text: String, onOpenLink: (String) -> Unit = {}) {
    val blocks = remember(text) { Markdown.parse(text) }
    if (blocks == null) Text(text) else MarkdownBlocks(blocks, "sent_markdown", onOpenLink)
}
