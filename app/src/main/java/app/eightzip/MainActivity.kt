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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startFile = intent?.data?.path?.let(::File)?.takeIf { it.exists() }
        setContent { EightZipTheme { EightZipApp(this, startFile) } }
    }
}

enum class SortBy(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date modified") }

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tgz", ".7z", ".zip", ".tar", ".rar", ".jar", ".apk")

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

private fun archiveBase(f: File): String {
    val n = f.name
    val suffix = ARCHIVE_SUFFIXES.firstOrNull { n.lowercase().endsWith(it) }
    return if (suffix != null) n.dropLast(suffix.length) else n.substringBeforeLast('.')
}

/** A path in [parent] that doesn't exist yet: "name", "name (1)", "name (2)"... */
private fun uniqueFile(parent: File, name: String, ext: String = ""): File {
    var f = File(parent, name + ext)
    var i = 1
    while (f.exists()) { f = File(parent, "$name ($i)$ext"); i++ }
    return f
}

private fun iconFor(name: String, isDir: Boolean): ImageVector {
    if (isDir) return Icons.Filled.Folder
    val lower = name.lowercase()
    if (ARCHIVE_SUFFIXES.any { lower.endsWith(it) && it != ".apk" && it != ".jar" }) return Icons.Filled.FolderZip
    return when (lower.substringAfterLast('.', "")) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Icons.Filled.Image
        "mp4", "mkv", "webm", "3gp", "mov", "avi" -> Icons.Filled.Movie
        "mp3", "m4a", "wav", "ogg", "flac", "aac" -> Icons.Filled.MusicNote
        "apk" -> Icons.Filled.Android
        "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "csv", "json", "xml", "log" -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EightZipApp(activity: ComponentActivity, startFile: File?) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val root = remember { Environment.getExternalStorageDirectory() }

    var allowed by remember { mutableStateOf(hasAllFilesAccess()) }
    var dir by remember { mutableStateOf(startFile?.parentFile ?: root) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var viewing by remember { mutableStateOf<Pair<File, List<EntryInfo>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyFile by remember { mutableStateOf("") }
    var showCompress by remember { mutableStateOf(false) }
    var pwPrompt by remember { mutableStateOf<Pair<List<File>, Boolean>?>(null) } // archives, wrong-password flag

    var sortBy by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    // Re-check the all-files permission whenever we come back from the settings screen.
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) allowed = hasAllFilesAccess() }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    fun refresh() { files = dir.listFiles()?.toList() ?: emptyList() }
    LaunchedEffect(dir, allowed) { if (allowed) { refresh(); selected = emptySet(); query = ""; searching = false } }

    val visible = remember(files, sortBy, ascending, showHidden, query) {
        val cmp: Comparator<File> = when (sortBy) {
            SortBy.NAME -> compareBy<File> { it.name.lowercase() }
            SortBy.SIZE -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
            SortBy.DATE -> compareBy<File> { it.lastModified() }
        }
        files.filter { (showHidden || !it.name.startsWith(".")) && it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<File> { !it.isDirectory }.then(if (ascending) cmp else cmp.reversed()))
    }

    fun toast(msg: String) { scope.launch { snack.showSnackbar(msg) } }

    fun run(label: String, done: String, work: (Progress) -> Unit) = scope.launch {
        busy = label; busyFile = ""
        val err = withContext(Dispatchers.IO) { runCatching { work(Progress { busyFile = it }) }.exceptionOrNull() }
        busy = null
        refresh()
        if (err == null) { selected = emptySet(); toast(done) } else toast("Failed: ${err.message}")
    }

    fun doExtract(archives: List<File>, password: CharArray?) = scope.launch {
        busy = "Extracting"; busyFile = ""
        val err = withContext(Dispatchers.IO) {
            runCatching {
                archives.forEach {
                    val out = uniqueFile(it.parentFile ?: dir, archiveBase(it))
                    ArchiveEngine.extract(it, out, Progress { n -> busyFile = n }, password)
                }
            }.exceptionOrNull()
        }
        busy = null
        refresh()
        when (err) {
            null -> { selected = emptySet(); toast(if (archives.size == 1) "Extracted ${archiveBase(archives[0])}" else "Extracted ${archives.size} archives") }
            is PasswordRequiredException -> pwPrompt = archives to false
            is WrongPasswordException -> pwPrompt = archives to true
            else -> toast("Failed: ${err.message}")
        }
    }

    fun openArchive(f: File) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { ArchiveEngine.list(f) } }
            .onSuccess { viewing = f to it }
            .onFailure { toast("Can't open: ${it.message}") }
    }

    val inSelection = selected.isNotEmpty()
    BackHandler(enabled = viewing != null || inSelection || searching || dir != root) {
        when {
            viewing != null -> viewing = null
            inSelection -> selected = emptySet()
            searching -> { searching = false; query = "" }
            else -> dir.parentFile?.let { dir = it }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            val v = viewing
            when {
                v != null -> TopAppBar(
                    navigationIcon = { IconButton({ viewing = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text(v.first.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = { IconButton({ doExtract(listOf(v.first), null) }) { Icon(Icons.Filled.Unarchive, "Extract") } }
                )
                inSelection -> TopAppBar(
                    navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") } },
                    title = { Text("${selected.size} selected") },
                    actions = {
                        IconButton({ selected = visible.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton({ showCompress = true }) { Icon(Icons.Filled.Archive, "Add to archive") }
                        IconButton({
                            val archives = selected.filter { Format.detect(it) != null }
                            if (archives.isEmpty()) toast("Select an archive to extract") else doExtract(archives, null)
                        }) { Icon(Icons.Filled.Unarchive, "Extract") }
                    }
                )
                searching -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TopAppBar(
                        navigationIcon = { IconButton({ searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                        title = {
                            TextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text("Search in this folder") },
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                        },
                        actions = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } }
                    )
                }
                else -> TopAppBar(
                    title = { Text("8zip") },
                    actions = {
                        IconButton({ searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort and view") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortBy.values().forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label) },
                                        leadingIcon = { RadioButton(sortBy == s, null) },
                                        onClick = { sortBy = s; sortMenu = false }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Ascending") },
                                    leadingIcon = { Checkbox(ascending, null) },
                                    onClick = { ascending = !ascending }
                                )
                                DropdownMenuItem(
                                    text = { Text("Show hidden files") },
                                    leadingIcon = { Checkbox(showHidden, null) },
                                    onClick = { showHidden = !showHidden }
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val v = viewing
            when {
                !allowed -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.FolderZip, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Allow access to files", style = MaterialTheme.typography.titleLarge)
                    Text("8zip needs all-files access to browse, compress and extract your files.")
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")))
                        } else activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                    }) { Text("Open settings") }
                }

                v != null -> Column {
                    val folders = v.second.count { it.isDirectory }
                    val total = v.second.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
                    ListItem(
                        headlineContent = { Text("${v.second.size} items" + if (folders > 0) " · $folders folders" else "") },
                        supportingContent = { Text("${humanSize(total)} uncompressed · ${humanSize(v.first.length())} archive") },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(v.second) { e ->
                            ListItem(
                                leadingContent = { Icon(iconFor(e.name, e.isDirectory), null, tint = MaterialTheme.colorScheme.primary) },
                                headlineContent = { Text(e.name.trimEnd('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { if (!e.isDirectory) Text(humanSize(e.size)) }
                            )
                        }
                    }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    // quick locations
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val places = listOf("Internal storage" to root, "Download" to File(root, "Download"),
                            "Documents" to File(root, "Documents"), "DCIM" to File(root, "DCIM")).filter { it.second.isDirectory }
                        items(places) { (label, f) ->
                            FilterChip(selected = dir == f, onClick = { dir = f }, label = { Text(label) })
                        }
                    }
                    // breadcrumb
                    val crumbs = remember(dir) {
                        generateSequence(dir) { if (it == root) null else it.parentFile }.toList().reversed()
                    }
                    val crumbState = rememberLazyListState()
                    LaunchedEffect(crumbs) { crumbState.scrollToItem((crumbs.size - 1).coerceAtLeast(0)) }
                    LazyRow(state = crumbState, verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        itemsIndexed(crumbs) { i, f ->
                            if (i > 0) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                            TextButton({ dir = f }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(if (f == root) "Internal storage" else f.name.ifEmpty { "/" },
                                    color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider()
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (query.isNotEmpty()) "No matches" else "This folder is empty", color = MaterialTheme.colorScheme.outline)
                        }
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.path }) { f ->
                            val isSel = f in selected
                            ListItem(
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        when {
                                            inSelection -> selected = if (isSel) selected - f else selected + f
                                            f.isDirectory -> dir = f
                                            Format.detect(f) != null && !f.name.endsWith(".apk", true) -> openArchive(f)
                                            else -> toast("Long-press to select this file")
                                        }
                                    },
                                    onLongClick = { selected = if (isSel) selected - f else selected + f }
                                ),
                                colors = ListItemDefaults.colors(containerColor = if (isSel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                                leadingContent = {
                                    if (inSelection) Checkbox(isSel, null)
                                    else Icon(iconFor(f.name, f.isDirectory), null, tint = MaterialTheme.colorScheme.primary)
                                },
                                headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(dateFmt.format(f.lastModified()) + if (f.isDirectory) "" else " · ${humanSize(f.length())}")
                                }
                            )
                        }
                    }
                }
            }

            busy?.let { label ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Card(Modifier.padding(32.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("$label…", style = MaterialTheme.typography.titleMedium)
                            if (busyFile.isNotEmpty()) Text(busyFile, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showCompress) {
        val src = selected.toList()
        val defaultName = if (src.size == 1) (if (src[0].isDirectory) src[0].name else src[0].nameWithoutExtension) else dir.name.ifEmpty { "archive" }
        CompressDialog(defaultName, onDismiss = { showCompress = false }) { name, fmt, password ->
            showCompress = false
            val dest = uniqueFile(dir, name.ifBlank { defaultName }, ".${fmt.ext}")
            run("Compressing", "Created ${dest.name}") { p -> ArchiveEngine.compress(src, dest, fmt, p, password) }
        }
    }

    pwPrompt?.let { (archives, wrong) ->
        var pw by remember(archives) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwPrompt = null },
            icon = { Icon(Icons.Filled.Lock, null) },
            title = { Text("Password required") },
            text = {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, isError = wrong,
                    label = { Text(if (wrong) "Wrong password, try again" else "Password") },
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { pwPrompt = null; doExtract(archives, pw.toCharArray()) }) { Text("Extract") } },
            dismissButton = { TextButton({ pwPrompt = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CompressDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (String, Format, CharArray?) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var fmt by remember { mutableStateOf(Format.SEVEN_Z) }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Archive, null) },
        title = { Text("Add to archive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Archive name") },
                    suffix = { Text(".${fmt.ext}") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Format.values().filter { it.canCreate }.forEach { f ->
                        FilterChip(selected = fmt == f, onClick = { fmt = f }, label = { Text(f.label) })
                    }
                }
                OutlinedTextField(
                    value = if (fmt.supportsPassword) pw else "", onValueChange = { pw = it },
                    enabled = fmt.supportsPassword, singleLine = true,
                    label = { Text(if (fmt.supportsPassword) "Password (optional, AES-256)" else "No password support for ${fmt.label}") },
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton({ onConfirm(name.trim(), fmt, if (fmt.supportsPassword && pw.isNotEmpty()) pw.toCharArray() else null) }) { Text("Compress") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
