package app.eightzip

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startFile = intent?.data?.path?.let(::File)?.takeIf { it.exists() }
        setContent { MaterialTheme { EightZipApp(this, startFile) } }
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EightZipApp(activity: ComponentActivity, startFile: File?) {
    val scope = rememberCoroutineScope()
    val root = remember { Environment.getExternalStorageDirectory() }
    var allowed by remember { mutableStateOf(hasAllFilesAccess()) }
    var dir by remember { mutableStateOf(startFile?.parentFile ?: root) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var viewing by remember { mutableStateOf<Pair<File, List<EntryInfo>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showFormats by remember { mutableStateOf(false) }
    var pwPrompt by remember { mutableStateOf<Pair<List<File>, Boolean>?>(null) } // archives to extract, wrong-password flag

    fun refresh() {
        files = (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    LaunchedEffect(dir, allowed) { if (allowed) { refresh(); selected = emptySet() } }

    fun run(label: String, work: () -> Unit) = scope.launch {
        busy = label
        val err = withContext(Dispatchers.IO) { runCatching(work).exceptionOrNull() }
        busy = null
        message = err?.let { "Failed: ${it.message}" } ?: "$label done"
        refresh()
    }

    fun openArchive(f: File) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { ArchiveEngine.list(f) } }
            .onSuccess { viewing = f to it }
            .onFailure { message = "Cannot open: ${it.message}" }
    }

    fun doExtract(archives: List<File>, password: CharArray?) = scope.launch {
        busy = "Extract"
        val err = withContext(Dispatchers.IO) {
            runCatching {
                archives.forEach { ArchiveEngine.extract(it, File(it.parentFile, it.name.substringBefore('.')), password = password) }
            }.exceptionOrNull()
        }
        busy = null
        when (err) {
            null -> message = "Extract done"
            is PasswordRequiredException -> pwPrompt = archives to false
            is WrongPasswordException -> pwPrompt = archives to true
            else -> message = "Failed: ${err.message}"
        }
        refresh()
    }

    BackHandler(enabled = viewing != null || dir != root) {
        if (viewing != null) viewing = null else dir.parentFile?.let { dir = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewing?.first?.name ?: dir.path.removePrefix(root.path).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (viewing != null) {
                        IconButton(onClick = {
                            doExtract(listOf(viewing!!.first), null)
                        }) { Icon(Icons.Default.Unarchive, "Extract here") }
                    } else if (selected.isNotEmpty()) {
                        IconButton(onClick = { showFormats = true }) { Icon(Icons.Default.Archive, "Add to archive") }
                        IconButton(onClick = {
                            val archives = selected.filter { Format.detect(it) != null }
                            if (archives.isEmpty()) message = "Select an archive to extract" else doExtract(archives, null)
                        }) { Icon(Icons.Default.Unarchive, "Extract") }
                    }
                }
            )
        },
        snackbarHost = {
            message?.let { Snackbar(Modifier.padding(8.dp), action = { TextButton({ message = null }) { Text("OK") } }) { Text(it) } }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                !allowed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("8zip needs access to all files to browse and compress them.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")))
                        } else activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                    }) { Text("Grant access") }
                    TextButton({ allowed = hasAllFilesAccess() }) { Text("I've granted it") }
                }
                viewing != null -> LazyColumn {
                    items(viewing!!.second) { e ->
                        ListItem(
                            leadingContent = { Icon(if (e.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { if (!e.isDirectory) Text(humanSize(e.size)) }
                        )
                    }
                }
                else -> LazyColumn {
                    items(files, key = { it.path }) { f ->
                        val isSel = f in selected
                        ListItem(
                            modifier = Modifier.clickable {
                                when {
                                    selected.isNotEmpty() -> selected = if (isSel) selected - f else selected + f
                                    f.isDirectory -> dir = f
                                    Format.detect(f) != null -> openArchive(f)
                                }
                            },
                            leadingContent = {
                                Checkbox(isSel, { selected = if (isSel) selected - f else selected + f })
                            },
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (f.isDirectory) "Folder" else humanSize(f.length())) },
                            trailingContent = { Icon(if (f.isDirectory) Icons.Default.Folder else if (Format.detect(f) != null) Icons.Default.FolderZip else Icons.AutoMirrored.Filled.InsertDriveFile, null) }
                        )
                    }
                }
            }
            busy?.let {
                Surface(Modifier.align(Alignment.Center), tonalElevation = 8.dp, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("$it…")
                    }
                }
            }
        }
    }

    if (showFormats) {
        var fmt by remember { mutableStateOf(Format.SEVEN_Z) }
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFormats = false },
            title = { Text("Add to archive") },
            text = {
                Column {
                    Format.values().forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { fmt = f }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(fmt == f, { fmt = f }); Text(f.label)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (fmt.supportsPassword) pw else "",
                        onValueChange = { pw = it },
                        enabled = fmt.supportsPassword,
                        label = { Text(if (fmt.supportsPassword) "Password (optional)" else "Password not supported for ${fmt.label}") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFormats = false
                    val src = selected.toList()
                    val base = if (src.size == 1) src[0].nameWithoutExtension else dir.name.ifEmpty { "archive" }
                    val dest = File(dir, "$base.${fmt.ext}")
                    val password = if (fmt.supportsPassword && pw.isNotEmpty()) pw.toCharArray() else null
                    run("Compress") { ArchiveEngine.compress(src, dest, fmt, password = password) }
                }) { Text("Compress") }
            },
            dismissButton = { TextButton({ showFormats = false }) { Text("Cancel") } }
        )
    }

    pwPrompt?.let { (archives, wrong) ->
        var pw by remember(archives) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwPrompt = null },
            title = { Text("Password required") },
            text = {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it },
                    label = { Text(if (wrong) "Wrong password, try again" else "Enter password") },
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = pw.isNotEmpty(), onClick = { pwPrompt = null; doExtract(archives, pw.toCharArray()) }) { Text("Extract") }
            },
            dismissButton = { TextButton({ pwPrompt = null }) { Text("Cancel") } }
        )
    }
}
