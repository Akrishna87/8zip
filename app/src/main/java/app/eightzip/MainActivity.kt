package app.eightzip

import android.content.Context
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    override fun onStop() {
        super.onStop()
        runCatching { LatestDownloadWidget.refreshAll(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // must come before super.onCreate
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ArchiveEngine.rar5Reader = LibArchiveRar5
        setContent { EightZipTheme { EightZipApp(this, intent) } }
    }
}

/** A pending password prompt: which archives, whether the last try was wrong, and the partial selection. */
data class PwRequest(val archives: List<File>, val wrong: Boolean, val only: List<String>?)

/** A generic "are you sure" dialog. */
data class Confirm(val title: String, val text: String, val yes: String, val onYes: () -> Unit)

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
fun EightZipApp(activity: ComponentActivity, launchIntent: Intent?) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val root = remember { Environment.getExternalStorageDirectory() }

    var allowed by remember { mutableStateOf(hasAllFilesAccess()) }
    var dir by remember { mutableStateOf(root) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var selectMode by remember { mutableStateOf(false) } // explicit "Select" mode, also entered by long-press
    var viewing by remember { mutableStateOf<Pair<File, List<EntryInfo>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyFile by remember { mutableStateOf("") }
    var showCompress by remember { mutableStateOf(false) }
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var deleteAfterExtract by remember { mutableStateOf(prefs.getBoolean("delete_archive_after_extract", false)) }
    var confirmEnableDeleteAfterExtract by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val trash = remember { Trash(File(root, ".8zip-trash")) }
    var trashing by remember { mutableStateOf(false) }              // the Trash screen is open
    var trashItems by remember { mutableStateOf(emptyList<Trash.Item>()) }
    var trashSel by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var incomingMulti by remember { mutableStateOf<List<File>?>(null) } // several archives shared at once
    var pwPrompt by remember { mutableStateOf<PwRequest?>(null) }
    var viewSelected by remember { mutableStateOf(setOf<String>()) } // entry names picked inside the open archive
    var viewSelectMode by remember { mutableStateOf(false) }

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

    fun clearSelection() { selected = emptySet(); selectMode = false }

    // Items older than 30 days are removed for good.
    LaunchedEffect(allowed) {
        if (allowed) withContext(Dispatchers.IO) { runCatching { trash.purgeDeletedBefore(System.currentTimeMillis() - Trash.RETENTION_MS) } }
    }

    val visible = remember(files, sortBy, ascending, showHidden, query) {
        val cmp: Comparator<File> = when (sortBy) {
            SortBy.NAME -> compareBy<File> { it.name.lowercase() }
            SortBy.SIZE -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
            SortBy.DATE -> compareBy<File> { it.lastModified() }
        }
        files.filter { it.name != ".8zip-trash" && (showHidden || !it.name.startsWith(".")) && it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<File> { !it.isDirectory }.then(if (ascending) cmp else cmp.reversed()))
    }

    fun toast(msg: String) { scope.launch { snack.showSnackbar(msg) } }

    fun loadTrash() = scope.launch { trashItems = withContext(Dispatchers.IO) { trash.list() } }
    fun openTrash() { trashing = true; trashSel = emptySet(); loadTrash() }
    fun trashAction(label: String, done: String, work: () -> Unit) = scope.launch {
        busy = label; busyFile = ""
        val err = withContext(Dispatchers.IO) { runCatching(work).exceptionOrNull() }
        busy = null
        trashSel = emptySet()
        trashItems = withContext(Dispatchers.IO) { trash.list() }
        refresh()
        toast(err?.let { "Failed: ${it.message}" } ?: done)
    }

    fun run(label: String, done: String, work: (Progress) -> Unit) = scope.launch {
        busy = label; busyFile = ""
        val err = withContext(Dispatchers.IO) { runCatching { work(Progress { busyFile = it }) }.exceptionOrNull() }
        busy = null
        refresh()
        if (err == null) { clearSelection(); toast(done) } else toast("Failed: ${err.message}")
    }

    fun doExtract(archives: List<File>, password: CharArray?, only: List<String>? = null) = scope.launch {
        busy = "Extracting"; busyFile = ""
        var deleted = 0
        var toDownload = false
        val err = withContext(Dispatchers.IO) {
            runCatching {
                archives.forEach {
                    // An archive that reached us as a content:// link lives in our cache: extract into Download instead.
                    val temp = Incoming.isTemp(activity, it)
                    val parent = if (temp) File(root, "Download").also { d -> d.mkdirs() } else it.parentFile ?: dir
                    if (temp) toDownload = true
                    val out = uniqueFile(parent, archiveBase(it))
                    ArchiveEngine.extract(it, out, Progress { n -> busyFile = n }, password, only)
                    if (only == null) {
                        prefs.edit().putString("last_extracted", "${it.path}|${it.lastModified()}").apply()
                        if (temp) it.delete()                                          // our own copy: just tidy up
                        else if (deleteAfterExtract) { trash.moveToTrash(it); deleted++ } // the user's file: to the Trash
                    }
                }
            }.exceptionOrNull()
        }
        busy = null
        refresh()
        when (err) {
            null -> {
                clearSelection(); viewSelected = emptySet(); viewSelectMode = false
                if (deleted > 0) viewing = null // the archive being viewed is gone
                val gone = if (deleted > 0) (if (deleted == 1) " and moved the archive to Trash" else " and moved the archives to Trash") else ""
                toast(when {
                    only != null -> "Extracted ${only.size} selected item" + (if (only.size == 1) "" else "s")
                    archives.size == 1 -> "Extracted ${archiveBase(archives[0])}${if (toDownload) " to Download" else ""}$gone"
                    else -> "Extracted ${archives.size} archives${if (toDownload) " to Download" else ""}$gone"
                })
            }
            is PasswordRequiredException -> pwPrompt = PwRequest(archives, false, only)
            is WrongPasswordException -> pwPrompt = PwRequest(archives, true, only)
            else -> toast("Failed: ${err.message}")
        }
    }

    fun openArchive(f: File) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { ArchiveEngine.list(f) } }
            .onSuccess { viewing = f to it; viewSelected = emptySet(); viewSelectMode = false }
            .onFailure { toast("Can't open: ${it.message}") }
    }

    // "Open with 8zip" / "Share to 8zip": turn the intent into archive files and show them.
    LaunchedEffect(Unit) {
        val inc = launchIntent
        // Long-press shortcuts on the app icon.
        when (inc?.action) {
            "app.eightzip.action.DOWNLOADS" -> { dir = File(root, "Download").takeIf { it.isDirectory } ?: root; return@LaunchedEffect }
            "app.eightzip.action.TRASH" -> { openTrash(); return@LaunchedEffect }
            "app.eightzip.action.SELECT" -> { selectMode = true; return@LaunchedEffect }
            LatestDownloadWidget.ACTION_EXTRACT_LATEST, LatestDownloadWidget.ACTION_OPEN_LATEST -> {
                val f = inc?.getStringExtra("path")?.let(::File)
                dir = File(root, "Download").takeIf { it.isDirectory } ?: root
                when {
                    f == null || !f.isFile -> toast("That archive is no longer in Downloads")
                    !hasAllFilesAccess() -> Unit // the permission screen is showing; tap the widget again after allowing
                    inc.action == LatestDownloadWidget.ACTION_EXTRACT_LATEST -> doExtract(listOf(f), null)
                    else -> openArchive(f)
                }
                return@LaunchedEffect
            }
        }
        if (!Incoming.isIncoming(inc)) return@LaunchedEffect
        busy = "Opening"; busyFile = ""
        val files = withContext(Dispatchers.IO) { Incoming.resolve(activity, inc!!, root) }
        busy = null
        val archives = files.filter { Format.detect(it) != null }
        when {
            files.isEmpty() -> toast("Couldn't open that file")
            archives.isEmpty() -> toast("That isn't a supported archive")
            archives.size == 1 -> {
                val f = archives[0]
                if (!Incoming.isTemp(activity, f)) f.parentFile?.let { dir = it }
                openArchive(f)
            }
            else -> incomingMulti = archives
        }
    }

    val inSelection = selectMode || selected.isNotEmpty()
    BackHandler(enabled = trashing || viewing != null || inSelection || searching || dir != root) {
        when {
            trashing -> if (trashSel.isNotEmpty()) trashSel = emptySet() else trashing = false
            viewing != null -> if (viewSelectMode || viewSelected.isNotEmpty()) { viewSelected = emptySet(); viewSelectMode = false } else viewing = null
            inSelection -> clearSelection()
            searching -> { searching = false; query = "" }
            else -> dir.parentFile?.let { dir = it }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            val v = viewing
            when {
                trashing && trashSel.isNotEmpty() -> TopAppBar(
                    navigationIcon = { IconButton({ trashSel = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") } },
                    title = { Text("${trashSel.size} selected") },
                    actions = {
                        val all = trashItems.isNotEmpty() && trashSel.size == trashItems.size
                        IconButton({ trashSel = if (all) emptySet() else trashItems.map { it.id }.toSet() }) {
                            Icon(if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll, if (all) "Select none" else "Select all")
                        }
                        IconButton({
                            val ids = trashSel.toList()
                            trashAction("Restoring", if (ids.size == 1) "Restored 1 item" else "Restored ${ids.size} items") { ids.forEach { trash.restore(it) } }
                        }) { Icon(Icons.Filled.RestoreFromTrash, "Restore") }
                        IconButton({
                            val ids = trashSel.toList()
                            confirm = Confirm(
                                "Delete ${if (ids.size == 1) "this item" else "${ids.size} items"} forever?",
                                "This can't be undone.", "Delete forever"
                            ) { trashAction("Deleting", "Deleted forever") { ids.forEach { trash.deletePermanently(it) } } }
                        }) { Icon(Icons.Filled.DeleteForever, "Delete forever") }
                    }
                )
                trashing -> TopAppBar(
                    navigationIcon = { IconButton({ trashing = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text("Trash") },
                    actions = {
                        IconButton({
                            confirm = Confirm(
                                "Empty the Trash?", "Everything in it will be deleted forever. This can't be undone.", "Empty Trash"
                            ) { trashAction("Emptying", "Trash emptied") { trash.empty() } }
                        }, enabled = trashItems.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, "Empty Trash") }
                    }
                )
                v != null && (viewSelectMode || viewSelected.isNotEmpty()) -> TopAppBar(
                    navigationIcon = { IconButton({ viewSelected = emptySet(); viewSelectMode = false }) { Icon(Icons.Filled.Close, "Exit selection") } },
                    title = { Text(if (viewSelected.isEmpty()) "Select items" else "${viewSelected.size} selected") },
                    actions = {
                        val allNames = v.second.map { it.name }.toSet()
                        val all = allNames.isNotEmpty() && viewSelected.size == allNames.size
                        IconButton({ viewSelected = if (all) emptySet() else allNames }) {
                            Icon(if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll, if (all) "Select none" else "Select all")
                        }
                        IconButton({ doExtract(listOf(v.first), null, viewSelected.toList()) }, enabled = viewSelected.isNotEmpty()) {
                            Icon(Icons.Filled.Unarchive, "Extract selected")
                        }
                    }
                )
                v != null -> TopAppBar(
                    navigationIcon = { IconButton({ viewing = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text(v.first.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton({ viewSelectMode = true }) { Icon(Icons.Filled.Checklist, "Select items to extract") }
                        IconButton({ doExtract(listOf(v.first), null) }) { Icon(Icons.Filled.Unarchive, "Extract all") }
                    }
                )
                inSelection -> TopAppBar(
                    navigationIcon = { IconButton({ clearSelection() }) { Icon(Icons.Filled.Close, "Exit selection") } },
                    title = { Text(if (selected.isEmpty()) "Select items" else "${selected.size} selected") },
                    actions = {
                        val all = visible.isNotEmpty() && selected.size == visible.size
                        IconButton({ selected = if (all) emptySet() else visible.toSet() }) {
                            Icon(if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll, if (all) "Select none" else "Select all")
                        }
                        IconButton({ showCompress = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Archive, "Add to archive") }
                        IconButton({ confirmDelete = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Delete, "Delete") }
                        IconButton({
                            val archives = selected.filter { Format.detect(it) != null }
                            if (archives.isEmpty()) toast("Select an archive to extract") else doExtract(archives, null)
                        }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Unarchive, "Extract") }
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
                        IconButton({ selectMode = true }) { Icon(Icons.Filled.Checklist, "Select multiple") }
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
                                    text = { Text("Trash archive after extracting") },
                                    leadingIcon = { Checkbox(deleteAfterExtract, null) },
                                    onClick = {
                                        sortMenu = false
                                        if (deleteAfterExtract) { deleteAfterExtract = false; prefs.edit().putBoolean("delete_archive_after_extract", false).apply() }
                                        else confirmEnableDeleteAfterExtract = true
                                    }
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

                trashing -> if (trashItems.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Text("Trash is empty", style = MaterialTheme.typography.titleMedium)
                        Text("Deleted items are kept here for 30 days so you can restore them.", color = MaterialTheme.colorScheme.outline)
                    }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(trashItems, key = { it.id }) { t ->
                        val sel = t.id in trashSel
                        val from = File(t.originalPath).parent.orEmpty().removePrefix(root.path).ifEmpty { "/" }
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = { trashSel = if (sel) trashSel - t.id else trashSel + t.id },
                                onLongClick = { trashSel = if (sel) trashSel - t.id else trashSel + t.id }
                            ),
                            colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            leadingContent = {
                                if (trashSel.isNotEmpty()) Checkbox(sel, null)
                                else Icon(iconFor(t.name, t.isDirectory), null, tint = MaterialTheme.colorScheme.primary)
                            },
                            headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${dateFmt.format(t.deletedAt)} · from $from", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Text(humanSize(t.size), style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                v != null -> Column {
                    val folders = v.second.count { it.isDirectory }
                    val total = v.second.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
                    val picking = viewSelectMode || viewSelected.isNotEmpty()
                    ListItem(
                        headlineContent = { Text("${v.second.size} items" + if (folders > 0) " · $folders folders" else "") },
                        supportingContent = {
                            Text(
                                if (picking) "Tap items to choose what to extract (a folder includes everything in it)"
                                else "${humanSize(total)} uncompressed · ${humanSize(v.first.length())} archive"
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(v.second) { e ->
                            val key = e.name
                            val own = key in viewSelected
                            // Shown as ticked when it, or a folder above it, is picked.
                            val inherited = !own && viewSelected.any { sel ->
                                val folder = sel.trimEnd('/')
                                key.trimEnd('/').startsWith("$folder/")
                            }
                            val ticked = own || inherited
                            ListItem(
                                modifier = Modifier.combinedClickable(
                                    onClick = { viewSelectMode = true; viewSelected = if (own) viewSelected - key else viewSelected + key },
                                    onLongClick = { viewSelectMode = true; viewSelected = if (own) viewSelected - key else viewSelected + key }
                                ),
                                colors = ListItemDefaults.colors(containerColor = if (ticked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                                leadingContent = {
                                    if (picking) Checkbox(ticked, null)
                                    else Icon(iconFor(e.name, e.isDirectory), null, tint = MaterialTheme.colorScheme.primary)
                                },
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
                        item {
                            FilterChip(
                                selected = false, onClick = { openTrash() }, label = { Text("Trash") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) }
                            )
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
                                            else -> { selectMode = true; selected = setOf(f) }
                                        }
                                    },
                                    onLongClick = { selectMode = true; selected = if (isSel) selected - f else selected + f }
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
        CompressDialog(defaultName, onDismiss = { showCompress = false }) { name, fmt, password, deleteOriginals, legacyZip ->
            showCompress = false
            val dest = uniqueFile(dir, name.ifBlank { defaultName }, ".${fmt.ext}")
            run("Compressing", "Created ${dest.name}" + if (deleteOriginals) " and moved the originals to Trash" else "") { p ->
                ArchiveEngine.compress(src, dest, fmt, p, password, legacyZip)
                if (deleteOriginals) ArchiveEngine.deleteSourcesAfterCompress(src, dest) { trash.moveToTrash(it) }
            }
        }
    }

    if (confirmEnableDeleteAfterExtract) {
        AlertDialog(
            onDismissRequest = { confirmEnableDeleteAfterExtract = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Trash archives after extracting?") },
            text = { Text("After an archive is fully extracted it is moved to the Trash (restore it from there). Archives that fail to extract, and partial extracts of selected items, are never moved.") },
            confirmButton = {
                TextButton({
                    deleteAfterExtract = true
                    prefs.edit().putBoolean("delete_archive_after_extract", true).apply()
                    confirmEnableDeleteAfterExtract = false
                }) { Text("Turn on", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmEnableDeleteAfterExtract = false }) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        val victims = selected.toList()
        val label = if (victims.size == 1) victims[0].name else "${victims.size} items"
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Delete $label?") },
            text = {
                Column {
                    Text(
                        (if (victims.size > 1) victims.take(3).joinToString("\n") { "• ${it.name}" } +
                            (if (victims.size > 3) "\n• and ${victims.size - 3} more" else "") + "\n\n" else "") +
                            "They'll be moved to the Trash, where you can restore them for 30 days."
                    )
                    TextButton({
                        confirmDelete = false
                        run("Deleting", "Permanently deleted $label") { p -> victims.forEach { p.onFile(it.name); ArchiveEngine.deleteTree(it) } }
                    }) { Text("Delete permanently instead", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    run("Moving to Trash", "Moved $label to Trash") { p -> victims.forEach { p.onFile(it.name); trash.moveToTrash(it) } }
                }) { Text("Move to Trash") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } }
        )
    }

    incomingMulti?.let { list ->
        AlertDialog(
            onDismissRequest = { incomingMulti = null },
            icon = { Icon(Icons.Filled.FolderZip, null) },
            title = { Text("${list.size} archives shared") },
            text = { Text(list.take(5).joinToString("\n") { "• ${it.name}" } + if (list.size > 5) "\n• and ${list.size - 5} more" else "") },
            confirmButton = { TextButton({ incomingMulti = null; doExtract(list, null) }) { Text("Extract all") } },
            dismissButton = { TextButton({ incomingMulti = null }) { Text("Cancel") } }
        )
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { TextButton({ confirm = null; c.onYes() }) { Text(c.yes, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }
        )
    }

    pwPrompt?.let { req ->
        val archives = req.archives
        val wrong = req.wrong
        var pw by remember(archives) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwPrompt = null },
            icon = { Icon(Icons.Filled.Lock, null) },
            title = { Text("Password required") },
            text = {
                PasswordField(pw, { pw = it }, if (wrong) "Wrong password, try again" else "Password", isError = wrong)
            },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { pwPrompt = null; doExtract(archives, pw.toCharArray(), req.only) }) { Text("Extract") } },
            dismissButton = { TextButton({ pwPrompt = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CompressDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (String, Format, CharArray?, Boolean, Boolean) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var fmt by remember { mutableStateOf(Format.ZIP) } // zip opens natively on Windows, macOS, Linux and Android
    var legacyZip by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var deleteOriginals by remember { mutableStateOf(false) }
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
                PasswordField(
                    value = if (fmt.supportsPassword) pw else "", onValueChange = { pw = it },
                    label = if (fmt.supportsPassword) "Password (optional, AES-256)" else "No password support for ${fmt.label}",
                    enabled = fmt.supportsPassword
                )
                val hasPassword = fmt.supportsPassword && pw.isNotEmpty()
                if (fmt == Format.ZIP && hasPassword) {
                    Row(Modifier.fillMaxWidth().clickable { legacyZip = !legacyZip }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(legacyZip, null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Compatible mode (opens in Windows and Mac)")
                            Text("Uses older ZipCrypto encryption, which is weaker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Text(
                    when {
                        fmt == Format.ZIP && !hasPassword -> "Opens everywhere: Windows, macOS, Linux and Android."
                        fmt == Format.ZIP && legacyZip -> "Opens in Windows Explorer and the Mac Archive Utility. Strong tools aren't needed, but this encryption is weak."
                        fmt == Format.ZIP -> "Strong AES-256, but on a laptop you need 7-Zip, WinRAR or Keka (Windows Explorer and the Mac Archive Utility can't open it)."
                        fmt == Format.SEVEN_Z -> "Best compression. On a laptop you need 7-Zip (Windows), Keka (Mac) or p7zip (Linux)."
                        fmt == Format.TAR_GZ -> "Opens on Mac, Linux and Windows 11. Older Windows needs 7-Zip."
                        else -> "Opens on Mac and Linux. Windows needs 7-Zip or Windows 11."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                Row(Modifier.fillMaxWidth().clickable { deleteOriginals = !deleteOriginals }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteOriginals, null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Move originals to Trash afterwards")
                        Text("Only after the archive is checked to contain every file; restore them from Trash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(name.trim(), fmt, if (fmt.supportsPassword && pw.isNotEmpty()) pw.toCharArray() else null, deleteOriginals, legacyZip && fmt == Format.ZIP) }) { Text("Compress") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/** Password input with an eye button that toggles between hidden and visible text. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        enabled = enabled, isError = isError, singleLine = true,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            if (enabled) IconButton({ visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        }
    )
}
