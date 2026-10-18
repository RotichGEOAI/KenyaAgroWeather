package ke.geoai.agroweather.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ke.geoai.agroweather.ui.screens.EnsoScreen
import ke.geoai.agroweather.ui.screens.FarmsScreen
import ke.geoai.agroweather.ui.screens.ForecastScreen
import ke.geoai.agroweather.ui.screens.RadarScreen
import ke.geoai.agroweather.ui.screens.TodayScreen

enum class Tab(val label: String, val emoji: String) {
    TODAY("Today", "🌾"),
    FORECAST("Forecast", "📅"),
    RADAR("Radar", "🛰️"),
    ENSO("El Niño", "🌊"),
    FARMS("Farms", "📍"),
}

@Composable
fun AgroApp(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.emoji, fontSize = 22.sp) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.TODAY -> TodayScreen(ui, vm, m, onGo = { tab = it })
            Tab.FORECAST -> ForecastScreen(ui, m, onGo = { tab = it }, onLoadModels = { vm.loadModels(it) })
            Tab.RADAR -> RadarScreen(ui, vm, m)
            Tab.ENSO -> EnsoScreen(ui, vm, m)
            Tab.FARMS -> FarmsScreen(ui, vm, m)
        }
    }
}
