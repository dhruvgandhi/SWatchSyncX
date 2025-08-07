/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.dhruvgandhi.swatchsync.presentation
// MainActivity.kt
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.material.darkColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private lateinit var heartRateManager: HeartRateManager
    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        TODO("VERSION.SDK_INT < S")
    }



    //Theme code
    private val DarkColors = darkColors(
        background = Color.Black,
        surface = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
        primary = Color.White,  // Adjust as needed
        onPrimary = Color.Black
    )

    @Composable
    fun MyDarkTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colors = DarkColors,
            content = content
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
        heartRateManager = HeartRateManager(this)

        // Start Bluetooth service
        val serviceIntent = Intent(this, HeartRateBluetoothService::class.java)
        startService(serviceIntent)

        setContent {
            MyDarkTheme {
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
    val heartRateState by HeartRateInfo.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)
        ){
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center

        ) {
            Text(
                text = "❤️",
                style = MaterialTheme.typography.title1
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Heart Rate",
                style = MaterialTheme.typography.body1
            )
            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = if (heartRateState.heartRate > 0) "${heartRateState.heartRate.toInt()} BPM" else "--",
                style = MaterialTheme.typography.body2

            )


            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Last Updated",
                style = MaterialTheme.typography.body1
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = if (heartRateState.lastUpdated == LocalDateTime.MIN) "--" else
                    "${heartRateState.lastUpdated.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))}",
                style = MaterialTheme.typography.body2
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isMonitoring) "Broadcasting..." else "Stopped",
                style = MaterialTheme.typography.caption3
            )
        }
    }

}
