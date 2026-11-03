package br.com.primereef.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import br.com.primereef.app.ui.AddDeviceScreen
import br.com.primereef.app.ui.DeviceScreen
import br.com.primereef.app.ui.HomeScreen
import br.com.primereef.app.ui.PrimeReefTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrimeReefTheme {
                // Navegação simples: "home", "add" ou "device:<id>".
                var route by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = route != "home") { route = "home" }
                when {
                    route == "add" -> AddDeviceScreen(vm, onBack = { route = "home" })
                    route.startsWith("device:") -> DeviceScreen(
                        vm = vm,
                        deviceId = route.removePrefix("device:"),
                        onBack = { route = "home" },
                    )
                    else -> HomeScreen(
                        vm = vm,
                        onOpen = { route = "device:${it.id}" },
                        onAdd = { route = "add" },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startPolling()
    }

    override fun onPause() {
        super.onPause()
        vm.stopPolling()
    }
}
