package com.civisrom.tvtimefixer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = detectDeviceMode(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StartScreen(mode)
                }
            }
        }
    }
}

@Composable
private fun StartScreen(mode: DeviceMode) {
    Column(
        // Отступ под overscan телевизоров: у части моделей края экрана обрезаны
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = when (mode) {
                DeviceMode.TELEVISION -> stringResource(R.string.mode_television)
                DeviceMode.HANDHELD -> stringResource(R.string.mode_handheld)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.milestone_one_notice),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
