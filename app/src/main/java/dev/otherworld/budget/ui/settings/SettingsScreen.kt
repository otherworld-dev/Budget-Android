package dev.otherworld.budget.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.BuildConfig
import dev.otherworld.budget.R

/**
 * Account, queue status, and licence information -- the one screen where a billing
 * surface could most easily creep in. There is deliberately no purchase entry, no
 * subscription status, no licence-key field, and no outbound link: the licences list,
 * the privacy policy and the AGPL line below are static, in-app text, not links to
 * anywhere. (Play wants the privacy policy reachable inside the app; the website's copy
 * sits one click from the scanning plans, so it is shown here rather than linked.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.signedOut) {
        if (uiState.signedOut) onSignedOut()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_account))
            Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.labelMedium)
            Text(uiState.server ?: stringResource(R.string.settings_not_signed_in), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_signed_in_as), style = MaterialTheme.typography.labelMedium)
            Text(uiState.loginName ?: stringResource(R.string.settings_value_placeholder), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showSignOutConfirm = true }, enabled = !uiState.signingOut) {
                Text(if (uiState.signingOut) stringResource(R.string.settings_signing_out) else stringResource(R.string.settings_sign_out))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            SectionHeader(stringResource(R.string.settings_queue))
            // Same vocabulary and partition as Capture's two banners -- "queued" and "ready to
            // review" -- so cross-checking the screens never shows one row under two names.
            // failed is a parenthetical because it is a subset of queued, not a third bucket.
            Text(
                if (uiState.failed > 0) {
                    stringResource(
                        R.string.settings_queue_status_failed,
                        uiState.queued,
                        uiState.failed,
                        uiState.awaitingReview,
                    )
                } else {
                    stringResource(
                        R.string.settings_queue_status,
                        uiState.queued,
                        uiState.awaitingReview,
                    )
                }
            )
            if (uiState.failed > 0) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::onRetryFailedClicked) {
                    Text(stringResource(R.string.settings_retry_failed))
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            SectionHeader(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showLicences = true }) {
                Text(stringResource(R.string.settings_licences))
            }
            TextButton(onClick = { showPrivacy = true }) {
                Text(stringResource(R.string.settings_privacy))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_agpl),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            // "transactions": clearAll() deletes every queue row, and since Quick Add that
            // includes photo-less manual entries. Naming only receipts understates what a user
            // with a queued manual entry is about to lose, in the one dialog that has to be
            // exact about it.
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    viewModel.onSignOutClicked()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showLicences) {
        LicencesDialog(onDismiss = { showLicences = false })
    }

    if (showPrivacy) {
        PrivacyDialog(onDismiss = { showPrivacy = false })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

private data class OssLicence(val name: String, val licence: String)

/**
 * Every dependency this app bundles (see app/build.gradle.kts): Kotlin, AndroidX/Jetpack,
 * Dagger Hilt, Square's Retrofit/OkHttp, and Coil, all under Apache License 2.0. No
 * proprietary or Play-Services dependency is ever bundled -- see the F-Droid check in CI.
 */
private val bundledLicences = listOf(
    OssLicence("Kotlin", "Apache License 2.0"),
    OssLicence("Kotlin Coroutines", "Apache License 2.0"),
    OssLicence("Kotlin Serialization", "Apache License 2.0"),
    OssLicence("AndroidX / Jetpack", "Apache License 2.0"),
    OssLicence("Jetpack Compose", "Apache License 2.0"),
    OssLicence("Jetpack Room", "Apache License 2.0"),
    OssLicence("Jetpack WorkManager", "Apache License 2.0"),
    OssLicence("Jetpack Security Crypto", "Apache License 2.0"),
    OssLicence("Jetpack CameraX", "Apache License 2.0"),
    OssLicence("Dagger Hilt", "Apache License 2.0"),
    OssLicence("OkHttp", "Apache License 2.0"),
    OssLicence("Retrofit", "Apache License 2.0"),
    OssLicence("Coil", "Apache License 2.0"),
)

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_oss_licences_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth()) {
                items(bundledLicences) { entry ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry.licence,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_privacy)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_privacy_body))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
