package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.phone.live.PhoneLiveAudioGate
import org.junit.Test
import org.junit.Assert.*

class LiveAudioGateTest {
    @Test fun liveAndLegacyNativePlayersCannotOverlapAndReleaseIsIdempotent() {
        val gate=PhoneLiveAudioGate()
        val call=gate.live()!!
        assertNull(gate.playback()); assertNull(gate.live())
        call.close(); call.close()
        val first=gate.playback()!!; val second=gate.playback()!!
        assertNull(gate.live()); first.close(); assertNull(gate.live())
        second.close(); assertNotNull(gate.live())
    }
    @Test fun anAlreadyAdmittedPlayerPreventsLiveUntilNativeTeardownReturnsItsLease() {
        val gate=PhoneLiveAudioGate(); val player=gate.playback()!!
        assertNull(gate.live()); player.close()
        val call=gate.live()!!; assertNull(gate.playback()); call.close(); assertNotNull(gate.playback())
    }
}
