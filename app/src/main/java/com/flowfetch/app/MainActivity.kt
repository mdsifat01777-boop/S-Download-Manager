package com.flowfetch.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfetch.app.ui.screen.FlowFetchScreen
import com.flowfetch.app.ui.screen.FlowFetchViewModel
import com.flowfetch.app.ui.theme.FlowFetchTheme

class MainActivity : ComponentActivity() {

    private val viewModel: FlowFetchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle shared URL from other apps (YouTube, TikTok, Twitter, Instagram, Facebook)
        handleIncomingIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            FlowFetchTheme(darkTheme = uiState.isDarkTheme) {
                FlowFetchScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.onUrlChanged(sharedText)
                viewModel.analyzeUrl(sharedText)
            }
        }
    }
}
