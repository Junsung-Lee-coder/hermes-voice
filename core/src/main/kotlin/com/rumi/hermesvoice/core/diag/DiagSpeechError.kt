package com.rumi.hermesvoice.core.diag

/**
 * The platform speech recognizer's error numbers (android.speech.SpeechRecognizer.ERROR_*), mapped to a failure category by
 * number alone. "No speech" and "no match" are normal outcomes of a listening window and are not failures; the recognizer being
 * busy or misused by the app is not a network or service condition either, so only these carry a category.
 */
object DiagSpeechError {
    private const val NETWORK_TIMEOUT = 1
    private const val NETWORK = 2
    private const val SERVER = 4
    private const val INSUFFICIENT_PERMISSIONS = 9
    private const val TOO_MANY_REQUESTS = 10
    private const val SERVER_DISCONNECTED = 11

    /** The category of a recognizer error that points at the network, the recognition service or a permission; null for the rest. */
    fun causeOf(code: Int): DiagCause? = when (code) {
        NETWORK_TIMEOUT -> DiagCause.TIMEOUT
        NETWORK, SERVER_DISCONNECTED -> DiagCause.DISCONNECT
        SERVER, TOO_MANY_REQUESTS -> DiagCause.REJECTED
        INSUFFICIENT_PERMISSIONS -> DiagCause.AUTH
        else -> null
    }

    /** Records [code] as a transcription failure of [origin]'s device when it is one; returns whether it was recorded. */
    fun record(log: DiagLog?, origin: DiagOrigin, code: Int): Boolean {
        val cause = causeOf(code) ?: return false
        log?.record(DiagCode.STT_RECOGNIZER_FAILED, origin, fail = DiagClassify.failOf(cause), stage = DiagStage.STT, cause = cause,
            modality = DiagModality.VOICE, status = code)
        return true
    }
}
