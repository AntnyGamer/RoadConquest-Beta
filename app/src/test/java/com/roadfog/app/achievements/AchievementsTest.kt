package com.roadfog.app.achievements

import com.roadfog.app.data.DataSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class AchievementsTest {
    @Test fun milestonesReflectLocalRoadAndMileageProgress() {
        val summary = DataSummary(
            trackPointCount = 0,
            roadSegmentCount = 0,
            firstTrackAt = null,
            lastTrackAt = null,
            distanceMeters = 100.0 * 1609.344,
            roadsUnlockedCount = 100
        )
        val progress = Achievements.progress(summary)
        assertEquals(
            listOf("Newbie Explorer", "Casual Explorer", "Road Conquerer", "Beginner Driver", "Average Driver", "Expert Driver"),
            progress.map { it.title }
        )
        assertTrue(progress.first { it.id == "roads_10" }.unlocked)
        assertTrue(progress.first { it.id == "roads_100" }.unlocked)
        assertFalse(progress.first { it.id == "roads_1000" }.unlocked)
        assertTrue(progress.first { it.id == "miles_100" }.unlocked)
        assertFalse(progress.first { it.id == "miles_1000" }.unlocked)
    }

    @Test fun correctedRoadGroupingCannotRelockAnEarnedMilestone() {
        val context = RuntimeEnvironment.getApplication()
        Achievements.reset(context)
        val reached = DataSummary(0, 0, null, null, 0.0, 10)
        assertTrue(Achievements.progress(context, reached).first { it.id == "roads_10" }.unlocked)

        val corrected = reached.copy(roadsUnlockedCount = 9)
        val progress = Achievements.progress(context, corrected)
        assertTrue(progress.first { it.id == "roads_10" }.unlocked)
        assertEquals(10.0, progress.first { it.id == "roads_10" }.progress, 0.0)

        Achievements.reset(context)
        assertFalse(Achievements.progress(context, corrected).first { it.id == "roads_10" }.unlocked)
    }

    @Test fun existingProgressIsBaselinedAndFutureMilestonesAnnounceOnce() {
        val context = RuntimeEnvironment.getApplication()
        Achievements.reset(context)
        val initial = DataSummary(0, 0, null, null, 9.0 * 1609.344, 9)
        assertTrue(Achievements.newlyUnlocked(context, initial).isEmpty())

        val crossed = DataSummary(0, 0, null, null, 10.0 * 1609.344, 10)
        assertEquals(setOf("roads_10", "miles_10"), Achievements.newlyUnlocked(context, crossed).map { it.id }.toSet())
        assertTrue(Achievements.newlyUnlocked(context, crossed).isEmpty())
    }
}
