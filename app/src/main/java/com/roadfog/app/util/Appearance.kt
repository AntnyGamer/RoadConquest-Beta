package com.roadfog.app.util

import android.content.Context
import android.content.res.Configuration

enum class UiTheme(val label: String) { SYSTEM("Use phone setting"), LIGHT("Light"), DARK("Dark") }

object Appearance {
    fun wrap(context: Context): Context {
        if (Prefs.uiTheme(context) == UiTheme.SYSTEM) return context
        val config = Configuration(context.resources.configuration)
        val night = when (Prefs.uiTheme(context)) {
            UiTheme.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            UiTheme.DARK -> Configuration.UI_MODE_NIGHT_YES
            UiTheme.SYSTEM -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(config)
    }

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
