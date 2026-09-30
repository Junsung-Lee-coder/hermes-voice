package com.rumi.hermesvoice.watch

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.WatchVoiceStatus
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderHistory
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderScrollPolicy
import com.rumi.hermesvoice.core.watchlink.ReaderSessionsState
import com.rumi.hermesvoice.core.watchlink.RotaryScrollDriver
import com.rumi.hermesvoice.core.watchlink.SwipeDirection
import com.rumi.hermesvoice.core.watchlink.SwipeTracker
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import kotlinx.coroutines.launch

/**
 * Root horizontal-swipe detector. It watches the Final pass, after the lists and buttons had
 * their turn: a gesture a list already scrolled with (consumed movement) or that starts vertical
 * is never a swipe; once horizontal travel dominates past the touch slop it consumes the rest, which
 * cancels list scrolling and button taps for that gesture (see [SwipeTracker]).
 */
fun Modifier.readerSwipe(onSwipe: (SwipeDirection) -> Unit): Modifier = pointerInput(Unit) {
    val minDistance = 48.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val tracker = SwipeTracker(viewConfiguration.touchSlop, minDistance)
        var claimed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val travel = change.position - down.position
            if (!change.pressed) {
                if (claimed) change.consume()
                tracker.onUp(travel.x, travel.y)?.let(onSwipe)
                break
            }
            claimed = tracker.onMove(travel.x, travel.y, consumedByOther = !claimed && change.isConsumed)
            if (claimed) change.consume()
        }
    }
}

/**
 * Bezel/crown scrolling for one list: rotary events reach the focused list and go through
 * [RotaryScrollDriver], which coalesces them while a scroll runs (nothing is lost, and a cancelled
 * scroll drops its leftovers) and asks for one short tick only when the list really moved.
 */
@Composable
fun Modifier.rotaryScroll(state: LazyListState, focusRequester: FocusRequester, onScrollStep: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val driver = remember { RotaryScrollDriver() }
    return onRotaryScrollEvent { event ->
        if (driver.offer(event.verticalScrollPixels)) {
            scope.launch { driver.drain({ px -> state.scrollBy(px) }, SystemClock::uptimeMillis, onScrollStep) }
        }
        true
    }.focusRequester(focusRequester).focusable()
}

private val SentBubble = Color(0xFF8AB4F8)
private val OnSentBubble = Color(0xFF0B1D3A)
private val ReceivedBubble = Color(0xFF303134)
private val OnReceivedBubble = Color(0xFFE8EAED)

/** Sent (the user's own) messages right-aligned in blue; received ones left-aligned in grey. */
@Composable
private fun Bubble(message: ReaderMessageRow) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.sent) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 150.dp)
                .background(if (message.sent) SentBubble else ReceivedBubble,
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (message.sent) 12.dp else 3.dp, bottomEnd = if (message.sent) 3.dp else 12.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp)
                .testTag(if (message.sent) "bubble_sent" else "bubble_received"),
        ) {
            Text(message.text + if (message.truncated) " (more on phone)" else "",
                color = if (message.sent) OnSentBubble else OnReceivedBubble, style = MaterialTheme.typography.caption2)
        }
    }
}

fun readerErrorText(error: String?): String = when (error) {
    ReaderError.OFFLINE -> "Phone not reachable"
    ReaderError.SIGN_IN_REQUIRED -> "Sign in on the phone"
    ReaderError.NOT_CONFIGURED -> "Set up Hermes on the phone"
    ReaderError.TIMEOUT -> "Phone did not answer"
    ReaderError.NOT_OWNED -> "Conversation not available"
    else -> "Could not load"
}

@Composable
private fun StatusText(text: String, error: Boolean = false) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
        style = MaterialTheme.typography.caption3)
}

/** The selected conversation: history bubbles, "load older" at the top, Talk controls overlaid at the bottom. */
@Composable
fun ChatSurface(
    title: String,
    history: ReaderHistory,
    focusRequester: FocusRequester,
    onOlder: () -> Unit,
    onRetry: () -> Unit,
    onScrollStep: () -> Unit,
    talkControls: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    val ids = history.messages.map { it.rowId }
    var previousIds by remember(history.sessionId) { mutableStateOf(emptyList<Long>()) }
    LaunchedEffect(history.sessionId, ids) {
        // "At latest" = the previously newest message is still on screen; older pages never pull the view down.
        val wasAtLatest = previousIds.lastOrNull()?.let { newest -> listState.layoutInfo.visibleItemsInfo.any { it.key == newest } } ?: true
        if (ReaderScrollPolicy.followLatest(wasAtLatest, previousIds, ids)) listState.scrollToItem(ids.size + 1)
        previousIds = ids
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().rotaryScroll(listState, focusRequester, onScrollStep).testTag("chat_list"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 70.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    when {
                        history.loadingOlder -> StatusText("Loading older…")
                        history.olderBlocked -> StatusText("Older messages are on the phone")
                        history.hasOlder -> CompactChip(onClick = onOlder, label = { Text("Load older") },
                            colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.testTag("load_older"))
                    }
                }
            }
            items(history.messages, key = { it.rowId }) { Bubble(it) }
            item(key = "footer") {
                when {
                    history.status == LoadStatus.LOADING && !history.loadingOlder -> StatusText(if (ids.isEmpty()) "Loading…" else "Updating…")
                    history.status == LoadStatus.OFFLINE || history.status == LoadStatus.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        StatusText(readerErrorText(history.error), error = true)
                        CompactChip(onClick = onRetry, label = { Text("Retry") }, colors = ChipDefaults.secondaryChipColors())
                    }
                    ids.isEmpty() && history.status == LoadStatus.READY -> StatusText("No messages yet")
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { talkControls() }
    }
}

/** App-owned active conversations; tapping one opens it in chat. */
@Composable
fun SessionsSurface(
    sessions: ReaderSessionsState,
    selectedId: String?,
    focusRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onScrollStep: () -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().rotaryScroll(listState, focusRequester, onScrollStep).testTag("session_list"),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "header") {
            Text("Conversations", style = MaterialTheme.typography.title3, color = MaterialTheme.colors.onBackground)
        }
        item(key = "status") {
            when (sessions.status) {
                LoadStatus.LOADING -> StatusText(if (sessions.rows.isEmpty()) "Loading…" else "Updating…")
                LoadStatus.OFFLINE, LoadStatus.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusText(readerErrorText(sessions.error), error = true)
                    CompactChip(onClick = onRefresh, label = { Text("Retry") }, colors = ChipDefaults.secondaryChipColors())
                }
                LoadStatus.READY -> if (sessions.rows.isEmpty()) StatusText("No conversations. Create one on the phone.")
                LoadStatus.IDLE -> Unit
            }
        }
        items(sessions.rows, key = { it.id }) { row ->
            Chip(
                onClick = { onSelect(row.id) },
                label = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(row.preview.ifBlank { row.alias }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = if (row.id == selectedId) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().testTag("session_${row.alias}"),
            )
        }
    }
}

/** Big Talk/Send button used when no conversation is open. */
@Composable
fun TalkHome(phone: Boolean?, talk: WatchTalkState, wakeEnabled: Boolean, wakeListening: Boolean, wakeUnavailable: Boolean,
             background: WatchVoiceStatus, onTalk: () -> Unit, onBackground: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            when (phone) {
                true -> "Phone connected"
                false -> "Phone not reachable"
                null -> "Checking phone…"
            },
            color = if (phone == false) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
            style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
        )
        TalkButton(talk, size = 88, onTalk)
        Text(
            talk.line.ifBlank {
                when {
                    wakeListening -> "Listening for the wake phrase…"
                    wakeEnabled && wakeUnavailable -> "Wake phrase unavailable on this watch. Tap to talk"
                    wakeEnabled -> "Say the wake phrase or tap"
                    else -> "Tap to talk"
                }
            },
            color = MaterialTheme.colors.onBackground, style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center, maxLines = 3,
        )
        // The background session: what it really does now, and what a tap does. Started and stopped only here (or Stop in its notification).
        Text("${BackgroundText.watchLabel(background.session, background.loop)}\n${BackgroundText.watchAction(background.session, background.notification)}",
            Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colors.surface)
                .clickable(onClick = onBackground).padding(horizontal = 8.dp, vertical = 2.dp).testTag("background"),
            color = if (background.session.running) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurfaceVariant,
            style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center, maxLines = 3)
        Text("Swipe left for conversations", color = MaterialTheme.colors.onSurfaceVariant,
            style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center)
    }
}

/** Compact Talk/Send with the talk status line, overlaid on the chat. */
@Composable
fun CompactTalk(talk: WatchTalkState, wakeListening: Boolean, onTalk: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val line = talk.line.ifBlank { if (wakeListening) "Listening for the wake phrase…" else "" }
        if (line.isNotBlank()) {
            Text(line, Modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = MaterialTheme.colors.onBackground, style = MaterialTheme.typography.caption3, maxLines = 2,
                textAlign = TextAlign.Center)
        }
        TalkButton(talk, size = 46, onTalk)
    }
}

@Composable
private fun TalkButton(talk: WatchTalkState, size: Int, onTalk: () -> Unit) {
    val recording = talk.phase == WatchPhase.RECORDING
    Button(onClick = onTalk, modifier = Modifier.padding(vertical = 4.dp).size(size.dp).testTag("talk"),
        enabled = talk.phase != WatchPhase.SENDING,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (recording) MaterialTheme.colors.error else MaterialTheme.colors.primary)) {
        Text(if (recording) "Send" else "Talk", style = if (size > 60) MaterialTheme.typography.title3 else MaterialTheme.typography.caption1)
    }
}
