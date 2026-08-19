package dev.otherworld.budget.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.FakeLoginFlow
import dev.otherworld.budget.data.auth.LoginFlowStart
import dev.otherworld.budget.data.auth.Session

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A one-shot event, not state: a StateFlow would re-emit (and re-open the browser)
    // on every configuration change, e.g. a screen rotation while waiting on the poll.
    LaunchedEffect(viewModel) {
        viewModel.launchBrowser.collect { url ->
            val uri = url.toUri()
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, uri)
            } catch (e: ActivityNotFoundException) {
                // No Custom Tabs provider installed -- fall back to a plain browser intent
                // so the user can still complete login. That fallback can throw in turn, on a
                // device with no browser at all (kiosk builds, stripped e-ink readers), where
                // an unguarded startActivity would crash the app on its very first screen.
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    viewModel.onBrowserUnavailable()
                }
            }
        }
    }

    LaunchedEffect(uiState.status) {
        if (uiState.status == Status.DONE) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Draws behind the system bars under enableEdgeToEdge(); inset so the fields and the
            // connect button clear the status/navigation bars and the keyboard.
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = uiState.serverUrl,
            onValueChange = viewModel::onServerUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.onboarding_address_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_address_placeholder)) },
            singleLine = true,
            isError = uiState.errorMessage != null,
            supportingText = uiState.errorMessage?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = uiState.status == Status.EDITING,
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::onConnectClicked,
            enabled = uiState.status == Status.EDITING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_connect))
        }

        if (uiState.status == Status.WAITING_FOR_BROWSER) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.onboarding_waiting_browser))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::onCancelled) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }

    // Dumb by design: the decision lives in the ViewModel's state machine (which has not made
    // any network call yet -- see Status.CONFIRM_HTTP); this dialog only renders the question.
    // Dismissing it any way other than "Connect anyway" declines.
    if (uiState.status == Status.CONFIRM_HTTP) {
        AlertDialog(
            onDismissRequest = viewModel::onHttpDeclined,
            title = { Text(stringResource(R.string.onboarding_http_title)) },
            text = {
                Text(stringResource(R.string.onboarding_http_body))
            },
            confirmButton = {
                TextButton(onClick = viewModel::onHttpConfirmed) { Text(stringResource(R.string.onboarding_connect_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onHttpDeclined) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    MaterialTheme {
        OnboardingScreen(
            onDone = {},
            viewModel = OnboardingViewModel(
                loginFlow = FakeLoginFlow(
                    start = Result.success(
                        LoginFlowStart(
                            "https://cloud.example.com/login/flow",
                            "tok",
                            "https://cloud.example.com/poll",
                        )
                    ),
                    poll = Result.success(Credentials("https://cloud.example.com", "adam", "app-pw")),
                ),
                session = object : Session {
                    override suspend fun signIn(credentials: Credentials) = Unit
                    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
                },
                theme = object : dev.otherworld.budget.data.theme.ThemePalette {
                    override fun clear() = Unit
                    override suspend fun refresh() = Unit
                    override fun refreshInBackground() = Unit
                },
            ),
        )
    }
}
