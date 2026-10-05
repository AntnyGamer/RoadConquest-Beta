package com.roadfog.app

import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.roadfog.app.data.AppDatabase
import com.roadfog.app.achievements.Achievements
import com.roadfog.app.data.DataSummary
import com.roadfog.app.data.TrackingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AchievementsActivityTest {
    @Before fun resetHistory() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TrackingRepository(RuntimeEnvironment.getApplication()).clearHistory()
    }

    @Test fun rendersAllAchievementProgressBarsWithOriginalMilestonesFirst() {
        val controller = Robolectric.buildActivity(AchievementsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val list = activity.findViewById<LinearLayout>(R.id.achievementsList)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            do {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (list.childCount == Achievements.progress(DataSummary(0, 0, null, null)).size) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)

            val expectedCount = Achievements.progress(DataSummary(0, 0, null, null)).size
            assertEquals(expectedCount, list.childCount)
            val expected = listOf(
                "Newbie Explorer", "Casual Explorer", "Road Conquerer",
                "Beginner Driver", "Average Driver", "Expert Driver"
            )
            val titles = ArrayList<String>(expected.size)
            var progressBars = 0
            for (index in 0 until list.childCount) {
                val card = list.getChildAt(index) as ViewGroup
                val title = (card.getChildAt(0) as TextView).text.toString()
                expected.firstOrNull { title.endsWith(it) }?.let(titles::add)
                for (child in 0 until card.childCount) {
                    if (card.getChildAt(child) is ProgressBar) {
                        progressBars++
                        val bar = card.getChildAt(child) as ProgressBar
                        assertTrue(bar.progress in 0..bar.max)
                    }
                }
            }
            assertEquals(expected, titles)
            assertEquals(expectedCount, progressBars)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
