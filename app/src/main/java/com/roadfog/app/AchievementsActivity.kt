package com.roadfog.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadfog.app.achievements.AchievementProgress
import com.roadfog.app.achievements.Achievements
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.util.Appearance
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class AchievementsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    private companion object { const val PROGRESS_MAX = 1000 }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Appearance.isDark(this@AchievementsActivity)
            isAppearanceLightNavigationBars = !Appearance.isDark(this@AchievementsActivity)
        }
        setContentView(R.layout.activity_achievements)
        val root = findViewById<android.view.View>(R.id.achievementsRoot)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val text = findViewById<TextView>(R.id.achievementsText)
        val list = findViewById<LinearLayout>(R.id.achievementsList)
        executor.execute {
            val result = runCatching { Achievements.progress(this, TrackingRepository(this).getSummary()) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { achievements ->
                        text.visibility = android.view.View.GONE
                        list.removeAllViews()
                        achievements.forEach { addAchievement(list, it) }
                    },
                    onFailure = {
                        list.removeAllViews()
                        text.visibility = android.view.View.VISIBLE
                        text.text = "Achievements are temporarily unavailable."
                    }
                )
            }
        }
    }

    private fun addAchievement(container: LinearLayout, item: AchievementProgress) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_background)
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        card.addView(TextView(this).apply {
            text = (if (item.unlocked) "UNLOCKED  " else "LOCKED  ") + item.title
            setTextColor(getColor(R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = item.description
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        })
        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progress = ((item.progress / item.goal).coerceIn(0.0, 1.0) * PROGRESS_MAX).roundToInt()
            isIndeterminate = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        card.addView(TextView(this).apply {
            text = progressText(item)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })
        container.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
    }

    private fun progressText(item: AchievementProgress): String =
        if (item.unit == "roads") {
            String.format(
                Locale.getDefault(), "%,.0f / %,.0f roads",
                item.progress.coerceAtMost(item.goal), item.goal
            )
        } else {
            String.format(
                Locale.getDefault(), "%,.1f / %,.0f mi",
                item.progress.coerceAtMost(item.goal), item.goal
            )
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
