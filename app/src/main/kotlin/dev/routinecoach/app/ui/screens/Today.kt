package dev.routinecoach.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import dev.routinecoach.app.data.AppSettings
import dev.routinecoach.app.data.AutomationChangeEntity
import dev.routinecoach.app.data.CalendarEntryEntity
import dev.routinecoach.app.startSession
import dev.routinecoach.app.ui.DAY_FMT
import dev.routinecoach.app.ui.TIME_FMT
import dev.routinecoach.core.engine.EntryStatus
import dev.routinecoach.core.engine.PlanTimeline
import dev.routinecoach.core.engine.Variant
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun TodayScreen(nav: NavController, settings: AppSettings) {
    val c = LocalContext.current.container
    val ready = c.packs.current() ?: return
    val today = LocalDate.now()
    val entries by remember { c.repo.observeRange(today, today.plusDays(8)) }.collectAsState(initial = emptyList())
    val changes by remember { c.db.states().observeChanges() }.collectAsState(initial = emptyList())
    val deloads by remember { c.db.states().observeDeloads() }.collectAsState(initial = emptyList())
    val header by produceState<Triple<String, Int, Boolean>?>(null, deloads) {
        val start = c.repo.planStart()
        val phase = PlanTimeline.phase(ready.pack.plan, start, today)
        value = Triple(phase.name, PlanTimeline.weekIndex(start, today) + 1, c.repo.isDeload(today))
    }
    val open by produceState<Long?>(null) { value = c.repo.openSession()?.id }
    var showBodyLog by remember { mutableStateOf(false) }
    val now = LocalDateTime.now()
    val recentChanges = changes.filter { c.automation.canUndo(it, now) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(today.format(DAY_FMT), style = MaterialTheme.typography.labelLarge)
                Text("Today", style = MaterialTheme.typography.headlineMedium)
                header?.let { (phase, week, deload) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("Week $week · $phase") })
                        if (deload) AssistChip(onClick = {}, label = { Text("Deload week") })
                        if (settings.paused) AssistChip(onClick = { nav.navigate("settings") }, label = { Text("Automation paused") })
                    }
                }
            }
        }
        open?.let { id ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("A session is in progress", Modifier.weight(1f))
                        Button(onClick = { nav.navigate("session?resume=$id") }) { Text("Resume") }
                    }
                }
            }
        }
        if (recentChanges.isNotEmpty()) {
            item { Text("Plan changes", style = MaterialTheme.typography.titleMedium) }
            items(recentChanges, key = { "chg${it.id}" }) { ChangeCard(it, compact = true) }
        }
        val todays = entries.filter { it.date == today }
        item { Text("Sessions today", style = MaterialTheme.typography.titleMedium) }
        if (todays.isEmpty()) item { Text("Rest day. Nothing planned today.") }
        items(todays, key = { "e${it.id}" }) { e -> SessionCard(nav, e, highlight = true) }
        val upcoming = entries.filter { it.date > today && it.status == EntryStatus.PLANNED.name }.take(4)
        if (upcoming.isNotEmpty()) {
            item { Text("Coming up", style = MaterialTheme.typography.titleMedium) }
            items(upcoming, key = { "u${it.id}" }) { e -> SessionCard(nav, e, highlight = false) }
        }
        item {
            OutlinedButton(onClick = { showBodyLog = true }, modifier = Modifier.fillMaxWidth()) { Text("Log weight, sleep, steps or protein") }
        }
        item {
            TextButton(onClick = { nav.navigate("stop") }, modifier = Modifier.fillMaxWidth()) {
                Text("Warning signs? Chest pain, dizziness, sharp pain", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showBodyLog) BodyLogDialog(onDismiss = { showBodyLog = false })
}

@Composable
fun SessionCard(nav: NavController, e: CalendarEntryEntity, highlight: Boolean) {
    val c = LocalContext.current.container
    val ready = c.packs.current() ?: return
    val s = ready.pack.plan.session(e.sessionId)
    val scope = rememberCoroutineScope()
    var variant by remember(e.id, e.variant) { mutableStateOf(Variant.valueOf(e.variant)) }
    val status = EntryStatus.valueOf(e.status)
    Card(
        colors = if (highlight) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s?.name ?: e.sessionId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${e.startTime.format(DAY_FMT)} · ${e.startTime.format(TIME_FMT)}" + if (e.manual) " · edited" else "", style = MaterialTheme.typography.bodyMedium)
                }
                if (status != EntryStatus.PLANNED) AssistChip(onClick = {}, label = { Text(status.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
            s?.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (status == EntryStatus.PLANNED && s != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Variant.entries.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = variant == v,
                            onClick = {
                                variant = v
                                scope.launch { c.repo.setVariant(e.id, v) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, Variant.entries.size),
                        ) { Text(if (v == Variant.FULL) "Full · ~${s.durationMin} min" else "Short · ~${s.shortDurationMin} min") }
                    }
                }
                if (highlight) {
                    Button(onClick = { nav.startSession(e.id, e.sessionId, variant.name) }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
                }
            }
        }
    }
}

@Composable
fun ChangeCard(ch: AutomationChangeEntity, compact: Boolean) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(!compact) }
    val undoable = c.automation.canUndo(ch)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ch.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(LocalDateTime.parse(ch.createdAt).let { "${it.format(DAY_FMT)} ${it.format(TIME_FMT)}" } +
                (if (ch.undoneAt != null) " · undone" else ""), style = MaterialTheme.typography.bodySmall)
            Text(ch.reason, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                val data = runCatching { Json.decodeFromString<Map<String, String>>(ch.dataJson) }.getOrDefault(emptyMap())
                if (data.isNotEmpty()) {
                    Text("Data used", style = MaterialTheme.typography.labelLarge)
                    data.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
                if (undoable) TextButton(onClick = { scope.launch { c.automation.undo(ch.id) } }) { Text("Undo") }
            }
        }
    }
}
