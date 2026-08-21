package ch.steigis.overprint.data.remote.garmin

/**
 * Garmin's activity search is newest-first. Download a FIT only when we do not already
 * have that activity (or we have it without a track). Stop paging once a whole page is
 * already complete, so later history is not fetched again.
 */
internal fun garminIdsToDownload(pageIds: List<String>, skipFetch: Set<String>): List<String> =
    pageIds.filter { it !in skipFetch }

internal fun garminReachedKnownHistory(pageIds: List<String>, skipFetch: Set<String>): Boolean =
    pageIds.isNotEmpty() && pageIds.all { it in skipFetch }
