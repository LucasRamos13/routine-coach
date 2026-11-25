package dev.routinecoach.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.routinecoach.app.container
import dev.routinecoach.app.data.AppSettings
import dev.routinecoach.app.reminders.Relay
import dev.routinecoach.app.ui.BackBar
import dev.routinecoach.app.ui.DAY_FMT
import dev.routinecoach.app.ui.SectionTitle
import dev.routinecoach.app.ui.Stepper
import dev.routinecoach.core.engine.TimeWindow
import dev.routinecoach.core.engine.Weeks
import dev.routinecoach.core.pack.WEEKDAY_KEYS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun SettingsScreen(nav: NavController, s: AppSettings) {
    val ctx = LocalContext.current
    val c = ctx.container
    val ready = c.packs.current() ?: return
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    fun update(f: (AppSettings) -> AppSettings, resync: Boolean = false) = scope.launch {
        c.settings.update(f)
        if (resync) c.reminders.sync()
    }

    // Data export / backup launchers.
    var backupPassword by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var askPassword by remember { mutableStateOf<String?>(null) } // "backup" or "restore"
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { c.backup.exportJson(it) } }; "JSON exported." }
                .getOrElse { "Export failed: ${it.message}" }
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { c.backup.exportCsv(it) } }; "CSV exported." }
                .getOrElse { "Export failed: ${it.message}" }
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = backupPassword
        backupPassword = null
        if (uri == null || pw == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { c.backup.exportEncrypted(it, pw.toCharArray()) } }
                "Encrypted backup saved. Keep the password: it cannot be recovered."
            }.getOrElse { "Backup failed: ${it.message}" }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; askPassword = "restore" }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }

        item { SectionTitle("Plan") }
        item {
            Text("${ready.pack.manifest.name}${if (ready.pack.manifest.description.isNotBlank()) " — " + ready.pack.manifest.description else ""}")
            if (ready.pack.warnings.isNotEmpty()) Text("${ready.pack.warnings.size} content warning(s), e.g. illustrations awaiting posture review.", style = MaterialTheme.typography.bodySmall)
            val start = s.planStart ?: Weeks.weekStart(LocalDate.now())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan started ${start.format(DAY_FMT)}", Modifier.weight(1f))
                TextButton(onClick = { update({ it.copy(planStart = start.minusWeeks(1)) }) }) { Text("−1 wk") }
                TextButton(onClick = { update({ it.copy(planStart = start.plusWeeks(1)) }) }) { Text("+1 wk") }
            }
        }

        item { SectionTitle("Automation") }
        item {
            SwitchRow("Pause all automation", s.paused) { v -> update({ it.copy(paused = v) }) }
            TextButton(onClick = { nav.navigate("automation") }) { Text("Plan changes history and undo") }
        }

        item { SectionTitle("When you can train") }
        item { TimeWindowsEditor(s.timeWindows ?: ready.pack.plan.timeWindows) { w -> update({ it.copy(timeWindows = w) }, resync = true) } }

        item { SectionTitle("Reminders") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Stepper("Minutes before", s.reminderLeadMin, { v -> update({ it.copy(reminderLeadMin = v) }, true) }, step = 5, max = 180)
                    Stepper("Max per day", s.dailyReminderLimit, { v -> update({ it.copy(dailyReminderLimit = v) }, true) }, min = 1, max = 10)
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Stepper("Quiet from", s.quietStart.hour, { v -> update({ it.copy(quietStart = LocalTime.of(v, 0)) }, true) }, max = 23, suffix = "h")
                    Stepper("Quiet until", s.quietEnd.hour, { v -> update({ it.copy(quietEnd = LocalTime.of(v, 0)) }, true) }, max = 23, suffix = "h")
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Stepper("Snooze", s.snoozeMin, { v -> update({ it.copy(snoozeMin = v) }) }, step = 5, min = 5, max = 120, suffix = "m")
                    Stepper("Escalate after", s.escalateAfterMin, { v -> update({ it.copy(escalateAfterMin = v) }) }, step = 5, min = 5, max = 120, suffix = "m")
                }
            }
        }

        item { SectionTitle("Bot and PC relay (ntfy)") }
        item {
            var server by remember { mutableStateOf(s.relayServer) }
            var topic by remember { mutableStateOf(s.relayTopic) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("If a phone reminder is not acknowledged in time, it is relayed to this ntfy topic. The PC notifier and the Telegram bridge " +
                    "listen to it. Use a long random topic name: anyone who knows it can read the reminders (they contain only a time).",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(server, { server = it.trim() }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(topic, { topic = it.trim() }, label = { Text("Topic (empty = off)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { update({ it.copy(relayServer = server, relayTopic = topic) }) }) { Text("Save") }
                    OutlinedButton(enabled = topic.isNotBlank(), onClick = {
                        scope.launch {
                            message = runCatching { withContext(Dispatchers.IO) { Relay(server, topic).publishReminder(0, "now (test)") }; "Test reminder sent." }
                                .getOrElse { "Relay test failed: ${it.message}" }
                        }
                    }) { Text("Send test") }
                }
            }
        }

        item { SectionTitle("Permissions") }
        item {
            val pm = ctx.getSystemService(PowerManager::class.java)
            Column {
                Text(if (c.notifier.canNotify()) "Notifications: allowed" else "Notifications: blocked")
                if (!c.notifier.canNotify() && Build.VERSION.SDK_INT >= 33) {
                    TextButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                }
                Text(if (c.reminders.exactAlarmsAllowed()) "Exact reminders: allowed" else "Exact reminders: not allowed, reminders may be late")
                if (!c.reminders.exactAlarmsAllowed() && Build.VERSION.SDK_INT >= 31) {
                    TextButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }) { Text("Allow exact alarms") }
                }
                val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                Text(if (exempt) "Battery optimization: exempt" else "Battery optimization: active (reminders and timers may be delayed)")
                if (!exempt) TextButton(onClick = {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Exempt from battery optimization") }
            }
        }

        item { SectionTitle("Accessibility") }
        item {
            Text("Text size: ${(s.fontScale * 100).toInt()}% (on top of the system size)")
            Slider(value = s.fontScale, onValueChange = { v -> update({ it.copy(fontScale = (v * 10).toInt() / 10f) }) }, valueRange = 0.9f..1.6f, steps = 6)
            SwitchRow("High contrast", s.highContrast) { v -> update({ it.copy(highContrast = v) }) }
        }

        item { SectionTitle("Your data") }
        item {
            Text("Everything is stored on this device only.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportCsv.launch("routine-coach-${LocalDate.now()}.zip") }) { Text("Export CSV") }
                OutlinedButton(onClick = { exportJson.launch("routine-coach-${LocalDate.now()}.json") }) { Text("Export JSON") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { askPassword = "backup" }) { Text("Encrypted backup") }
                OutlinedButton(onClick = { pickRestore.launch(arrayOf("*/*")) }) { Text("Restore backup") }
            }
        }
        item {
            SectionTitle("About")
            Text("Routine Coach does not replace advice from a doctor or a qualified trainer. Stop on sharp pain; chest pain or dizziness: stop and seek help.",
                style = MaterialTheme.typography.bodySmall)
            Text("Open source under the MIT license.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    askPassword?.let { mode ->
        PasswordDialog(if (mode == "backup") "Backup password" else "Backup password", confirm = mode == "backup", onDismiss = { askPassword = null }) { pw ->
            askPassword = null
            if (mode == "backup") {
                backupPassword = pw
                exportBackup.launch("routine-coach-${LocalDate.now()}.rcbackup")
            } else {
                val uri = restoreUri ?: return@PasswordDialog
                scope.launch {
                    message = runCatching {
                        withContext(Dispatchers.IO) {
                            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                            c.backup.restoreEncrypted(bytes, pw.toCharArray())
                        }
                        c.repo.ensureWeeks(); c.reminders.sync()
                        "Backup restored."
                    }.getOrElse { "Restore failed: ${it.message}" }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TimeWindowsEditor(current: Map<String, List<String>>, onSave: (Map<String, List<String>>) -> Unit) {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val texts = remember(current) { WEEKDAY_KEYS.associateWith { mutableStateOf(current[it].orEmpty().joinToString(", ")) } }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Comma-separated windows like 07:00-08:00, 18:00-21:00. Empty means no training that day.", style = MaterialTheme.typography.bodySmall)
        WEEKDAY_KEYS.forEachIndexed { i, k ->
            var t by texts.getValue(k)
            OutlinedTextField(t, { t = it }, label = { Text(names[i]) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = {
            val parsed = WEEKDAY_KEYS.associateWith { k -> texts.getValue(k).value.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
            val bad = parsed.entries.firstOrNull { (_, l) -> l.any { TimeWindow.parse(it) == null } }
            if (bad != null) error = "Invalid window on ${names[WEEKDAY_KEYS.indexOf(bad.key)]}" else { error = null; onSave(parsed) }
        }) { Text("Save time windows") }
    }
}

@Composable
private fun PasswordDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (confirm) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (confirm && pw.isNotEmpty() && pw.length < 8) Text("Use at least 8 characters.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && (!confirm || (pw == pw2 && pw.length >= 8)), onClick = { onOk(pw) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AutomationHistoryScreen(nav: NavController) {
    val c = LocalContext.current.container
    val changes by remember { c.db.states().observeChanges() }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        BackBar(nav, "Plan changes")
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Every automatic change, with the reason and the data used. Changes can be undone for 24 hours.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (changes.isEmpty()) item { Text("No automatic changes yet.") }
            items(changes, key = { it.id }) { ChangeCard(it, compact = true) }
            item { HorizontalDivider() }
        }
    }
}
