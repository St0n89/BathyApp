package io.github.st0n89.bathyapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BathyAppTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val vm: AppViewModel = viewModel()
    val state by vm.state.collectAsState()

    // Android back: Profile -> Map -> Home, only Home exits.
    // The last enabled BackHandler wins, so register the more specific one first.
    BackHandler(enabled = state.screen == AppScreen.Profile) { vm.goToMap() }
    BackHandler(enabled = state.screen == AppScreen.Map) { vm.goHome() }

    when (state.screen) {
        AppScreen.Home -> HomeScreen(vm, state)
        AppScreen.Map -> MapScreen(vm, state)
        AppScreen.Profile -> ProfileScreen(vm, state)
    }
}
