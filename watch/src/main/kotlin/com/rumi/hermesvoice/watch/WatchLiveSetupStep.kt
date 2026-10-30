package com.rumi.hermesvoice.watch

import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagStage

/**
 * R32: the fixed step at which the Watch refused its own Live call before the call started: the [n] of the Watch's one
 * `live_setup_failed` (origin watch) observation, with [stage] as its setup word. [fail] is the type of a refusal the app made
 * itself; when an exception ended the step, its type (never its text) decides instead, and a setup bound that elapsed or a
 * cancellation that was no Stop is observed at its step as timeout/cancelled without being a refusal. A step names where the
 * Watch stopped, never why the device did. R34: a refusal after the Phone relayed the call's session.started is recorded instead as
 * where the Watch stopped the started call: the same step and type in one `live_ended` (origin watch) with stage live_start.
 */
enum class WatchLiveSetupStep(val n: Int, val stage: DiagStage, val fail: DiagFail = DiagFail.REFUSED) {
    /** Starting the Watch Live foreground service threw. */
    SERVICE_START(1, DiagStage.LIVE_INPUT),
    /** The service's foreground start threw: its notification channel or notification, or startForeground with the microphone and media playback types. */
    SERVICE_FOREGROUND(2, DiagStage.LIVE_INPUT),
    /** Admission work right after startForeground threw. */
    ADMISSION_SETUP(3, DiagStage.LIVE_INPUT),
    /** At admission the microphone permission was missing (auth) or Watch Live was off (cancelled). */
    NOT_ELIGIBLE(4, DiagStage.LIVE_INPUT, DiagFail.AUTH),
    /** At admission a Watch recording was active. */
    BUSY(5, DiagStage.LIVE_INPUT, DiagFail.BUSY),
    /** Android did not admit the Watch microphone within the 60 s admission bound. */
    NOT_ADMITTED(6, DiagStage.LIVE_INPUT, DiagFail.TIMEOUT),
    /** The microphone permission (auth) or Watch Live (cancelled) changed while the call was being admitted. */
    ELIGIBILITY_CHANGED(7, DiagStage.LIVE_INPUT, DiagFail.AUTH),
    /** Opening the Watch communication audio route threw. */
    ROUTE_THREW(10, DiagStage.LIVE_ROUTE),
    /** The route refused: a private (Phone) audio output is selected. */
    ROUTE_PRIVATE_SELECTED(11, DiagStage.LIVE_ROUTE),
    /** The route refused: a personal output is connected on an Android version without communication devices. */
    ROUTE_PERSONAL_OUTPUT(12, DiagStage.LIVE_ROUTE),
    /** The route refused: audio focus was not granted. */
    ROUTE_FOCUS_DENIED(13, DiagStage.LIVE_ROUTE),
    /** The route refused: no matching communication device was available. */
    ROUTE_NO_DEVICE(14, DiagStage.LIVE_ROUTE),
    /** The route refused: Android did not set the communication device. */
    ROUTE_DEVICE_REFUSED(15, DiagStage.LIVE_ROUTE),
    /** The route refused: it was not valid right after opening. */
    ROUTE_NOT_VALID(16, DiagStage.LIVE_ROUTE),
    /** The route or its focus was lost. */
    ROUTE_LOST(17, DiagStage.LIVE_ROUTE),
    /** Creating the paired relay media threw. */
    MEDIA_FACTORY(20, DiagStage.LIVE_MEDIA),
    /** Opening the Wear Channel to the Phone failed, timed out or was cancelled (status: the Task's Play services status), including its
     *  precondition that the call is still current and eligible; also any failure before the offer of a media adapter that names no step. */
    CHANNEL_OPEN(21, DiagStage.LIVE_RELAY),
    /** The opened Channel was not this call's. */
    CHANNEL_IDENTITY(22, DiagStage.LIVE_RELAY),
    /** Taking the Channel's input or output stream failed, timed out or was cancelled. */
    CHANNEL_STREAMS(23, DiagStage.LIVE_RELAY),
    /** The offer could not be handed to the Phone. */
    OFFER_NOT_SENT(24, DiagStage.LIVE_MEDIA),
    /** The Phone's answer did not arrive within its bound, was not ready, or the call stopped being current while waiting. */
    ANSWER(25, DiagStage.LIVE_ANSWER),
    /** "connected" could not be handed to the Phone. */
    CONNECTED_NOT_SENT(26, DiagStage.LIVE_RELAY),
    /** Another media setup step (callbacks, mute, input hold, private selection) threw. */
    MEDIA_SETUP(27, DiagStage.LIVE_MEDIA),
    /** Sending a Watch control packet to the Phone failed. */
    SIGNALING(30, DiagStage.LIVE_RELAY),
    /** A Phone control packet was missing from the sequence. */
    CONTROL_SEQUENCE(31, DiagStage.LIVE_RELAY, DiagFail.PROTOCOL),
    /** No Phone control packet arrived for 5 s. */
    CONTROL_LEASE(32, DiagStage.LIVE_RELAY, DiagFail.TIMEOUT),
    /** Reading Phone audio from the Channel failed. */
    RELAY_DOWNLINK(33, DiagStage.LIVE_RELAY),
    /** Writing Watch audio to the Channel failed. */
    RELAY_UPLINK(34, DiagStage.LIVE_RELAY),
    /** The relay media reported a loss without its own step. */
    RELAY_LOST(35, DiagStage.LIVE_RELAY),
    /** At capture the microphone permission was missing. */
    CAPTURE_PERMISSION(40, DiagStage.LIVE_INPUT, DiagFail.AUTH),
    /** The Watch recorder could not be created, given its preferred input device, or started. */
    CAPTURE_RECORDER(41, DiagStage.LIVE_INPUT),
    /** The capture input route was not confirmed or changed. */
    CAPTURE_ROUTE(42, DiagStage.LIVE_INPUT),
    /** Reading the Watch recorder failed. */
    CAPTURE_READ(43, DiagStage.LIVE_INPUT),
    /** Captured audio could not be queued. */
    CAPTURE_OVERFLOW(44, DiagStage.LIVE_INPUT, DiagFail.BUSY),
    /** A provider command could not be delivered to Watch media. */
    DATA_CHANNEL(50, DiagStage.LIVE_RELAY),
    /** Output was enabled without a confirmed Watch output route. */
    OUTPUT_ROUTE(51, DiagStage.LIVE_ROUTE),
    /** A held Watch input could not be released. */
    INPUT_RELEASE(52, DiagStage.LIVE_INPUT),
    /** The Watch speaker did not confirm its stop for a private output. */
    PRIVATE_STOP(53, DiagStage.LIVE_ROUTE),
    /** The private-output confirmation could not be handed to the Phone. */
    PRIVATE_CONFIRMATION(54, DiagStage.LIVE_ROUTE),
    /** The call's absolute cost bound was reached. */
    COST_LIMIT(55, DiagStage.LIVE_START),
    /** R34: the Watch speaker track for Phone audio could not be created or started, or did not take a whole downlink frame (a read,
     *  sequence or format failure of the downlink itself stays [RELAY_DOWNLINK]). */
    OUTPUT_PLAYBACK(56, DiagStage.LIVE_ROUTE)
}
