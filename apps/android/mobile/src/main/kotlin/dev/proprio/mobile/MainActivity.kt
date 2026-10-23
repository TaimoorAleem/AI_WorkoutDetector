package dev.proprio.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.proprio.mobile.ui.AppRoot
import dev.proprio.mobile.ui.ProprioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = container
        setContent {
            ProprioTheme {
                AppRoot(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.coordinator.refreshConnection()
    }
}
