/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.dhruvgandhi.swatchsync.presentation
// MainActivity.kt
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {

    private lateinit var heartRateManager: HeartRateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        heartRateManager = HeartRateManager(this)

        // Start Bluetooth service
        val serviceIntent = Intent(this, HeartRateBluetoothService::class.java)
        startService(serviceIntent)

        setContent {
            androidx.wear.compose.material.MaterialTheme {
                HeartRateScreen(heartRateManager)
            }
        }
    }
}

@Composable
fun HeartRateScreen(heartRateManager: HeartRateManager) {
    heartRateManager.startMonitoring()
    val heartRate by heartRateManager.heartRate.collectAsState()
    val isMonitoring by heartRateManager.isMonitoring.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Heart Rate",
            style = MaterialTheme.typography.body1
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (heartRate > 0) "${heartRate.toInt()} BPM" else "--",
            style = MaterialTheme.typography.body1
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isMonitoring) "Broadcasting..." else "Stopped",
            style = MaterialTheme.typography.body2
        )
    }
}
