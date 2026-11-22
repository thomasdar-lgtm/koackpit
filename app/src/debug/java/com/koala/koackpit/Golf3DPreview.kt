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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mask.intValue = intent.getIntExtra("mask", 0)
        setContent { Golf3D(mask.intValue, Modifier.fillMaxSize()) }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        mask.intValue = intent.getIntExtra("mask", 0)
    }
}
