package com.meteor.memory.ui.life

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.R

private const val STEP_FEATURES = 0
private const val STEP_PRIVACY = 1
private const val STEP_PERMISSIONS = 2
private const val STEP_BIRTH_DATE = 3

@Composable
fun OnboardingFlowScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(STEP_FEATURES) }
    var selectedProfile by remember { mutableStateOf<BirthProfile?>(null) }

    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        step = STEP_BIRTH_DATE
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_step, step + 1),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))

        when (step) {
            STEP_FEATURES -> OnboardingTextPage(
                title = stringResource(R.string.onboarding_intro_title),
                body = stringResource(R.string.onboarding_intro_body),
                button = stringResource(R.string.onboarding_continue),
                onContinue = { step = STEP_PRIVACY },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            STEP_PRIVACY -> OnboardingTextPage(
                title = stringResource(R.string.onboarding_privacy_title),
                body = stringResource(R.string.onboarding_privacy_body),
                button = stringResource(R.string.onboarding_continue),
                onContinue = { step = STEP_PERMISSIONS },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            STEP_PERMISSIONS -> OnboardingPermissionsPage(
                notificationFeatureAvailable = viewModel.notificationFeatureAvailable,
                canRequestPermission =
                    viewModel.notificationFeatureAvailable &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                onRequestPermission = {
                    if (
                        viewModel.notificationFeatureAvailable &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        step = STEP_BIRTH_DATE
                    }
                },
                onSkip = { step = STEP_BIRTH_DATE },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            STEP_BIRTH_DATE -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_birth_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.onboarding_birth_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BirthDatePicker(
                    initialProfile = selectedProfile,
                    onProfileChanged = { selectedProfile = it },
                )
                if (selectedProfile == null) {
                    Text(
                        text = stringResource(R.string.birth_date_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (saveState.failed) {
                    Text(
                        text = stringResource(R.string.onboarding_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { selectedProfile?.let(viewModel::saveBirthProfile) },
                    enabled = selectedProfile != null && !saveState.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saveState.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(text = stringResource(R.string.onboarding_save_birth))
                    }
                }
                TextButton(
                    onClick = viewModel::skipBirthProfile,
                    enabled = !saveState.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.onboarding_skip_birth))
                }
            }
        }
    }
}

@Composable
private fun OnboardingTextPage(
    title: String,
    body: String,
    button: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = body,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(text = button)
        }
    }
}

@Composable
private fun OnboardingPermissionsPage(
    notificationFeatureAvailable: Boolean,
    canRequestPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(
                when {
                    !notificationFeatureAvailable -> R.string.onboarding_permissions_unavailable
                    canRequestPermission -> R.string.onboarding_permissions_body
                    else -> R.string.onboarding_permissions_not_required
                },
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (canRequestPermission) {
                        R.string.onboarding_allow_notifications
                    } else {
                        R.string.onboarding_permissions_continue
                    },
                ),
            )
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.onboarding_skip_permission))
        }
    }
}

@Composable
fun OnboardingLoadFailureScreen(onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_load_failed),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.life_retry))
            }
        }
    }
}
