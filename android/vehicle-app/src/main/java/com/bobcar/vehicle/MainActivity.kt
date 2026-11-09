package com.bobcar.vehicle

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.bobcar.vehicle.gateway.CarPropertyGateway
import com.bobcar.vehicle.gateway.NaviGateway
import com.bobcar.vehicle.gateway.VehicleGateway

class MainActivity : ComponentActivity() {

    // Temporary owner until the collection service exists; the gateways move there later.
    private lateinit var vehicleGateway: VehicleGateway
    private lateinit var carPropertyGateway: CarPropertyGateway
    private lateinit var naviGateway: NaviGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created (version ${BuildConfig.VERSION_NAME})")

        vehicleGateway = VehicleGateway(this).also {
            if (it.initialize()) it.readDistanceDrivenOnce { }
        }
        carPropertyGateway = CarPropertyGateway(this).also {
            if (it.connect()) it.readGearOnce()
        }
        naviGateway = NaviGateway(this).also { it.initialize() }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen()
            }
        }
    }

    override fun onDestroy() {
        naviGateway.release()
        carPropertyGateway.release()
        vehicleGateway.release()
        Log.i(TAG, "MainActivity destroyed")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BOBCar"
    }
}

@Composable
fun HomeScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        HomeScreen()
    }
}
