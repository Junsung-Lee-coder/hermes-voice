package com.rumi.hermesvoice.core.net

/** How a re-read of a conversation's newest page combines with what is already shown. */
object HistoryPages {
    /**
     * The newest page wins for every row it contains (a reply may have grown); rows older than it
     * that were already loaded ("Load older") stay, so a refresh never pulls the reader's place
     * out from under them. Row ids grow with time, as the older-page loader already assumes.
     */
    fun refreshLatest(current: List<HistoryMessage>, latestPage: List<HistoryMessage>): List<HistoryMessage> {
        val oldestNew = latestPage.minOfOrNull { it.rowId } ?: return current
        val pageIds = latestPage.mapTo(HashSet()) { it.rowId }
        return current.filter { it.rowId < oldestNew && it.rowId !in pageIds } + latestPage
    }
}
