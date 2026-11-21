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
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DismissValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import dev.ruimdetijd.aircav.ui.theme.AirCavTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
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
        enableEdgeToEdge()
        setContent {
            AirCavTheme {
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirCavApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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
    var recSeconds by remember { mutableStateOf(0) }
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

    var syncJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun doSync(manual: Boolean) {
        isSyncing = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repo.ensureOpen()
                repo.sync()
                repo.list() // one list, reused for the UI update
            }
            withContext(Dispatchers.Main) {
                isSyncing = false
                result.onSuccess {
                    tasks = it.tasks
                    contexts = it.contexts
                    if (manual) Toast.makeText(activity, "Synced", Toast.LENGTH_SHORT).show()
                }
                result.onFailure { e ->
                    Log.e(TAG, "sync failed", e)
                    if (manual) Toast.makeText(activity, e.message ?: "sync failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var lastAction by remember { mutableStateOf("") }

    // snackbar with Undo: Taskchampion wrote an UndoPoint before the change,
    // so a single undo() call reverses it
    fun reportChange(message: String) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                lastAction = ""
                scope.launch(Dispatchers.IO) {
                    val undone = runCatching { repo.ensureOpen(); repo.undo() }
                    withContext(Dispatchers.Main) {
                        undone.onFailure { e ->
                            Log.e(TAG, "undo failed", e)
                            Toast.makeText(activity, e.message ?: "undo failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    pushAfterChange()
                }
            }
        }
    }

    // refresh the UI right away (local), debounce the network sync (2 s of quiet)
    fun pushAfterChange() {
        refresh()
        if (settingsStore.load().syncUrl.isBlank()) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(2000)
            doSync(manual = false)
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
        doSync(manual = true)
    }

    fun submitParsed(description: String, tags: List<String>, project: String) {
        busy = true
        scope.launch(Dispatchers.IO) {
            // selected context applies to new tasks (taskwarrior behaviour)
            val projectToUse =
                if (project.isEmpty()) {
                    contextDefs.firstOrNull { it.name == selectedContext }?.project ?: ""
                } else {
                    project
                }
            val result = runCatching {
                repo.ensureOpen()
                repo.add(description, tags, projectToUse)
                "ok"
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) inputText = ""
                busy = false
                if (result.isSuccess) {
                    reportChange("Added: $description" + if (projectToUse.isNotEmpty()) " #$projectToUse" else "")
                    pushAfterChange()
                } else {
                    result.onFailure { e ->
                        Log.e(TAG, "add failed", e)
                        Toast.makeText(activity, e.message ?: "add failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    fun mistralVoice(apiKey: String, wav: java.io.File) {
        voiceProcessing = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val transcript = MistralApi.transcribe(apiKey, wav)
                transcript
            }
            recorder.discard()
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
                    handleInput(text, true)
                }
            }
        }
    }
    fun complete(task: TaskItem) {
        lastAction = "done"
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.ensureOpen()
                repo.complete(task.uuid)
            }
            withContext(Dispatchers.Main) {
                reportChange("Done: ${task.description}")
                pushAfterChange()
            }
        }
    }

    fun remove(task: TaskItem) {
        lastAction = "delete"
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.ensureOpen()
                repo.remove(task.uuid)
            }
            withContext(Dispatchers.Main) {
                reportChange("Deleted: ${task.description}")
                pushAfterChange()
            }
        }
    }

    fun handleInput(text: String, isVoice: Boolean) {
        when (val cmd = parse(text)) {
            is Command.Add -> {
                if (isVoice) {
                    VoiceLog.append(activity) {
                        put("description", cmd.description)
                        put("tags", org.json.JSONArray(cmd.tags))
                        if (cmd.project.isNotEmpty()) put("project", cmd.project)
                    }
                }
                submitParsed(cmd.description, cmd.tags, cmd.project)
            }
            is Command.Act -> {
                val target = tasks.firstOrNull { it.id == cmd.number }
                if (isVoice) {
                    VoiceLog.append(activity) {
                        put("command", cmd.action)
                        put("number", cmd.number)
                        if (target != null) put("target", target.description)
                    }
                }
                if (target == null) {
                    Toast.makeText(activity, "No task #${cmd.number}", Toast.LENGTH_SHORT).show()
                } else if (cmd.action == "delete") {
                    lastAction = "delete"
                    pendingDelete = target
                } else {
                    complete(target)
                }
            }
            null -> {
                if (isVoice) {
                    VoiceLog.append(activity) {
                        put("note", "could not be parsed into a task")
                    }
                }
                Toast.makeText(activity, "Could not understand", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun submit(text: String) = handleInput(text, false)

    LaunchedEffect(Unit) { refresh() }

    // keep the list fresh: sync whenever the app (re)gains focus
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (settingsStore.load().syncUrl.isNotBlank()) {
            doSync(manual = false)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                FloatingActionButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            activity, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@FloatingActionButton
                        }
                        val apiKey = settingsStore.load().mistralApiKey
                        if (!MistralApi.hasKey(apiKey)) {
                            Toast.makeText(activity, "Set a Mistral API key in settings", Toast.LENGTH_SHORT).show()
                            showSettings = true
                            return@FloatingActionButton
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
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor =
                        if (recorder.isRecording) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                ) {
                    if (voiceProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (recorder.isRecording) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop recording")
                    } else {
                        Icon(Icons.Filled.Mic, contentDescription = "Add task by voice")
                    }
                }
                if (busy && tasks.isEmpty()) {
                    CircularProgressIndicator()
                } else {
                    val selectedChip = chips.firstOrNull { it.name == selectedContext }
                    fun matches(t: TaskItem): Boolean {
                        val chip = selectedChip ?: return true
                        return if (chip.project != null) {
                            t.project?.equals(chip.project, ignoreCase = true) == true
                        } else {
                            chip.name in t.tags
                        }
                    }
                    val visible = tasks
                        .filter { matches(it) }
                        .sortedByDescending { it.entry ?: "" }
                    if (visible.isEmpty()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (selectedContext == null) {
                                    "No tasks yet.\nType below or tap the mic to add one."
                                } else {
                                    "Nothing in ${selectedContext}"
                                },
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (selectedContext != null) {
                                TextButton(onClick = { selectContext(null) }) {
                                    Text("show all")
                                }
                            }
                        }
                    }
                    LazyColumn {
                        items(visible.distinctBy { it.uuid }, key = { it.uuid }) { task ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        DismissValue.DismissedToEnd -> {
                                            complete(task) // swipe right
                                            true
                                        }
                                        DismissValue.DismissedToStart -> {
                                            remove(task) // swipe left
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = true,
                                enableDismissFromEndToStart = true,
                                backgroundContent = {
                                    // simple direction indicator
                                    Row(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Done, contentDescription = "Done")
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
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
                                }
                            }
                        }
                    }
                }
            }

            // active context applies to new tasks (taskwarrior-style)
            val activeCtx = chips.firstOrNull { it.name == selectedContext }?.project

            if (activeCtx != null) {
                AssistChip(
                    onClick = { selectContext(null) },
                    label = { Text("-> $selectedContext (x)") },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            // recording timer (max 60 s per the recorder)
            if (voiceInput) {
                LaunchedEffect(Unit) {
                    recSeconds = 0
                    while (voiceInput) {
                        delay(1000)
                        recSeconds += 1
                    }
                }
                Text(
                    "rec ${recSeconds}s/60",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
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
                    placeholder = {
                        Text(
                            if (activeCtx != null) {
                                "-> $selectedContext: buy milk"
                            } else {
                                "buy milk +home"
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (inputText.isNotBlank()) submit(inputText) },
                    ),
                )
                IconButton(
                    onClick = { if (inputText.isNotBlank()) submit(inputText) },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add task")
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
                        remove(target)
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

    val window = (LocalContext.current as? ComponentActivity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    var showSecret by remember { mutableStateOf(false) }
    val secretVisual = if (showSecret) VisualTransformation.None else PasswordVisualTransformation()
    val secretKeyboard = KeyboardOptions(
        keyboardType = KeyboardType.Password,
        autoCorrect = false,
    )

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
                    visualTransformation = secretVisual,
                    keyboardOptions = secretKeyboard,
                    trailingIcon = {
                        TextButton(onClick = { showSecret = !showSecret }) {
                            Text(if (showSecret) "hide" else "show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Mistral API key (for voice input)") },
                    singleLine = true,
                    visualTransformation = secretVisual,
                    keyboardOptions = secretKeyboard,
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
            val ctx = LocalContext.current
            TextButton(
                onClick = {
                    val text = runCatching { VoiceLog.file(ctx).readText() }.getOrDefault("(no log yet)")
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Voice log",
                        ),
                    )
                },
            ) { Text("Export voice log") }
        },
    )
}
