package ke.geoai.agroweather.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Material window-width classes, read from the live configuration so rotation, split-screen and foldables all work. */
enum class Width { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWidth(): Width {
    val dp = LocalConfiguration.current.screenWidthDp
    return when {
        dp < 600 -> Width.COMPACT
        dp < 840 -> Width.MEDIUM
        else -> Width.EXPANDED
    }
}
