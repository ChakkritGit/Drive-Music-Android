package com.drivemusic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drivemusic.shared.analysis.MixPoints
import com.drivemusic.shared.playback.Shuffle
import com.drivemusic.shared.recommendation.Features
import com.drivemusic.shared.recommendation.RecommendationModel

/**
 * Placeholder screen. It exists to prove the wiring end to end — that `:androidApp` really does
 * compile against `:shared`, and that the shared logic runs on device — not to be the UI. The
 * real Compose port replaces this entirely.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SharedCoreSmokeScreen()
                }
            }
        }
    }
}

@Composable
private fun SharedCoreSmokeScreen() {
    val lines = remember {
        val model = RecommendationModel.createDefault()
        val order = Shuffle.seedWindow(queueLength = 12, pinned = 4, weights = List(12) { 1.0 })
        // A track that plays flat out for 150s and then fades for 50s.
        val envelope = List(300) { 1.0f } + List(100) { 0.2f }
        val mixOut = MixPoints.mixOutPoint(envelope, duration = 200.0)

        listOf(
            "shared module is linked",
            "feature vector size: ${Features.FEATURE_SIZE}",
            "model shape ok: ${RecommendationModel.hasExpectedShape(model)}",
            "shuffle window: $order",
            "mix out of a 200s track with a 50s outro: ${mixOut?.toInt()}s",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Drive Music", style = MaterialTheme.typography.headlineMedium)
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
