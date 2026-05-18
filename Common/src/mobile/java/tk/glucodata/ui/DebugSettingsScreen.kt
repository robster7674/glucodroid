package tk.glucodata.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import tk.glucodata.ui.components.StyledSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.LogSanitizer
import tk.glucodata.Natives
import tk.glucodata.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var logType by remember { mutableStateOf(LogType.TRACE) }
    var isEnabled by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("Loading...") }

    LaunchedEffect(Unit) {
        isEnabled = if (logType == LogType.TRACE) Natives.islogging() else Natives.islogcat()
    }

    // Polling loop
    LaunchedEffect(logType) {
        isEnabled = if (logType == LogType.TRACE) Natives.islogging() else Natives.islogcat()

        withContext(Dispatchers.IO) {
            while (isActive) {
                val fileName = if (logType == LogType.TRACE) "logs/trace.log" else "logs/logcat.txt"
                val file = File(context.filesDir, fileName)

                val content = if (file.exists()) {
                    val fileSize = file.length()
                    if (fileSize > 50 * 1024) {
                        try {
                            file.inputStream().use { stream ->
                                stream.skip(fileSize - 50 * 1024)
                                "[...truncated...]\n" + stream.bufferedReader().readText()
                            }
                        } catch (e: Exception) {
                            "Error reading file: ${e.message}"
                        }
                    } else {
                        file.readText()
                    }
                } else {
                    if (isEnabled) "Log enabled. Waiting for entries..." else "Log unchecked. Enable to start logging."
                }

                withContext(Dispatchers.Main) { logContent = content }
                delay(1000)
            }
        }
    }

    fun logFile(): File {
        val name = if (logType == LogType.TRACE) "logs/trace.log" else "logs/logcat.txt"
        return File(context.filesDir, name)
    }

    fun sanitizedContent(): String = LogSanitizer.sanitize(
        try { logFile().readText() } catch (e: Exception) { logContent }
    )

    // Save to file (sanitized)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { targetUri ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val sanitized = sanitizedContent()
                    context.contentResolver.openOutputStream(targetUri)?.use { it.writer().use { w -> w.write(sanitized) } }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { logContent += "\n[Error saving file: ${e.message}]" }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.debug_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (logType == LogType.TRACE) Natives.zeroLog() else Natives.zeroLogcat()
                        logContent = "Cleared."
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = context.getString(R.string.clear))
                    }
                    // Copy all visible log content to clipboard (unsanitized — user's own data)
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(logContent))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = context.getString(R.string.copy_log))
                    }
                    // Share sanitized log via system share sheet
                    IconButton(onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val sanitized = sanitizedContent()
                                val exportDir = File(context.cacheDir, "exports").also { it.mkdirs() }
                                val exportFile = File(exportDir, if (logType == LogType.TRACE) "trace.log" else "logcat.txt")
                                exportFile.writeText(sanitized)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                withContext(Dispatchers.Main) {
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_log)))
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { logContent += "\n[Error sharing: ${e.message}]" }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = context.getString(R.string.share_log))
                    }
                    IconButton(onClick = {
                        val name = if (logType == LogType.TRACE) "trace.log" else "logcat.txt"
                        saveLauncher.launch(name)
                    }) {
                        Icon(Icons.Default.Save, contentDescription = context.getString(R.string.save))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = logType == LogType.TRACE, onClick = { logType = LogType.TRACE })
                    Text(context.getString(R.string.trace_log))
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = logType == LogType.LOGCAT, onClick = { logType = LogType.LOGCAT })
                    Text(context.getString(R.string.logcat))
                }

                Spacer(modifier = Modifier.width(16.dp))
                StyledSwitch(
                    checked = isEnabled,
                    onCheckedChange = {
                        isEnabled = it
                        if (logType == LogType.TRACE) Natives.dolog(it) else Natives.dologcat(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val scrollState = rememberScrollState()
            var isAtBottom by remember { mutableStateOf(true) }

            LaunchedEffect(scrollState.value, scrollState.maxValue) {
                isAtBottom = (scrollState.maxValue - scrollState.value) < 50
            }

            LaunchedEffect(logContent) {
                if (isAtBottom) scrollState.scrollTo(scrollState.maxValue)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background((MaterialTheme.colorScheme.secondaryContainer), RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    .drawWithContent {
                        drawContent()
                        val containerHeight = size.height
                        val totalContentHeight = scrollState.maxValue + containerHeight
                        if (totalContentHeight > containerHeight) {
                            val thumbHeight = (containerHeight * containerHeight / totalContentHeight).coerceAtLeast(20.dp.toPx())
                            val scrollRange = totalContentHeight - containerHeight
                            val thumbRange = containerHeight - thumbHeight
                            val thumbOffset = if (scrollRange > 0) (scrollState.value / scrollRange) * thumbRange else 0f
                            drawRect(
                                color = Color.White.copy(alpha = 0.3f),
                                topLeft = Offset(size.width - 4.dp.toPx(), thumbOffset),
                                size = Size(4.dp.toPx(), thumbHeight)
                            )
                        }
                    }
            ) {
                SelectionContainer {
                    Text(
                        text = logContent,
                        color = Color(0xFFCCCCCC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

enum class LogType {
    TRACE, LOGCAT
}
