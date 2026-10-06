package com.roadconquest.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MapLayoutTest {
    private class CountingTextView(context: Context) : TextView(context) {
        var layoutRequests = 0
        override fun requestLayout() { layoutRequests++; super.requestLayout() }
    }

    @Test fun identicalInsetsAndRepeatedLayoutsDoNotRescheduleTheStatsCard() {
        // Test the real safe-area listener without loading MapLibre's desktop-incompatible GPU view.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_RoadConquest)
        val root = FrameLayout(activity).apply { id = R.id.root }
        val stats = CountingTextView(activity).apply { text = "0.0 mi traveled\n0 roads unlocked"; maxWidth = 210 }
        val settings = ImageButton(activity)
        val center = ImageButton(activity)
        val overlay = ImageButton(activity)
        val points = CountingTextView(activity)
        val panel = View(activity)
        fun params(top: Int, gravity: Int) = FrameLayout.LayoutParams(52, 52, gravity).apply {
            topMargin = top; marginStart = 16; marginEnd = 16; bottomMargin = 16
        }
        root.addView(stats, params(16, Gravity.TOP or Gravity.START))
        root.addView(settings, params(16, Gravity.TOP or Gravity.END))
        root.addView(center, params(80, Gravity.TOP or Gravity.END))
        root.addView(overlay, params(144, Gravity.TOP or Gravity.END))
        root.addView(points, params(88, Gravity.TOP or Gravity.START))
        root.addView(panel, params(16, Gravity.BOTTOM))
        activity.setContentView(root)
        MainActivity::class.java.getDeclaredMethod(
            "applySafeAreaInsets",
            ImageButton::class.java,
            ImageButton::class.java,
            ImageButton::class.java,
            View::class.java,
            View::class.java,
            View::class.java
        ).apply { isAccessible = true }
            .invoke(activity, settings, center, overlay, panel, stats, points)
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(7, 30, 11, 18)).build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1280)
        }
        val requests = stats.layoutRequests
        repeat(20) {
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1280)
        }
        assertEquals("Unchanged values must not create a layout feedback loop", requests, stats.layoutRequests)
        assertEquals(46, (settings.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(110, (center.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(27, (center.layoutParams as FrameLayout.LayoutParams).marginEnd)
        assertEquals(174, (overlay.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(27, (overlay.layoutParams as FrameLayout.LayoutParams).marginEnd)
        assertEquals(118, (points.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(23, (points.layoutParams as FrameLayout.LayoutParams).marginStart)
        assertEquals(34, (panel.layoutParams as FrameLayout.LayoutParams).bottomMargin)
    }
}
