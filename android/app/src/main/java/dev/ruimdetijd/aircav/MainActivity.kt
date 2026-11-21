package dev.ruimdetijd.aircav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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

private const val TAG = "AirCav"

data class ParsedCommand(
    val description: String,
    val tags: List<String>,
    val project: String = "",
)

private val deleteCmd = Regex("^(?:delete|verwijder|remove)\\s+(\\d+)$", RegexOption.IGNORE_CASE)

private fun parseCommand(text: String): ParsedCommand? {
    val words = text.trim().split(Regex("\\s+")).toMutableList()
    var project = ""

    // "project:foo" as a single token
    words.removeAll { word ->
        val m = Regex("^project:(.+)$").find(word)
        if (m != null) {
            project = m.groupValues[1]
            true
        } else {
            false
        }
    }

    // "project foo" as two tokens
    val wordsIter = words.toList()
    val keep = mutableListOf<String>()
    var skip = false
    for (w in wordsIter) {
        if (skip) {
            skip = false
            continue
        }
        if ((w == "project" || w == "project:") && wordsIter.size > wordsIter.indexOf(w) + 1) {
            project = wordsIter[wordsIter.indexOf(w) + 1]
            skip = true
            continue
        }
        keep += w
    }
    words.clear()
    words.addAll(keep)

    val tags = mutableListOf<String>()
    val descWords = mutableListOf<String>()
    for (word in words) {
        when {
            word.startsWith("+") && word.length > 1 -> tags += word.drop(1)
            word.startsWith("@") && word.length > 1 -> tags += word.drop(1)
            else -> descWords += word
        }
    }
    val description = descWords.joinToString(" ").trim()
    if (description.isEmpty()) return null
    return ParsedCommand(description, tags, project)
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
    var contextDefs by remember { mutableStateOf<List<ContextDef>>(emptyList()) }
    var selectedContext by remember {
        mutableStateOf<String?>(settingsStore.getSelectedContext())
    }

    fun selectContext(name: String?) {
        selectedContext = name
        settingsStore.setSelectedContext(name)
    }
    var inputText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TaskItem?>(null) }
    var voiceInput by remember { mutableStateOf(false) }
    var voiceProcessing by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(activity) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        voiceInput = false
        if (!granted) {
            Toast.makeText(activity, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun refresh() {
        contextDefs = parseContexts(settingsStore.load().contextsText)
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repo.ensureOpen()
                repo.list()
            }
            withContext(Dispatchers.Main) {
                result.onFailure { e ->
                    Log.e(TAG, "load failed", e)
                }
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
            Toast.makeText(
                activity,
                "No sync server configured yet",
                Toast.LENGTH_SHORT,
            ).show()
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
                    Log.e(TAG, "sync failed", e)
                    Toast.makeText(activity, e.message ?: "sync failed", Toast.LENGTH_LONG).show()
                }
                isSyncing = false
            }
        }
    }

    fun submitParsed(description: String, tags: List<String>, project: String) {
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repo.ensureOpen()
                repo.add(description, tags, project)
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
        val del = deleteCmd.find(text.trim())
        if (del != null) {
            val num = del.groupValues[1].toIntOrNull()
            val target = tasks.firstOrNull { it.id == num }
            if (target == null) {
                Toast.makeText(activity, "No task #$num", Toast.LENGTH_SHORT).show()
                return
            }
            pendingDelete = target
            return
        }
        val parsed = parseCommand(text) ?: return
        submitParsed(parsed.description, parsed.tags, parsed.project)
    }

    fun mistralVoice(apiKey: String, wav: java.io.File) {
        voiceProcessing = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val transcript = MistralApi.transcribe(apiKey, wav)
                wav.delete()
                transcript
            }
            withContext(Dispatchers.Main) {
                voiceProcessing = false
                result.onFailure { e ->
                    Log.e(TAG, "voice failed", e)
                    VoiceLog.append(activity) {
                        put("error", e.message ?: "unknown error")
                    }
                    Toast.makeText(activity, "voice failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                result.onSuccess { text ->
                    VoiceLog.append(activity) {
                        put("transcript", text)
                    }
                    val del = deleteCmd.find(text.trim())
                    if (del != null) {
                        val num = del.groupValues[1].toIntOrNull()
                        val target = tasks.firstOrNull { it.id == num }
                        VoiceLog.append(activity) {
                            put("delete_command", num)
                            if (target != null) put("target", target.description)
                        }
                        if (target == null) {
                            Toast.makeText(activity, "No task #$num", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingDelete = target
                        }
                        return@onSuccess
                    }
                    val parsed = parseCommand(text)
                    if (parsed == null) {
                        VoiceLog.append(activity) {
                            put("note", "transcript could not be parsed into a task")
                        }
                        Toast.makeText(activity, "Could not understand", Toast.LENGTH_SHORT).show()
                    } else {
                        VoiceLog.append(activity) {
                            put("description", parsed.description)
                            put("tags", org.json.JSONArray(parsed.tags))
                            if (parsed.project.isNotEmpty()) put("project", parsed.project)
                        }
                        submitParsed(parsed.description, parsed.tags, parsed.project)
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
            // chip values: settings-defined contexts (name -> project), or tags as fallback
            data class Chip(val name: String, val project: String?)
            val chips = if (contextDefs.isNotEmpty()) {
                contextDefs.map { Chip(it.name, it.project) }
            } else {
                contexts.map { Chip(it, null) }
            }
            if (chips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                ) {
                    FilterChip(
                        selected = selectedContext == null,
                        onClick = { selectContext(null) },
                        label = { Text("all") },
                        modifier = Modifier.padding(4.dp),
                    )
                    chips.forEach { chip ->
                        FilterChip(
                            selected = selectedContext == chip.name,
                            onClick = {
                                selectContext(
                                    if (selectedContext == chip.name) null else chip.name,
                                )
                            },
                            label = { Text(chip.name) },
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
                    val selectedChip = chips.firstOrNull { it.name == selectedContext }
                    fun matches(t: TaskItem): Boolean {
                        val chip = selectedChip ?: return true
                        return if (chip.project != null) {
                            t.project == chip.project
                        } else {
                            chip.name in t.tags
                        }
                    }
                    val visible = tasks
                        .filter { matches(it) }
                        .sortedByDescending { it.entry ?: "" }
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
                                    Text(
                                        (task.id?.let { "$it. " } ?: "") + task.description,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (task.tags.isNotEmpty() || task.project != null) {
                                        val bits = buildList {
                                            task.project?.let { add("#$it") }
                                            addAll(task.tags.map { "+$it" })
                                        }
                                        Text(
                                            bits.joinToString(" "),
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
                        if (!MistralApi.hasKey(apiKey)) {
                            Toast.makeText(activity, "Set a Mistral API key in settings", Toast.LENGTH_SHORT).show()
                            showSettings = true
                            return@IconButton
                        }
                        if (recorder.isRecording) {
                            voiceInput = false
                            val wav = recorder.stop()
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
                                voiceInput = true
                                Toast.makeText(activity, "Recording... tap again to stop", Toast.LENGTH_SHORT).show()
                            }
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

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete task?") },
            text = {
                Text(
                    (target.id?.let { "#$it " } ?: "") + target.description +
                        if (target.project != null) "  (project:${target.project})" else "",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        remove(target.uuid)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
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
    var contextsText by remember { mutableStateOf(settings.contextsText) }

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
                    label = { Text("Mistral API key (for voice input)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = contextsText,
                    onValueChange = { contextsText = it },
                    label = { Text("Contexts (name=project, one per line)") },
                    placeholder = { Text("nde=nde\ncodere=rdt.codere\nhome=home") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Settings(syncUrl, clientId, secret, apiKey, contextsText)) },
            ) { Text("Save & sync") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
