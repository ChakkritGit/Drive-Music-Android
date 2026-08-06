package com.drivemusic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.drivemusic.android.audio.TransitionDemoScreen

/**
 * Currently hosts the transition bench and nothing else.
 *
 * The real app has a library, a queue and a Now Playing surface; none of that exists yet, and
 * building it before knowing whether the audio engine sounds right would be building on an
 * unverified foundation. So the one screen that exists is the one that answers that question.
 */
@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TransitionDemoScreen()
                }
            }
        }
    }
}
