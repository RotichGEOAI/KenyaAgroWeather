package ke.geoai.agroweather.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ke.geoai.agroweather.domain.Light

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B34),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0B8),
    onPrimaryContainer = Color(0xFF002106),
    secondary = Color(0xFF4F6354),
    tertiary = Color(0xFF39656B),
    background = Color(0xFFF7FBF3),
    surface = Color(0xFFF7FBF3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD49E),
    onPrimary = Color(0xFF00390E),
    primaryContainer = Color(0xFF145221),
    onPrimaryContainer = Color(0xFFB7F0B8),
    secondary = Color(0xFFB5CCB9),
    tertiary = Color(0xFFA1CED5),
    background = Color(0xFF101410),
    surface = Color(0xFF101410),
)

@Composable
fun AgroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Traffic-light colours, tuned per theme. Always shown together with a text badge. */
@Composable
fun signalColor(light: Light): Color {
    val dark = isSystemInDarkTheme()
    return when (light) {
        Light.GREEN -> if (dark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
        Light.AMBER -> if (dark) Color(0xFFFFCA28) else Color(0xFFF9A825)
        Light.RED -> if (dark) Color(0xFFEF5350) else Color(0xFFC62828)
    }
}

@Composable
fun onSignal(light: Light): Color =
    if (isSystemInDarkTheme() || light == Light.AMBER) Color.Black else Color.White
