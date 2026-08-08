package com.drivemusic.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R

/**
 * The signed-out screen — a port of `SignInView.swift`.
 *
 * The copy is the point, not decoration. Android showed a title, one line, and a button; the iOS
 * version says what the app does and what it will do with Drive access before asking for it. That
 * wording exists because Google's OAuth verification asked for it: a screen that says only "Sign
 * in" does not tell anyone what they are consenting to, and read-only is a promise worth making
 * where it can be read rather than in a policy nobody opens.
 */
@Composable
fun SignInScreen(
    isSigningIn: Boolean,
    errorMessage: String?,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .widthIn(max = 400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        // The real launcher icon, not a generic note-in-a-circle. This is the first screen a new
        // user sees, and showing the same mark they just tapped is what makes it read as this app
        // rather than as a sign-in sheet that could belong to anything.
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp)),
        )

        Text(
            stringResource(R.string.drive_music),
            style = MaterialTheme.typography.titleLarge,
        )

        Text(
            stringResource(R.string.drive_music_is_a_personal_audio_player_for_the_music_files_a),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Text(
            stringResource(R.string.sign_in_with_google_to_grant_read_only_access_to_your_drive_),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (errorMessage != null) {
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = onSignIn,
            enabled = !isSigningIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSigningIn) {
                // Sized to the label it replaces, so the button does not change height when it
                // starts working.
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.sign_in_with_google))
            }
        }

        Text(
            stringResource(R.string.by_continuing_you_agree_to_the_terms_of_service_and_privacy_),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
