package dev.ruimdetijd.aircav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AirCavApp()
                }
            }
        }
    }
}

data class ParsedCommand(val description: String, val tags: List<String>)

private fun parseCommand(text: String): ParsedCommand? {
    val tags = mutableListOf<String>()
    val words = mutableListOf<String>()
    for (word in text.trim().split(Regex("\\s+"))) {
        when {
            word.startsWith("+") && word.length > 1 -> tags += word.drop(1)
            word.startsWith("@") && word.length > 1 -> tags += word.drop(1)
            else -> words += word
        }
    }
    val description = words.joinToString(" ").trim()
    if (description.isEmpty()) return null
    return ParsedCommand(description, tags)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirCavApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return
    val scope = rememberCoroutineScope()

    val settingsStore = remember { SettingsStore(context) }
    val repo = remember { TaskRepository(context, settingsStore) }

    var tasks by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var contexts by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedContext by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var voiceInput by remember { mutableStateOf(false) }
    var voiceProcessing by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(activity) }
    var voiceResult by remember { mutableStateOf<String?>(null) }

    val speechLauncher = remember(activity) {
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            voiceInput = false
            if (!text.isNullOrBlank()) voiceResult = text
        }
    }
    val permissionLauncher = remember(activity) {
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            voiceInput = false
            if (!granted) {
                Toast.makeText(activity, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refresh() {
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repo.ensureOpen()
                repo.list()
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    tasks = it.tasks
                    contexts = it.contexts
                }
                busy = false
            }
        }
    }

    fun syncAfterChange() {
        if (settingsStore.load().syncUrl.isBlank()) return
        isSyncing = true
        scope.launch(Dispatchers.IO) {
            val syncResult = runCatching {
                repo.ensureOpen()
                repo.sync()
                repo.list()
            }
            withContext(Dispatchers.Main) {
                syncResult.onSuccess {
                    tasks = it.tasks
                    contexts = it.contexts
                }
                isSyncing = false
            }
        }
    }

    fun manualSync() {
        if (settingsStore.load().syncUrl.isBlank()) {
            showSettings = true
            return
        }
        isSyncing = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repo.ensureOpen()
                repo.sync()
                repo.list()
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    tasks = it.tasks
                    contexts = it.contexts
                    Toast.makeText(activity, "Synced", Toast.LENGTH_SHORT).show()
                }
                result.onFailure { e ->
                    Toast.makeText(activity, e.message ?: "sync failed", Toast.LENGTH_LONG).show()
                }
                isSyncing = false
            }
        }
    }

    fun submitParsed(description: String, tags: List<String>) {
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repo.ensureOpen()
                repo.add(description, tags)
                repo.list()
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    tasks = it.tasks
                    contexts = it.contexts
                }
                if (result.isSuccess) inputText = ""
                busy = false
                if (result.isSuccess) syncAfterChange()
            }
        }
    }

    fun submit(text: String) {
        val parsed = parseCommand(text) ?: return
        submitParsed(parsed.description, parsed.tags)
    }

    fun mistralVoice(apiKey: String, wav: java.io.File) {
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val transcript = MistralApi.transcribe(apiKey, wav)
                wav.delete()
                MistralApi.extractTask(apiKey, transcript)
            }
            val parsed = result.getOrNull()
            withContext(Dispatchers.Main) {
                result.onFailure { e ->
                    Toast.makeText(activity, "voice failed: ${e.message}", Toast.LENGTH_LONG).show()
                    busy = false
                }
                result.onSuccess { p ->
                    if (p == null) {
                        Toast.makeText(activity, "Could not understand", Toast.LENGTH_SHORT).show()
                        busy = false
                    } else {
                        Toast.makeText(activity, "\"${p.description}\"", Toast.LENGTH_SHORT).show()
                        submitParsed(p.description, p.tags)
                    }
                }
            }
        }
    }

    fun complete(uuid: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.ensureOpen()
                repo.complete(uuid)
            }
            withContext(Dispatchers.Main) {
                refresh()
                syncAfterChange()
            }
        }
    }

    fun remove(uuid: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.ensureOpen()
                repo.remove(uuid)
            }
            withContext(Dispatchers.Main) {
                refresh()
                syncAfterChange()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    voiceResult?.let { text ->
        voiceResult = null
        submit(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AirCav") },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    IconButton(onClick = { manualSync() }) {
                        Icon(Icons.Filled.CloudSync, contentDescription = "Sync")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            if (contexts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                ) {
                    FilterChip(
                        selected = selectedContext == null,
                        onClick = { selectedContext = null },
                        label = { Text("all") },
                        modifier = Modifier.padding(4.dp),
                    )
                    contexts.forEach { ctx ->
                        FilterChip(
                            selected = selectedContext == ctx,
                            onClick = { selectedContext = if (selectedContext == ctx) null else ctx },
                            label = { Text(ctx) },
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = if (busy && tasks.isEmpty()) Alignment.Center else Alignment.TopStart,
            ) {
                if (busy && tasks.isEmpty()) {
                    CircularProgressIndicator()
                } else {
                    val visible = tasks.filter { t ->
                        selectedContext == null || selectedContext in t.tags
                    }
                    if (visible.isEmpty()) {
                        Text(
                            "No tasks yet.\nType below or tap the mic to add one.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    LazyColumn {
                        items(visible.distinctBy { it.uuid }, key = { it.uuid }) { task ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(
                                    checked = false,
                                    onCheckedChange = { complete(task.uuid) },
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 12.dp),
                                ) {
                                    Text(task.description, style = MaterialTheme.typography.bodyLarge)
                                    if (task.tags.isNotEmpty()) {
                                        Text(
                                            task.tags.joinToString(" ") { "+$it" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                IconButton(onClick = { remove(task.uuid) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("buy milk +home") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(
                    onClick = { if (inputText.isNotBlank()) submit(inputText) },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add task")
                }
                IconButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            activity, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@IconButton
                        }
                        val apiKey = settingsStore.load().mistralApiKey
                        if (MistralApi.hasKey(apiKey)) {
                            if (recorder.isRecording) {
                                voiceInput = false
                                voiceProcessing = true
                                val wav = recorder.stop()
                                voiceProcessing = false
                                if (wav == null) {
                                    Toast.makeText(activity, "Nothing recorded", Toast.LENGTH_SHORT).show()
                                } else {
                                    mistralVoice(apiKey, wav)
                                }
                            } else {
                                if (!recorder.start { activity.runOnUiThread { voiceInput = false } }) {
                                    voiceInput = false
                                    Toast.makeText(activity, "Cannot start recording", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, "Recording... tap again to stop", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                                Toast.makeText(activity, "No speech recognizer available", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            voiceInput = true
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "New task")
                            }
                            speechLauncher.launch(intent)
                        }
                    },
                ) {
                    if (voiceProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (voiceInput) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop recording")
                    } else {
                        Icon(Icons.Filled.Mic, contentDescription = "Add task by voice")
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settingsStore.load(),
            onSave = {
                settingsStore.save(it)
                showSettings = false
                refresh()
                manualSync()
            },
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: Settings,
    onSave: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    var syncUrl by remember { mutableStateOf(settings.syncUrl) }
    var clientId by remember { mutableStateOf(settings.clientId) }
    var secret by remember { mutableStateOf(settings.encryptionSecret) }
    var apiKey by remember { mutableStateOf(settings.mistralApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync settings") },
        text = {
            Column {
                Text(
                    "Point this at your own taskchampion-sync-server.\n" +
                        "On Tailscale, use the machine's Tailscale IP or MagicDNS name.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = syncUrl,
                    onValueChange = { syncUrl = it },
                    label = { Text("Sync server URL") },
                    placeholder = { Text("http://100.x.y.z:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Encryption secret") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Mistral API key (optional, for voice)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "With a Mistral key, voice notes are recorded in-app and sent only to " +
                        "Mistral (Voxtral for speech, then a small model that structures it " +
                        "into description + tags). Without it, the phone's system speech " +
                        "recognizer is used.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Settings(syncUrl, clientId, secret, apiKey)) },
            ) { Text("Save & sync") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
