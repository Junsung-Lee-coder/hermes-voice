package com.rumi.hermesvoice.core

/** U+1F399 and one space: what every Phone or Watch voice request's final recipient prompt starts with. */
internal const val VOICE_MARK = "🎙 "

/** The retired v19 scaffold, kept only as a literal so tests can prove it is neither sent nor stripped. */
internal const val OLD_VOICE_HINT = "음성으로 보낸 요청입니다. 자세한 설명을 요청하지 않았다면 핵심 결론부터 짧고 자연스럽게 답하고, " +
    "필요한 조치와 중요한 주의사항만 덧붙여 주세요. 자세히 설명해 달라는 요청에는 충분히 설명해 주세요. " +
    "요청한 언어로 답해 주세요."

/** The words of a recipient prompt with the voice marker taken off, for inherited assertions on what the user said. */
internal fun voiceWords(raw: String): String = raw.removePrefix(VOICE_MARK)
