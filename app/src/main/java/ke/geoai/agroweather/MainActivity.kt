package ke.geoai.agroweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ke.geoai.agroweather.ui.AgroApp
import ke.geoai.agroweather.ui.theme.AgroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgroTheme { AgroApp() } }
    }
}
