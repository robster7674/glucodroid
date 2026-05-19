package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.data.journal.JournalTreatmentUploader
import tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.cardShape

private enum class NightscoutMode { OFF, UPLOAD, FOLLOW }

private sealed class TestState {
    object Idle : TestState()
    object Testing : TestState()
    data class Result(
        val serverCode: Int,
        val entriesCode: Int,
        val lastUploadCode: Int,
        val lastUploadTime: Long,
    ) : TestState()
    data class Err(val message: String) : TestState()
}

private val SHA1_SECRET_REGEX = Regex("^[0-9a-fA-F]{40}$")

private fun applyNightscoutTestAuth(
    connection: java.net.HttpURLConnection,
    baseUrl: String,
    secret: String,
    useV3: Boolean
) {
    val trimmed = secret.trim()
    if (trimmed.isEmpty()) return
    val alreadyBearer = trimmed.startsWith("Bearer ", ignoreCase = true) ||
        trimmed.startsWith("token=", ignoreCase = true)
    if (useV3 && !alreadyBearer && !SHA1_SECRET_REGEX.matches(trimmed)) {
        val encodedSecret = java.net.URLEncoder.encode(trimmed, Charsets.UTF_8.name())
        val tokenConnection = (java.net.URL("$baseUrl/api/v2/authorization/request/$encodedSecret").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (tokenConnection.responseCode in 200..299) {
                val body = tokenConnection.inputStream.bufferedReader().use { it.readText() }
                val token = org.json.JSONObject(body).optString("token")
                if (token.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    return
                }
            }
        } catch (_: Exception) {
            // Fall back to the v1/follower auth header below.
        } finally {
            tokenConnection.disconnect()
        }
    }
    NightscoutFollowerRegistry.applyAuth(connection, trimmed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightscoutSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var url by rememberSaveable { mutableStateOf(Natives.getnightuploadurl() ?: "") }
    var secret by rememberSaveable { mutableStateOf(Natives.getnightuploadsecret() ?: "") }
    var sendTreatments by rememberSaveable { mutableStateOf(Natives.getpostTreatments()) }
    var receiveTreatments by rememberSaveable { mutableStateOf(JournalTreatmentUploader.getReceiveTreatments()) }
    var isV3 by rememberSaveable { mutableStateOf(Natives.getnightscoutV3()) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var lastResponseCode by rememberSaveable { mutableStateOf(0) }
    var lastAttemptTime by rememberSaveable { mutableStateOf(0L) }
    var lastSuccessTime by rememberSaveable { mutableStateOf(0L) }
    var retryMinutes by rememberSaveable { mutableStateOf(0) }
    var uploaderRunning by rememberSaveable { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }

    val followerConfig = remember { NightscoutFollowerRegistry.loadConfig(context) }
    var mode by rememberSaveable {
        mutableStateOf(
            when {
                followerConfig.enabled -> NightscoutMode.FOLLOW
                Natives.getuseuploader() -> NightscoutMode.UPLOAD
                else -> NightscoutMode.OFF
            }
        )
    }

    fun persistSettings() {
        Natives.setNightUploader(url.trim(), secret.trim(), mode == NightscoutMode.UPLOAD, isV3)
        Natives.setpostTreatments(sendTreatments)
        JournalTreatmentUploader.setReceiveTreatments(receiveTreatments)
        NightscoutFollowerRegistry.saveConfig(context, mode == NightscoutMode.FOLLOW, url, secret)
    }

    fun refreshStatus() {
        lastResponseCode = Natives.getnightscoutlastresponsecode()
        lastAttemptTime = Natives.getnightscoutlastattempttime()
        lastSuccessTime = Natives.getnightscoutlastsuccesstime()
        retryMinutes = Natives.getnightscoutretryminutes()
        uploaderRunning = Natives.getnightscoutuploaderrunning()
    }

    fun requireUrl(): Boolean {
        if (NightscoutFollowerRegistry.normalizeUrl(url).isBlank()) {
            Toast.makeText(context, context.getString(R.string.nightscout_follow_url_required), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    fun testConnection() {
        if (!requireUrl()) return
        val capturedUploadCode = lastResponseCode
        val capturedUploadTime = lastAttemptTime
        testState = TestState.Testing
        coroutineScope.launch {
            testState = withContext(Dispatchers.IO) {
                try {
                    val base = NightscoutFollowerRegistry.normalizeUrl(url)
                    val sec = secret.trim()

                    // Phase 1: server reachability (no auth required on most servers)
                    val serverCode = (java.net.URL("$base/api/v1/status.json").openConnection()
                            as java.net.HttpURLConnection).run {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        val code = responseCode
                        disconnect()
                        code
                    }

                    // Phase 2: entries endpoint with auth — proves the actual upload path works
                    val entriesCode = (java.net.URL("$base/api/v1/entries/sgv.json?count=1").openConnection()
                            as java.net.HttpURLConnection).run {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        NightscoutFollowerRegistry.applyAuth(this, sec)
                        val code = responseCode
                        disconnect()
                        code
                    }

                    TestState.Result(
                        serverCode = serverCode,
                        entriesCode = entriesCode,
                        lastUploadCode = capturedUploadCode,
                        lastUploadTime = capturedUploadTime,
                    )
                } catch (e: Exception) {
                    TestState.Err(e.localizedMessage?.take(80) ?: context.getString(R.string.status_connection_failed))
                }
            }
        }
    }

    LaunchedEffect(mode) {
        while (true) {
            refreshStatus()
            delay(if (mode == NightscoutMode.UPLOAD) 5_000L else 15_000L)
        }
    }

    DisposableEffect(Unit) {
        refreshStatus()
        onDispose { persistSettings() }
    }

    fun formatStatusTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return context.getString(R.string.nightscout_status_never)
        return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
            java.util.Locale.getDefault()
        ).format(java.util.Date(epochSeconds * 1000L))
    }

    val uploaderSummary = when {
        uploaderRunning -> context.getString(R.string.nightscout_status_running)
        retryMinutes > 0 -> context.getString(R.string.nightscout_status_retry_in, retryMinutes)
        else -> context.getString(R.string.nightscout_status_waiting)
    }
    val responseSummary = when {
        lastResponseCode == 0 && lastAttemptTime <= 0L -> context.getString(R.string.nightscout_status_waiting)
        lastResponseCode == -2 -> context.getString(R.string.nightscout_status_response_invalid_url)
        lastResponseCode in 200..299 -> context.getString(R.string.nightscout_status_response_ok, lastResponseCode)
        lastResponseCode == 404 -> context.getString(R.string.nightscout_status_response_404)
        lastResponseCode == 413 -> context.getString(R.string.nightscout_status_response_413)
        lastResponseCode > 0 -> context.getString(R.string.nightscout_status_response_error, lastResponseCode)
        else -> context.getString(R.string.nightscout_status_waiting)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nightscout_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // URL + secret always at the top and always editable — needed for both upload and follow
            item("nightscout_connection_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = cardShape(CardPosition.SINGLE),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { newUrl ->
                                url = newUrl
                                // Reset mode to Off when URL is cleared so stale mode doesn't persist
                                if (newUrl.isBlank() && mode != NightscoutMode.OFF) {
                                    if (mode == NightscoutMode.FOLLOW) NightscoutFollowerRegistry.disableFollowerSensor(context)
                                    mode = NightscoutMode.OFF
                                    persistSettings()
                                }
                                testState = TestState.Idle
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.nightscout_url_label)) },
                            placeholder = { Text(stringResource(R.string.nightscout_url_placeholder)) },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                        )
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; testState = TestState.Idle },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.api_secret_label)) },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                persistSettings()
                                keyboardController?.hide()
                                navController.popBackStack()
                            }),
                            trailingIcon = {
                                IconButton(onClick = { showSecret = !showSecret }) {
                                    Icon(
                                        imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Mode selector — upload and follow are mutually exclusive
            item("nightscout_mode_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = cardShape(CardPosition.SINGLE),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.nightscout_mode_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = mode == NightscoutMode.OFF,
                                onClick = {
                                    focusManager.clearFocus()
                                    if (mode == NightscoutMode.FOLLOW) NightscoutFollowerRegistry.disableFollowerSensor(context)
                                    mode = NightscoutMode.OFF
                                    persistSettings()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                label = { Text(stringResource(R.string.nightscout_mode_off)) }
                            )
                            SegmentedButton(
                                selected = mode == NightscoutMode.UPLOAD,
                                onClick = {
                                    focusManager.clearFocus()
                                    if (!requireUrl()) return@SegmentedButton
                                    if (mode == NightscoutMode.FOLLOW) NightscoutFollowerRegistry.disableFollowerSensor(context)
                                    mode = NightscoutMode.UPLOAD
                                    persistSettings()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                icon = {
                                    SegmentedButtonDefaults.Icon(active = mode == NightscoutMode.UPLOAD) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                                    }
                                },
                                label = { Text(stringResource(R.string.nightscout_mode_upload)) }
                            )
                            SegmentedButton(
                                selected = mode == NightscoutMode.FOLLOW,
                                onClick = {
                                    focusManager.clearFocus()
                                    if (!requireUrl()) return@SegmentedButton
                                    mode = NightscoutMode.FOLLOW
                                    persistSettings()
                                    NightscoutFollowerRegistry.enableFollowerSensor(context, url, secret)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                icon = {
                                    SegmentedButtonDefaults.Icon(active = mode == NightscoutMode.FOLLOW) {
                                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                                    }
                                },
                                label = { Text(stringResource(R.string.nightscout_mode_follow)) }
                            )
                        }
                    }
                }
            }

            // Test connection — available regardless of mode
            item("nightscout_test") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { testConnection() },
                        enabled = testState !is TestState.Testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                    ) {
                        if (testState is TestState.Testing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.nightscout_test_testing))
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.nightscout_test_connection))
                        }
                    }
                    when (val s = testState) {
                        is TestState.Result -> {
                            val serverOk = s.serverCode in 200..299
                            val entriesOk = s.entriesCode in 200..299
                            Column(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (serverOk)
                                        stringResource(R.string.nightscout_test_server_ok, s.serverCode)
                                    else
                                        stringResource(R.string.nightscout_test_server_err, s.serverCode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (serverOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = when {
                                        entriesOk -> stringResource(R.string.nightscout_test_entries_ok, s.entriesCode)
                                        s.entriesCode == 401 || s.entriesCode == 403 ->
                                            stringResource(R.string.nightscout_test_entries_auth_err, s.entriesCode)
                                        else -> stringResource(R.string.nightscout_test_entries_err, s.entriesCode)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entriesOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                if (mode == NightscoutMode.UPLOAD && s.lastUploadTime > 0L && s.lastUploadCode != 0) {
                                    val uploadOk = s.lastUploadCode in 200..299
                                    Text(
                                        text = if (uploadOk)
                                            stringResource(R.string.nightscout_test_last_upload_ok, formatStatusTime(s.lastUploadTime), s.lastUploadCode)
                                        else
                                            stringResource(R.string.nightscout_test_last_upload_err, formatStatusTime(s.lastUploadTime), s.lastUploadCode),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (uploadOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        is TestState.Err -> Text(
                            text = stringResource(R.string.nightscout_test_error, s.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        else -> {}
                    }
                }
            }

            // Upload-only items
            if (mode == NightscoutMode.UPLOAD) {
                item("nightscout_status_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        shape = cardShape(CardPosition.SINGLE),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = stringResource(R.string.status), style = MaterialTheme.typography.titleMedium)
                            Text(text = uploaderSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = responseSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = stringResource(R.string.nightscout_status_last_attempt, formatStatusTime(lastAttemptTime)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.nightscout_status_last_success, formatStatusTime(lastSuccessTime)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item("nightscout_options_group") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingsSwitchItem(
                            title = stringResource(R.string.sendamounts),
                            subtitle = stringResource(R.string.nightscout_send_amounts_desc),
                            checked = sendTreatments,
                            onCheckedChange = {
                                sendTreatments = it
                                Natives.setpostTreatments(it)
                            },
                            icon = Icons.Default.Medication,
                            iconTint = MaterialTheme.colorScheme.primary,
                            enabled = true,
                            position = CardPosition.TOP
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.nightscout_receive_amounts),
                            subtitle = stringResource(R.string.nightscout_receive_amounts_desc),
                            checked = receiveTreatments,
                            onCheckedChange = {
                                receiveTreatments = it
                                JournalTreatmentUploader.setReceiveTreatments(it)
                            },
                            icon = Icons.Default.Link,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            enabled = isActive,
                            position = CardPosition.MIDDLE
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.nightscout_use_v3_api),
                            subtitle = stringResource(R.string.experimental),
                            checked = isV3,
                            onCheckedChange = { isV3 = it },
                            icon = Icons.Default.Science,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            enabled = true,
                            position = CardPosition.BOTTOM
                        )
                    }
                }

                item("nightscout_send_now") {
                    Button(
                        onClick = {
                            persistSettings()
                            Natives.wakeuploader()
                            refreshStatus()
                            Toast.makeText(context, context.getString(R.string.sending_now), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.sendnow))
                    }
                }

                item("nightscout_resend_reset") {
                    OutlinedButton(
                        onClick = {
                            persistSettings()
                            Natives.resetuploader()
                            refreshStatus()
                            Toast.makeText(context, context.getString(R.string.resend_triggered), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.resend_data_reset))
                    }
                }
            }
        }
    }
}
