package dev.otherworld.budget.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.otherworld.budget.R

/**
 * The one-time first-run welcome. It explains what the app does and how receipt scanning works --
 * that it runs on the user's *own* Budget server -- and then hands off to Onboarding.
 *
 * Deliberately carries **no** purchasing surface: no price, no subscription UI, no link steering to
 * a checkout. Receipt scanning is a feature of the user's Budget server, described here in the same
 * neutral terms the rest of the app uses (see CaptureScreen's OcrUnavailable copy). This keeps the
 * app within Google Play's anti-steering policy and F-Droid's no-proprietary-nudge expectations --
 * the whole app is deliberately billing-free; the subscription lives on the website and the licence
 * key is entered in Budget's own Nextcloud settings, never here.
 */
@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel = hiltViewModel(),
    onGetStarted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(28.dp))

        WelcomeStep(
            "1",
            stringResource(R.string.welcome_step1_title),
            stringResource(R.string.welcome_step1_body),
        )
        WelcomeStep(
            "2",
            stringResource(R.string.welcome_step2_title),
            stringResource(R.string.welcome_step2_body),
        )
        WelcomeStep(
            "3",
            stringResource(R.string.welcome_step3_title),
            stringResource(R.string.welcome_step3_body),
            last = true,
        )

        Spacer(Modifier.height(20.dp))

        // Neutral, no steering: names where the feature lives (the user's own server) and what
        // happens without it. No price, no subscription, no link. Mirrors the app's existing
        // OcrUnavailable copy.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.welcome_ocr_notice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                viewModel.markSeen()
                onGetStarted()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.welcome_get_started))
        }
    }
}

@Composable
private fun WelcomeStep(number: String, title: String, body: String, last: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // defaultMinSize + inner padding rather than a fixed size(32.dp): at large system font
        // scales the digit grows the circle instead of clipping inside a hard 32dp box.
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (!last) Spacer(Modifier.height(20.dp))
}

@Preview(showBackground = true)
@Composable
private fun WelcomeStepPreview() {
    MaterialTheme {
        Column(Modifier.padding(24.dp)) {
            WelcomeStep("1", "Snap a receipt", "Photograph it with the camera, or share an image.")
            WelcomeStep("2", "Your server reads it", "Receipt scanning fills in the details.", last = true)
        }
    }
}
