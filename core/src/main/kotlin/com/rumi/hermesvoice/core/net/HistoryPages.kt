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

    /** As above, and a stale copy the page positively identifies (a row it hides, or a stable uid now at another row id) goes too. */
    fun refreshLatest(current: List<HistoryMessage>, page: HistoryPage): List<HistoryMessage> {
        val kept = withoutStale(current, page.suppressed, page.messages.map { SuppressedRow(it.rowId, it.messageUid) }, { it.rowId }, { it.messageUid })
        return if (page.messages.isEmpty()) kept else refreshLatest(kept, page.messages)
    }

    /**
     * Drops from [current] only what the page names: a [hidden] row by row id (unless both uids are known and differ) or by uid, and a
     * [shown] uid that now sits at another row id. Nothing else is touched, whatever its words.
     */
    fun <T> withoutStale(current: List<T>, hidden: List<SuppressedRow>, shown: List<SuppressedRow>, rowId: (T) -> Long, uid: (T) -> String): List<T> {
        if (hidden.isEmpty() && shown.none { it.uid.isNotEmpty() }) return current
        val hiddenById = hidden.groupBy { it.rowId }
        val hiddenUids = hidden.mapNotNullTo(HashSet()) { it.uid.takeIf(String::isNotEmpty) }
        val shownUids = shown.filter { it.uid.isNotEmpty() }.groupBy { it.uid }
        return current.filterNot { row ->
            val id = rowId(row)
            val u = uid(row)
            hiddenById[id]?.any { it.uid.isEmpty() || u.isEmpty() || it.uid == u } == true ||
                (u.isNotEmpty() && (u in hiddenUids || shownUids[u]?.any { it.rowId != id } == true))
        }
    }
}
