package com.rumi.hermesvoice.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.AttachmentRefs
import com.rumi.hermesvoice.core.text.MessageLinks
import com.rumi.hermesvoice.core.text.MessageSegment

/**
 * Opens a validated http/https link in the user's own browser (a plain ACTION_VIEW: no embedded browser, no script bridge, no
 * Custom Tabs, no extras; nothing of the app's sign-in is ever attached). Only called from an explicit tap.
 */
internal object LinkOpener {
    const val NOT_OPENABLE = "That link can't be opened"
    const val NO_HANDLER = "No app can open this link"

    fun intentFor(url: String): Intent? {
        val safe = MessageLinks.safeUrl(url) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE)
    }

    /** Null when the link was handed to the system, otherwise the visible reason it was not. */
    fun open(context: Context, url: String, start: (Intent) -> Unit = context::startActivity): String? {
        val intent = intentFor(url) ?: return NOT_OPENABLE
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            start(intent)
            null
        } catch (_: ActivityNotFoundException) {
            NO_HANDLER
        } catch (_: SecurityException) {
            NO_HANDLER
        }
    }

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').take(80)
}

/**
 * A chat message: its text exactly as stored, selectable with the platform's long-press handles and Copy / Select all, with
 * http(s) links tappable (underlined, with an "open link" accessibility action each) and a button for each stored file it names.
 *
 * Selection is per message (its own [SelectionContainer]), so a LazyColumn row scrolling away never leaves a half selection
 * behind and no parent row intercepts the gesture. A link opens on a short tap only: a long press (the start of a selection)
 * is swallowed by the link's gesture handler and never opens it. The file buttons sit outside the selectable region.
 */
@Composable
internal fun MessageBody(
    text: String,
    tag: String,
    onOpenLink: (String) -> Unit,
    onOpenAttachment: (AttachmentRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { MessageLinks.segments(text) }
    val refs = remember(text) { AttachmentRefs.extract(text) }
    val links = remember(segments) { segments.filterIsInstance<MessageSegment.Link>() }
    Column(modifier.fillMaxWidth()) {
        SelectionContainer {
            if (links.isEmpty()) {
                Text(text, Modifier.testTag(tag))
            } else {
                val annotated = remember(segments) { annotateLinks(segments) }
                val actions = remember(links) {
                    links.map { link -> CustomAccessibilityAction("Open link to ${LinkOpener.hostOf(link.url)}") { onOpenLink(link.url); true } }
                }
                LinkText(annotated, onOpenLink, Modifier.testTag(tag).semantics { customActions = actions })
            }
        }
        DisableSelection {
            refs.forEachIndexed { index, ref ->
                OutlinedButton(
                    onClick = { onOpenAttachment(ref) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_file_$index").semantics { contentDescription = "View attachment ${ref.name}" },
                ) {
                    Text("📎 ${ref.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Selectable text whose links open on a short tap only; the empty long-press handler makes a long press never count as a tap. */
@Composable
private fun LinkText(text: AnnotatedString, onOpenLink: (String) -> Unit, modifier: Modifier) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        modifier = modifier.pointerInput(text) {
            detectTapGestures(
                onLongPress = { },
                onTap = { position -> layout.value?.let { result -> text.urlAt(result.getOffsetForPosition(position))?.let(onOpenLink) } },
            )
        },
        style = LocalTextStyle.current.merge(TextStyle(color = LocalContentColor.current)),
        onTextLayout = { layout.value = it },
    )
}

private const val URL_TAG = "URL"

/** The message text exactly as written, with each link's own characters tagged (and underlined) as its address. */
internal fun annotateLinks(segments: List<MessageSegment>): AnnotatedString = buildAnnotatedString {
    segments.forEach { segment ->
        if (segment is MessageSegment.Link) {
            pushStringAnnotation(URL_TAG, segment.url)
            pushStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold))
            append(segment.text)
            pop()
            pop()
        } else {
            append(segment.text)
        }
    }
}

internal fun AnnotatedString.urlAt(offset: Int): String? =
    getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.item
