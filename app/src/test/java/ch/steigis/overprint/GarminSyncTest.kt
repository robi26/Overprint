package ch.steigis.overprint

import ch.steigis.overprint.data.remote.garmin.garminIdsToDownload
import ch.steigis.overprint.data.remote.garmin.garminReachedKnownHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminSyncTest {
    @Test
    fun downloadsOnlyUnknownIds() {
        val page = listOf("garmin-3", "garmin-2", "garmin-1")
        val skip = setOf("garmin-1")
        assertEquals(listOf("garmin-3", "garmin-2"), garminIdsToDownload(page, skip))
    }

    @Test
    fun stopsPagingWhenTheWholePageIsAlreadyStored() {
        val page = listOf("garmin-2", "garmin-1")
        assertTrue(garminReachedKnownHistory(page, setOf("garmin-1", "garmin-2", "garmin-0")))
        assertFalse(garminReachedKnownHistory(page, setOf("garmin-1")))
        assertFalse(garminReachedKnownHistory(emptyList(), setOf("garmin-1")))
    }

    @Test
    fun retriesActivitiesThatHaveNoTrackYet() {
        val page = listOf("garmin-new", "garmin-incomplete")
        val skip = setOf("garmin-complete")
        assertEquals(page, garminIdsToDownload(page, skip))
        assertFalse(garminReachedKnownHistory(page, skip))
    }
}
