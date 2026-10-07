package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.PlayProgress
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strict wire form of a Watch playback progress report (new in v19 self-check R1; the Phone also declares its manifest message filter). */
class PlayProgressWireTest {
    @Test
    fun `a valid report round-trips and every invalid one is refused`() {
        val ok = PlayProgress("turn-0001", 3, 1_500, 60_000)
        assertEquals(ok, PlayProgress.decode(ok.encode()))
        assertEquals(PlayProgress("turn-0001", 0, 0, 1), PlayProgress.decode(PlayProgress("turn-0001", 0, 0, 1).encode()))
        assertEquals(PlayProgress("turn-0001", 0, PlayProgress.MAX_DURATION_MS, PlayProgress.MAX_DURATION_MS),
            PlayProgress.decode(PlayProgress("turn-0001", 0, PlayProgress.MAX_DURATION_MS, PlayProgress.MAX_DURATION_MS).encode()))
        fun body(turn: Any = "turn-0001", seq: Any = 1, pos: Any = 10L, dur: Any = 100L) =
            JSONObject().put("turn_id", turn).put("seq", seq).put("pos", pos).put("dur", dur).toString().toByteArray()
        assertNull("beyond the duration", PlayProgress.decode(body(pos = 101L)))
        assertNull("negative position", PlayProgress.decode(body(pos = -1L)))
        assertNull("no duration", PlayProgress.decode(body(dur = 0L)))
        assertNull("not a real clip length", PlayProgress.decode(body(pos = 1L, dur = PlayProgress.MAX_DURATION_MS + 1)))
        assertNull("negative sequence", PlayProgress.decode(body(seq = -1)))
        assertNull("bad turn id", PlayProgress.decode(body(turn = "../etc")))
        assertNull("empty", PlayProgress.decode(ByteArray(0)))
        assertNull("oversized", PlayProgress.decode(ByteArray(513) { ' '.code.toByte() }))
        assertNull("text", PlayProgress.decode("hello".toByteArray()))
        assertTrue(WatchLinkPaths.PLAY_PROGRESS.startsWith("/hv/v1/"))
    }
}
