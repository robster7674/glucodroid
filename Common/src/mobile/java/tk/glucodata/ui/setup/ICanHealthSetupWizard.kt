// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Setup Wizard.
// iCan setup is QR/manual onboarding-first:
//   - scan the onboarding SN / active code
//   - let the driver discover the BLE peripheral in the background
// Account ID stays hidden from the normal flow; bundled keys are selected
// automatically.
//
// This file is the presentation layer. The state machine lives in
// ICanHealthSetupState; the singleton warm-up lives in
// ICanHealthSingletons. Keep this file focused on layout and event
// dispatch.

package tk.glucodata.ui.setup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.icanhealth.ICanHealthConstants
import tk.glucodata.drivers.icanhealth.ICanHealthRegistry
import tk.glucodata.drivers.icanhealth.ICanHealthSingletons

private const val ICAN_HEALTH_ONBOARDING_EXAMPLE = "726022F50005"
private const val TAG = "ICanHealthSetupWizard"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICanHealthSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit,
) {
    // Compose 1.11.x + R8 -repackageclasses can leave a Kotlin `object`'s
    // INSTANCE field null on first read if its only call site is a
    // runCatching { } in the driver. Force the iCan singletons to load
    // before any Compose measure can reach them, mirroring the pattern
    // AndroidX uses for `androidx.lifecycle.Lifecycle`.
    ICanHealthSingletons.ensureInitialized()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ICanHealthSetupState() }
    val ui = rememberWizardUiMetrics()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        val pending = state.onPermissionResult(granted)
        if (pending != null) {
            startAttach(scope, state, context, pending)
        }
    }

    fun requestPermissionsAndAttach(rawCode: String) {
        val normalized = ICanHealthConstants.normalizeOnboardingDeviceSn(rawCode)
        if (!state.isAttachable(normalized) { it }) return
        if (!hasBleScanPermissions(context)) {
            val required = requiredBleScanPermissions()
            if (required.isNotEmpty()) {
                // Stash the code on the state so the launcher callback can
                // re-enter startAttach with it after the user responds.
                state.requestPermissionsThenAttach(normalized) { it }
                permissionLauncher.launch(required)
                return
            }
        }
        startAttach(scope, state, context, normalized)
    }

    val launchFullscreenScan = rememberUnifiedQrScanLauncher(
        requestCode = tk.glucodata.MainActivity.REQUEST_BARCODE,
        title = context.getString(R.string.icanhealth_sensor),
        onScanResult = ::requestPermissionsAndAttach
    )

    BackHandler {
        when (state.onBack()) {
            BackOutcome.Consumed -> Unit
            BackOutcome.Dismiss -> onDismiss()
        }
    }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == ICanHealthSetupStep.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    if (state.showManualEntry) {
        ICanHealthManualEntryDialog(
            initialValue = state.lastOnboardingCode,
            onDismiss = { state.dismissManualEntry() },
            onConfirm = { normalized ->
                state.dismissManualEntry()
                requestPermissionsAndAttach(normalized)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.icanhealth_sensor)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                // Material3 1.4 expressive spec expects an explicit
                // colors= on TopAppBar so the top app bar surface
                // resolves via the new color scheme without depending on
                // the implicit default. This also dodges the R8-static
                // NPE that bit the old style.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // `key(currentStep)` is the new-Material-3-Expressive pattern for
        // stepping a state-machine UI: it forces a fresh composition
        // subtree per step so the AnimatedContent + onComplete LaunchedEffect
        // lifecycle is independent across steps. Without this, the same
        // composable identities get reused and the recompose path can hit
        // a static-singleton read on the wrong step.
        key(state.currentStep) {
            AnimatedContent(
                targetState = state.currentStep,
                modifier = Modifier.padding(padding),
                transitionSpec = {
                    val enter = fadeIn(animationSpec = tween(durationMillis = 220))
                    val exit = fadeOut(animationSpec = tween(durationMillis = 180))
                    enter togetherWith exit
                },
                contentKey = { it },
                label = "ICanHealthWizard",
            ) { step ->
                when (step) {
                    ICanHealthSetupStep.ONBOARDING -> ICanHealthOnboardingStep(
                        ui = ui,
                        onNavigateToReadiness = onNavigateToReadiness,
                        onInlineScanResult = ::requestPermissionsAndAttach,
                        onLaunchFullscreenScan = launchFullscreenScan,
                        onShowManualEntry = { state.openManualEntry() }
                    )

                    ICanHealthSetupStep.CONNECTING -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        SensorSetupConnectingScreen(
                            ui = ui,
                            sensorLabel = state.selectedSensorLabel.ifBlank { null }
                        )
                    }

                    ICanHealthSetupStep.SUCCESS -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        SensorSetupSuccessScreen(
                            ui = ui,
                            sensorLabel = state.selectedSensorLabel.ifBlank { null }
                        )
                    }
                }
            }
        }
    }
}

private fun startAttach(
    scope: kotlinx.coroutines.CoroutineScope,
    state: ICanHealthSetupState,
    context: android.content.Context,
    normalizedOnboardingCode: String,
) {
    val outcome = state.startAttach(normalizedOnboardingCode) { it }
    if (outcome is ICanHealthAttachOutcome.InvalidCode) return
    scope.launch {
        val result = try {
            ICanHealthRegistry.addSensor(
                context,
                displayName = null,
                address = "",
                aesKeyAscii = null,
                onboardingDeviceSnOrCode = normalizedOnboardingCode,
                authUserId = null,
            )
            ICanHealthAttachResult.Added(normalizedOnboardingCode)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add iCanHealth sensor: ${t.message}")
            Toast.makeText(
                context,
                context.getString(R.string.nobluetooth),
                Toast.LENGTH_LONG
            ).show()
            ICanHealthAttachResult.Failed(t)
        }
        state.markAttached(result)
    }
}

@Composable
private fun ICanHealthOnboardingStep(
    ui: WizardUiMetrics,
    onNavigateToReadiness: () -> Unit,
    onInlineScanResult: (String) -> Unit,
    onLaunchFullscreenScan: () -> Unit,
    onShowManualEntry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ui.horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(ui.spacerMedium))

        tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)
        Spacer(Modifier.height(ui.spacerMedium))

        InlineQrScannerCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (ui.compact) 320.dp else 380.dp),
            onScanResult = onInlineScanResult,
            onManualFallback = onLaunchFullscreenScan,
            manualFallbackLabel = stringResource(R.string.scan_qr_button)
        )

        Spacer(Modifier.height(ui.spacerSmall))

        Text(
            text = stringResource(R.string.icanhealth_sensor_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(ui.spacerMedium))

        OutlinedButton(
            onClick = onShowManualEntry,
            modifier = Modifier
                .fillMaxWidth()
                .height(ui.buttonHeight)
        ) {
            Text(stringResource(R.string.enter_code_manually))
        }
    }
}

@Composable
private fun ICanHealthManualEntryDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) {
        mutableStateOf(initialValue)
    }
    val normalized = remember(value) {
        ICanHealthConstants.normalizeOnboardingDeviceSn(value)
    }
    val isValid = normalized.length in
        ICanHealthSetupState.MIN_ATTACH_CODE_LEN..ICanHealthSetupState.MAX_ATTACH_CODE_LEN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_code_manually)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.uppercase().filter { it.isLetterOrDigit() }
                    },
                    label = { Text(stringResource(R.string.serial_number_label)) },
                    placeholder = { Text(ICAN_HEALTH_ONBOARDING_EXAMPLE) },
                    supportingText = {
                        Text(stringResource(R.string.serial_number_supporting, ICAN_HEALTH_ONBOARDING_EXAMPLE))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    isError = value.isNotBlank() && !isValid,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalized) },
                enabled = isValid
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
