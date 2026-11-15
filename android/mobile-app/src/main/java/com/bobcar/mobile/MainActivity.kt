package com.bobcar.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bobcar.mobile.ui.ConsentScreen
import com.bobcar.mobile.ui.MType
import com.bobcar.mobile.ui.MobileTheme
import com.bobcar.mobile.ui.PickScreen
import com.bobcar.mobile.ui.StartScreen
import com.bobcar.mobile.ui.screenBackground

class MainActivity : ComponentActivity() {

    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark screens: light status and navigation bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MobileTheme {
                val state by model.state.collectAsState()
                App(state, model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

@Composable
private fun App(state: UiState, model: AppModel) {
    when (state.stage) {
        Stage.LOADING, Stage.ERROR -> StartScreen(state.problem.takeIf { state.stage == Stage.ERROR }, model::start)
        Stage.PICK -> PickScreen(state.drivers, state.busy, state.problem, model::pick)
        Stage.CONSENT -> ConsentScreen(state.me!!, state.busy, state.problem, model::agree, model::choose)
        Stage.MAIN -> Box(Modifier.fillMaxSize().screenBackground(), contentAlignment = Alignment.Center) {
            Text("${state.me?.name} 님 · 운행 ${state.trips?.size ?: 0}건", style = MType.Body)
        }
    }
}
