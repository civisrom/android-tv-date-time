package com.civisrom.tvtimefixer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.terminal.*

interface TerminalActions {
    fun edit(command: String)
    fun run()
    fun stop()
    fun clearOutput()
    fun clearHistory()
    fun importFiles()
    fun exportFile(name: String)
}

internal fun TerminalProblem.labelRes(): Int = when (this) {
    TerminalProblem.EMPTY -> R.string.terminal_empty
    TerminalProblem.TOO_LONG -> R.string.terminal_too_long
    TerminalProblem.INVALID_TEXT -> R.string.terminal_invalid_text
    TerminalProblem.QUOTES -> R.string.terminal_quotes
    TerminalProblem.INTERACTIVE -> R.string.terminal_interactive
    TerminalProblem.HOST_COMMAND -> R.string.terminal_host_command
    TerminalProblem.ARGUMENTS -> R.string.terminal_arguments
    TerminalProblem.FILE -> R.string.terminal_file_error
    TerminalProblem.CONNECTION -> R.string.terminal_connection_error
    TerminalProblem.IO -> R.string.terminal_io_error
    TerminalProblem.INSTALL -> R.string.terminal_install_error
}

@Composable
@android.annotation.SuppressLint("SdCardPath") // Paths belong to the remote device, never to this Android host.
internal fun TerminalScreen(
    mode: DeviceMode,
    state: TerminalState,
    target: String,
    busy: Boolean,
    actions: TerminalActions,
    onBack: () -> Unit,
    fileBusy: Boolean = false,
    fileMessage: Int? = null,
    deviceName: String = "",
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboardLabel = stringResource(R.string.terminal_title)
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var tab by rememberSaveable { mutableStateOf("console") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var copied by remember { mutableStateOf<Int?>(null) }
    var focusEditor by remember { mutableStateOf(false) }
    var filesHelp by rememberSaveable { mutableStateOf(false) }
    var downloadDialog by rememberSaveable { mutableStateOf(false) }
    var remoteFile by rememberSaveable { mutableStateOf("") }
    val editorFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val draggingOutput by listState.interactionSource.collectIsDraggedAsState()
    var editor by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    LaunchedEffect(state.draft) {
        if (editor.text != state.draft) editor = TextFieldValue(state.draft, TextRange(state.draft.length))
    }
    LaunchedEffect(Unit) { if (mode == DeviceMode.TELEVISION) backFocus.requestFocus() }
    LaunchedEffect(state.helpRequest) { if (state.helpRequest > 0) tab = "help" }
    LaunchedEffect(tab, focusEditor) {
        if (tab == "console" && focusEditor) {
            editorFocus.requestFocus()
            focusEditor = false
        }
    }
    LaunchedEffect(tab) { listState.scrollToItem(0); copied = null }
    LaunchedEffect(draggingOutput) { if (draggingOutput && tab == "console") follow = false }
    LaunchedEffect(state.output, tab, follow) {
        if (tab == "console" && follow && state.output.isNotEmpty()) {
            listState.scrollToItem(state.output.size)
            // A single block can be taller than the panel: follow its last line, not its first.
            listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == state.output.size }?.let { last ->
                val overflow = last.offset + last.size - listState.layoutInfo.viewportEndOffset
                if (overflow > 0) listState.scrollBy(overflow.toFloat())
            }
        }
    }
    fun insert(command: String) {
        actions.edit(command); follow = false; tab = "console"; focusEditor = true; keyboard?.hide()
    }
    fun copy(text: String) {
        copied = if (runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, text))
        }.isSuccess) R.string.terminal_copied else R.string.terminal_copy_failed
    }
    if (downloadDialog) {
        AlertDialog(onDismissRequest = { downloadDialog = false },
            title = { Text(stringResource(R.string.terminal_download)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.terminal_download_hint))
                    OutlinedTextField(value = remoteFile, onValueChange = { remoteFile = it },
                        label = { Text(stringResource(R.string.terminal_remote_path)) },
                        placeholder = { Text("/sdcard/Download/document.txt") }, singleLine = true,
                        modifier = Modifier.testTag("terminal-remote-path"))
                }
            }, confirmButton = {
                FilledTonalButton(enabled = !busy && !fileBusy && !state.running &&
                    remoteFile.trim().startsWith('/') && !remoteFile.trim().endsWith('/'),
                    onClick = {
                        downloadDialog = false
                        insert("adb pull " + shellQuote(remoteFile.trim()))
                        focusEditor = false
                        actions.run()
                    }, modifier = Modifier.testTag("terminal-download-confirm")) {
                    Text(stringResource(R.string.terminal_download_start))
                }
            }, dismissButton = {
                TextButton(onClick = { downloadDialog = false }) { Text(stringResource(R.string.terminal_warning_cancel)) }
            })
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(
        horizontal = if (mode == DeviceMode.TELEVISION) 48.dp else 16.dp,
        vertical = if (keyboardVisible) 4.dp else if (mode == DeviceMode.TELEVISION) 27.dp else 12.dp)
        .testTag("terminal-screen").onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyUp && it.isCtrlPressed) {
                when {
                    it.key == Key.Enter && !busy && !fileBusy && !state.running -> { actions.run(); true }
                    it.key == Key.C && state.running -> { actions.stop(); true }
                    else -> false
                }
            } else false
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val backButton: @Composable () -> Unit = {
            FilledTonalButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)
                .focusProperties { canFocus = true }.testTag("terminal-back"), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.terminal_back))
            }
        }
        val connectionLabel = stringResource(if (target.isNotBlank()) R.string.terminal_connected else R.string.terminal_not_connected)
        val connectionColor = if (target.isBlank()) MaterialTheme.colorScheme.error
            else if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF86EFAC) else Color(0xFF166534)
        if (keyboardVisible) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            backButton()
            Text(connectionLabel + if (target.isNotBlank()) " · $target" else "", color = connectionColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).testTag("terminal-connection-status"))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                backButton()
                Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.headlineSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(connectionLabel, color = connectionColor, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("terminal-connection-status"))
                Text(if (target.isBlank()) stringResource(R.string.terminal_disconnected) else stringResource(R.string.terminal_target, target),
                    style = MaterialTheme.typography.bodySmall)
                if (target.isNotBlank()) Text(
                    stringResource(R.string.terminal_device_name, deviceName.ifBlank { stringResource(R.string.terminal_device_unknown) }),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("terminal-device-name"))
            }
        }
        if (tab == "console") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = editor, onValueChange = { editor = it; actions.edit(it.text) },
                    label = { Text(stringResource(R.string.terminal_draft)) },
                    placeholder = { Text("adb shell getprop ro.product.model") },
                    minLines = 1, maxLines = if (keyboardVisible) 2 else 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { editor = TextFieldValue(""); actions.edit(""); editorFocus.requestFocus() },
                            enabled = state.draft.isNotEmpty(), modifier = Modifier.testTag("terminal-clear-input")) {
                            Icon(painterResource(R.drawable.ic_terminal_clear), stringResource(R.string.terminal_clear_input))
                        }
                    }, modifier = Modifier.weight(1f).focusRequester(editorFocus).testTag("terminal-input"))
                if (state.running) TerminalButton(R.string.terminal_stop, "terminal-stop", onClick = actions::stop)
                else TerminalButton(R.string.terminal_run, "terminal-run", enabled = !busy && !fileBusy && state.draft.isNotBlank()) {
                    keyboard?.hide(); actions.run()
                }
            }
        }
        val tabs: @Composable () -> Unit = {
            listOf("console" to R.string.terminal_console, "help" to R.string.terminal_help,
                "history" to R.string.terminal_history, "files" to R.string.terminal_files).forEach { (key, label) ->
                FilterChip(selected = tab == key, onClick = { tab = key; keyboard?.hide() },
                    label = { Text(stringResource(label)) }, modifier = Modifier.focusProperties { canFocus = true }
                        .testTag("terminal-tab-$key"))
            }
        }
        // Give the fixed editor room when a TV or landscape keyboard occupies most of the screen.
        if (keyboardVisible && tab != "console") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) { tabs() }
        else if (!keyboardVisible) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tabs() }
        if (state.running && tab != "console") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton(R.string.terminal_stop, "terminal-stop", onClick = actions::stop)
            }
        }
        copied?.let { Text(stringResource(it), modifier = Modifier.testTag("terminal-copy-result")) }
        if (tab == "console") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !state.running && (state.output.isNotEmpty() || state.command.isNotBlank()),
                    onClick = actions::clearOutput, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("terminal-clear")) { Text(stringResource(R.string.terminal_clear)) }
                FilterChip(selected = follow, onClick = { follow = !follow },
                    label = { Text(stringResource(R.string.terminal_follow)) }, modifier = Modifier.testTag("terminal-follow")
                        .semantics { stateDescription = resources.getString(R.string.terminal_follow_hint) })
                IconButton(onClick = { copy(state.output.joinToString("") { it.text }) }, enabled = state.output.isNotEmpty(),
                    modifier = Modifier.testTag("terminal-copy-output")) {
                    Icon(painterResource(R.drawable.ic_terminal_copy), stringResource(R.string.terminal_copy_output))
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal-list")
            .then(if (tab == "console") Modifier.clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
            contentPadding = if (tab == "console") PaddingValues(8.dp) else PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(if (tab in listOf("history", "help")) 0.dp else 8.dp)) {
            when (tab) {
                "console" -> {
                    item("status") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.command.isNotBlank()) {
                                Text(stringResource(R.string.terminal_target, state.target), style = MaterialTheme.typography.labelMedium)
                                if (state.targetName.isNotBlank()) Text(stringResource(R.string.terminal_device_name, state.targetName),
                                    style = MaterialTheme.typography.bodySmall)
                                SelectionContainer { Text("$ " + state.command, fontFamily = FontFamily.Monospace) }
                            }
                            val status = when (state.status) {
                                TerminalStatus.READY -> stringResource(R.string.terminal_ready)
                                TerminalStatus.RUNNING -> stringResource(R.string.terminal_running)
                                TerminalStatus.COMPLETE -> state.exitCode?.let { stringResource(R.string.terminal_complete, it) }
                                    ?: stringResource(R.string.terminal_no_exit)
                                TerminalStatus.CANCELLED -> stringResource(R.string.terminal_cancelled)
                                TerminalStatus.TIMEOUT -> stringResource(R.string.terminal_timeout)
                                TerminalStatus.FAILED -> stringResource((state.problem ?: TerminalProblem.IO).labelRes())
                            }
                            Text(status, modifier = Modifier.testTag("terminal-status"), color =
                                if (state.status == TerminalStatus.FAILED || (state.exitCode ?: 0) != 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.status != TerminalStatus.FAILED) state.problem?.let {
                                Text(stringResource(it.labelRes()), color = MaterialTheme.colorScheme.error)
                            }
                            state.transfer?.let { transfer ->
                                if (state.status == TerminalStatus.COMPLETE && state.exitCode == 0) Text(
                                    stringResource(if (transfer.downloading) R.string.terminal_pull_complete else R.string.terminal_push_complete),
                                    modifier = Modifier.testTag("terminal-transfer-result"))
                                SelectionContainer {
                                    Text(stringResource(R.string.terminal_remote_file, transfer.remotePath),
                                        modifier = Modifier.testTag("terminal-transfer-path"))
                                }
                                Text(stringResource(R.string.terminal_local_file, transfer.localName))
                            }
                            state.transferred?.let { Text(if (it < 1024) stringResource(R.string.terminal_progress_bytes, it)
                                else stringResource(R.string.terminal_progress, android.text.format.Formatter.formatShortFileSize(context, it), it),
                                modifier = Modifier.testTag("terminal-transfer-size")) }
                            if (state.truncated) Text(stringResource(R.string.terminal_truncated))
                        }
                    }
                    items(state.output.size) { index ->
                        val chunk = state.output[index]
                        var focused by remember { mutableStateOf(false) }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                            modifier = Modifier.fillMaxWidth().testTag("terminal-block-$index")
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) follow = false }
                                .focusProperties { canFocus = mode == DeviceMode.TELEVISION }.focusable()
                                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)) {
                            SelectionContainer {
                                Text(chunk.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    color = if (chunk.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(8.dp).testTag("terminal-output-$index"))
                            }
                        }
                    }
                }
                "help" -> {
                    item { Text(stringResource(R.string.terminal_help_intro)) }
                    item {
                        OutlinedTextField(value = query, onValueChange = { query = it; category = null },
                            label = { Text(stringResource(R.string.terminal_search)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth().testTag("terminal-search"))
                    }
                    val visible = terminalCatalog.map { group ->
                        group to group.examples.filter { example ->
                            val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
                            val searchable = example.command + " " + resources.getString(example.titleRes) + " " + resources.getString(group.titleRes)
                            terms.all { searchable.contains(it, ignoreCase = true) }
                        }
                    }.filter { it.second.isNotEmpty() }
                    if (visible.isEmpty()) item { Text(stringResource(R.string.terminal_no_matches)) }
                    visible.forEach { (group, examples) ->
                        item("category-${group.id}") {
                            val expanded = category == group.id
                            val description = stringResource(if (expanded) R.string.section_expanded else R.string.section_collapsed)
                            TextButton(onClick = { category = if (expanded) null else group.id; keyboard?.hide() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
                                    .focusProperties { canFocus = true }.testTag("terminal-category-${group.id}")
                                    .semantics { stateDescription = description }) {
                                Text((if (expanded) "− " else "+ ") + stringResource(group.titleRes) + " (${examples.size})",
                                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            }
                        }
                        if (category == group.id) items(examples, key = { it.id }) { example ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("terminal-example-${example.id}")) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(example.titleRes), style = MaterialTheme.typography.bodyMedium)
                                    SelectionContainer { Text(example.command, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary) }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TerminalButton(R.string.terminal_insert, "terminal-insert-${example.id}") { insert(example.command) }
                                        TerminalButton(R.string.terminal_copy, "terminal-copy-${example.id}") { copy(example.command) }
                                    }
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.terminal_limits), style = MaterialTheme.typography.bodySmall) }
                }
                "history" -> {
                    item {
                        TerminalButton(R.string.terminal_history_clear, "terminal-history-clear", enabled = state.history.isNotEmpty(), onClick = actions::clearHistory)
                        if (state.history.isEmpty()) Text(stringResource(R.string.terminal_history_empty))
                    }
                    items(state.history, key = { it }) { command ->
                        TextButton(onClick = { insert(command) }, shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = true }.testTag("terminal-history-$command")) {
                            Text(command, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                "files" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.terminal_files_hint))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TerminalButton(R.string.terminal_import, "terminal-import", enabled = !fileBusy && !state.running && !busy, onClick = actions::importFiles)
                                TerminalButton(R.string.terminal_download, "terminal-download", enabled = !fileBusy && !state.running && !busy && target.isNotBlank()) { downloadDialog = true }
                            }
                            TextButton(onClick = { filesHelp = !filesHelp }, modifier = Modifier.testTag("terminal-files-help")) {
                                Text((if (filesHelp) "− " else "+ ") + stringResource(R.string.terminal_files_help_title))
                            }
                            if (filesHelp) Text(stringResource(R.string.terminal_files_help), style = MaterialTheme.typography.bodySmall)
                            if (fileBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.terminal_file_busy)) }
                            fileMessage?.let { Text(stringResource(it)) }
                            if (state.files.isEmpty()) Text(stringResource(R.string.terminal_files_empty))
                        }
                    }
                    items(state.files, key = { it }) { name ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(name, fontFamily = FontFamily.Monospace)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (name.endsWith(".apk", true)) TerminalButton(R.string.terminal_install, "terminal-install-$name") {
                                        insert("adb install -r " + shellQuote(name))
                                    }
                                    TerminalButton(R.string.terminal_push, "terminal-push-$name") {
                                        insert("adb push ${shellQuote(name)} ${shellQuote("/sdcard/Download/$name")}")
                                    }
                                    TerminalButton(R.string.terminal_insert_filename, "terminal-file-insert-$name") { insert(state.draft + " " + shellQuote(name)) }
                                    TerminalButton(R.string.terminal_export, "terminal-export-$name", enabled = !fileBusy && !state.running && !busy) { actions.exportFile(name) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalButton(label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.focusProperties { canFocus = true }.testTag(tag)) { Text(stringResource(label)) }
}
