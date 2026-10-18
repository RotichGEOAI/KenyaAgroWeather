package ke.geoai.agroweather.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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

    val width = rememberWidth()
    val wide = width != Width.COMPACT

    @Composable
    fun Content(padding: PaddingValues) {
        // Content never stretches edge to edge on tablets: it is centred with a readable maximum width.
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val m = Modifier.widthIn(max = if (width == Width.EXPANDED) 960.dp else 720.dp).fillMaxSize()
            when (tab) {
                Tab.TODAY -> TodayScreen(ui, vm, m, onGo = { tab = it })
                Tab.FORECAST -> ForecastScreen(ui, m, onGo = { tab = it }, onLoadModels = { vm.loadModels(it) }, onPirateKey = { vm.setPirateKey(it) }, onTahmo = { k, sec -> vm.setTahmo(k, sec) })
                Tab.RADAR -> RadarScreen(ui, vm, m)
                Tab.ENSO -> EnsoScreen(ui, vm, m)
                Tab.FARMS -> FarmsScreen(ui, vm, m)
            }
        }
    }

    if (wide) {
        // tablets, foldables, phones in landscape: navigation rail on the side
        Scaffold { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    Spacer(Modifier.weight(1f))
                    Tab.values().forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Text(t.emoji, fontSize = 22.sp) },
                            label = { Text(t.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { Content(PaddingValues(0.dp)) }
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Text(t.emoji, fontSize = 22.sp) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding -> Content(padding) }
    }
}
