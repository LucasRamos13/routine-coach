package dev.routinecoach.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.routinecoach.app.container
import dev.routinecoach.app.data.CalendarEntryEntity
import dev.routinecoach.app.ui.DAY_FMT
import dev.routinecoach.app.ui.Stepper
import dev.routinecoach.app.ui.TIME_FMT
import dev.routinecoach.core.engine.EntryStatus
import dev.routinecoach.core.engine.Weeks
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun CalendarScreen(nav: NavController) {
    val c = LocalContext.current.container
    val ready = c.packs.current() ?: return
    val scope = rememberCoroutineScope()
    var weekStart by remember { mutableStateOf(Weeks.weekStart(LocalDate.now())) }
    val entries by remember(weekStart) { c.repo.observeRange(weekStart, weekStart.plusDays(7)) }.collectAsState(initial = emptyList())
    val unavailable by remember { c.repo.observeUnavailable() }.collectAsState(initial = emptyList())
    val deloads by remember { c.db.states().observeDeloads() }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<CalendarEntryEntity?>(null) }
    var addOn by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekStart = weekStart.minusWeeks(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Week of ${weekStart.format(DAY_FMT)}", style = MaterialTheme.typography.titleLarge)
                    if (deloads.any { LocalDate.parse(it.fromDate) <= weekStart.plusDays(6) && LocalDate.parse(it.toDate) >= weekStart }) {
                        Text("Deload this week", color = MaterialTheme.colorScheme.secondary)
                    }
                }
                IconButton(onClick = {
                    weekStart = weekStart.plusWeeks(1)
                    scope.launch { c.repo.ensureWeeks(weekStart) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
            }
            Text("Tap a session to move, swap or skip it. Changes you make by hand are never undone by automation.",
                style = MaterialTheme.typography.bodySmall)
        }
        for (i in 0..6) {
            val day = weekStart.plusDays(i.toLong())
            val off = unavailable.any { it.date == day.toString() }
            item(key = "d$day") {
                HorizontalDivider(Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day.format(DAY_FMT), style = MaterialTheme.typography.titleMedium, fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (day >= today) {
                        Text("Unavailable", style = MaterialTheme.typography.labelMedium)
                        Switch(checked = off, onCheckedChange = { v ->
                            scope.launch { if (v) c.automation.onDayUnavailable(day) else { c.repo.setUnavailable(day, false); c.reminders.sync() } }
                        }, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            val list = entries.filter { it.date == day }
            items(list, key = { "e${it.id}" }) { e ->
                val s = ready.pack.plan.session(e.sessionId)
                val status = EntryStatus.valueOf(e.status)
                Card(
                    Modifier.fillMaxWidth().clickable { selected = e },
                    colors = if (status == EntryStatus.PLANNED) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s?.name ?: e.sessionId, fontWeight = FontWeight.SemiBold)
                            Text("${e.startTime.format(TIME_FMT)} · ${if (e.variant == "SHORT") "short" else "full"}" + if (e.manual) " · edited" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (status != EntryStatus.PLANNED) Text(status.name.lowercase(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (day >= today) item(key = "add$day") {
                TextButton(onClick = { addOn = day }) { Text("+ Add session") }
            }
        }
        item {
            TextButton(onClick = { scope.launch { c.automation.scheduleManualDeload(weekStart) } }) { Text("Make this a deload week (half the sets)") }
        }
    }

    selected?.let { e -> EntryDialog(e, entries, onDismiss = { selected = null }) }
    addOn?.let { day ->
        PickDialog("Add session on ${day.format(DAY_FMT)}", ready.pack.plan.sessions.map { it.id to it.name }, onDismiss = { addOn = null }) { sid ->
            scope.launch {
                val time = c.repo.availability().windowsOn(day).firstOrNull()?.start ?: LocalTime.of(18, 0)
                c.repo.addEntry(sid, LocalDateTime.of(day, time))
                c.reminders.sync()
            }
            addOn = null
        }
    }
}

@Composable
private fun EntryDialog(e: CalendarEntryEntity, weekEntries: List<CalendarEntryEntity>, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val ready = c.packs.current() ?: return
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("menu") }
    fun done(block: suspend () -> Unit) {
        scope.launch { block(); c.reminders.sync() }
        onDismiss()
    }
    when (mode) {
        "move" -> MoveDialog(e, onDismiss) { dt -> done { c.repo.moveEntry(e.id, dt) } }
        "swap" -> PickDialog("Swap with", weekEntries.filter { it.id != e.id && it.status == EntryStatus.PLANNED.name }.map {
            it.id.toString() to "${ready.pack.plan.session(it.sessionId)?.name ?: it.sessionId} · ${it.startTime.format(DAY_FMT)} ${it.startTime.format(TIME_FMT)}"
        }, onDismiss) { other -> done { c.repo.swapEntries(e.id, other.toLong()) } }
        "change" -> PickDialog("Change to", ready.pack.plan.sessions.map { it.id to it.name }, onDismiss) { sid -> done { c.repo.replaceSession(e.id, sid) } }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(ready.pack.plan.session(e.sessionId)?.name ?: e.sessionId) },
            text = {
                Column {
                    Text("${e.startTime.format(DAY_FMT)} ${e.startTime.format(TIME_FMT)} · ${e.status.lowercase()}")
                    if (e.plannedStart != e.start) Text("Originally ${LocalDateTime.parse(e.plannedStart).format(DAY_FMT)}", style = MaterialTheme.typography.bodySmall)
                    val actions = buildList {
                        if (e.status == EntryStatus.PLANNED.name) {
                            add("Move to another day or time" to { mode = "move" })
                            add("Swap with another session" to { mode = "swap" })
                            add("Change session" to { mode = "change" })
                            add("Skip" to { done { c.repo.setStatus(e.id, EntryStatus.SKIPPED) } })
                        } else {
                            add("Restore as planned" to { done { c.repo.setStatus(e.id, EntryStatus.PLANNED) } })
                        }
                        add("Delete" to { done { c.repo.deleteEntry(e.id) } })
                    }
                    actions.forEach { (label, action) ->
                        TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }
}

@Composable
private fun MoveDialog(e: CalendarEntryEntity, onDismiss: () -> Unit, onMove: (LocalDateTime) -> Unit) {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(maxOf(e.date, today)) }
    var hour by remember { mutableStateOf(e.startTime.hour) }
    var minute by remember { mutableStateOf(e.startTime.minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move session") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val days = (0L..13L).map { today.plusDays(it) }
                days.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { d -> FilterChip(selected = d == day, onClick = { day = d }, label = { Text(d.format(DAY_FMT)) }) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stepper("Hour", hour, { hour = it }, min = 0, max = 23)
                    Stepper("Min", minute, { minute = it }, step = 15, min = 0, max = 45)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(LocalDateTime.of(day, LocalTime.of(hour, minute))) }) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PickDialog(title: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text("Nothing to choose.")
                options.forEach { (id, label) ->
                    TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
