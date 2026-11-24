package com.koala.koackpit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier

/** Test sur émulateur (workflow emulator-3d.yml) : la Golf 3D seule ; extra « mask » = ouvrants ouverts. */
class Golf3DPreview : ComponentActivity() {
    private val mask = mutableIntStateOf(0)
    private val zoom = androidx.compose.runtime.mutableStateOf(Triple(1f, 0f, 0f))   // extras « zoom », « offx », « offy »
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mask.intValue = intent.getIntExtra("mask", 0)
        if (intent.getBooleanExtra("jour", false)) KTheme.day.value = true
        zoom.value = Triple(intent.getFloatExtra("zoom", 1f), intent.getFloatExtra("offx", 0f), intent.getFloatExtra("offy", 0f))
        setContent {
            val (z, x, y) = zoom.value
            androidx.compose.runtime.key(zoom.value) { Golf3D(mask.intValue, Modifier.fillMaxSize(), z, androidx.compose.ui.geometry.Offset(x, y)) }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        mask.intValue = intent.getIntExtra("mask", 0)
        zoom.value = Triple(intent.getFloatExtra("zoom", 1f), intent.getFloatExtra("offx", 0f), intent.getFloatExtra("offy", 0f))
    }
}
