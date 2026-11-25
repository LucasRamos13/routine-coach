package dev.routinecoach.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.routinecoach.app.container
import dev.routinecoach.app.data.BodyMetricEntity
import dev.routinecoach.app.data.SetLogEntity
import dev.routinecoach.app.ui.BackBar
import dev.routinecoach.app.ui.DAY_FMT
import dev.routinecoach.app.ui.SectionTitle
import dev.routinecoach.app.ui.Stepper
import dev.routinecoach.app.ui.TIME_FMT
import dev.routinecoach.app.ui.muscleLabel
import dev.routinecoach.core.engine.Metrics
import dev.routinecoach.core.engine.Weeks
import dev.routinecoach.core.pack.ExerciseMode
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Composable
fun ProgressScreen(nav: NavController) {
    val c = LocalContext.current.container
    val pack = c.packs.current()?.pack ?: return
    val weekStart = Weeks.weekStart(LocalDate.now())
    val weekSets by remember { c.repo.observeSetsBetween(weekStart, weekStart.plusDays(7)) }.collectAsState(initial = emptyList())
    val weights by remember { c.repo.observeBody("weight") }.collectAsState(initial = emptyList())
    val recent by remember { c.repo.observeRecentBody() }.collectAsState(initial = emptyList())
    val sessions by remember { c.repo.observeSessions() }.collectAsState(initial = emptyList())
    var showLog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Progress", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }

        // MT-01 weekly sets per muscle against the target.
        item { SectionTitle("Sets this week vs target") }
        val volume = Metrics.weeklyVolume(Metrics.setsByMuscle(weekSets.map { it.exerciseId }, pack.exercises), pack.plan.muscleTargets)
        if (volume.isEmpty()) item { Text("No sets logged this week yet.") }
        items(volume, key = { "v${it.muscle}" }) { v ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row {
                    Text(muscleLabel(v.muscle), Modifier.weight(1f))
                    Text(if (v.target != null) "${v.done} / ${v.target}" else "${v.done}")
                }
                LinearProgressIndicator(
                    progress = { val t = v.target; if (t == null || t == 0) 1f else (v.done / t.toFloat()).coerceAtMost(1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
        }

        // MT-03 weight with a 7-day moving average.
        item { SectionTitle("Body weight (7-day average)") }
        item {
            val pts = weights.map { LocalDate.parse(it.date) to it.value }.filter { it.first >= LocalDate.now().minusDays(90) }
            if (pts.size < 2) Text("Log your weight a few times to see the trend.")
            else WeightChart(pts)
        }
        item { OutlinedButton(onClick = { showLog = true }, modifier = Modifier.fillMaxWidth()) { Text("Log body data") } }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recent entries") }
            items(recent.take(10), key = { "b${it.id}" }) { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${LocalDate.parse(m.date).format(DAY_FMT)} · ${m.name ?: m.type}: ${fmtNum(m.value)} ${m.unit}", Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { c.repo.deleteBody(m.id) } }) { Text("Delete") }
                }
            }
        }

        item { SectionTitle("Session history") }
        if (sessions.isEmpty()) item { Text("No sessions yet.") }
        items(sessions.take(50), key = { "s${it.id}" }) { s ->
            val start = LocalDateTime.parse(s.startedAt)
            Column(Modifier.fillMaxWidth().clickable { nav.navigate("history/${s.id}") }.padding(vertical = 8.dp)) {
                Text(pack.plan.session(s.sessionId)?.name ?: s.sessionId, fontWeight = FontWeight.SemiBold)
                Text("${start.format(DAY_FMT)} ${start.format(TIME_FMT)} · ${s.variant.lowercase()} · ${s.outcome ?: "in progress"}" + if (s.deload) " · deload" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
    if (showLog) BodyLogDialog(onDismiss = { showLog = false })
}

private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun WeightChart(points: List<Pair<LocalDate, Double>>) {
    val ma = Metrics.movingAverage(points)
    val first = points.minOf { it.first }
    val span = ChronoUnit.DAYS.between(first, points.maxOf { it.first }).coerceAtLeast(1)
    val lo = points.minOf { it.second } - 0.5
    val hi = points.maxOf { it.second } + 0.5
    val raw = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.primary
    val last = ma.last()
    Column {
        Text("Latest average: ${"%.1f".format(last.second)} kg", style = MaterialTheme.typography.bodyMedium)
        Canvas(
            Modifier.fillMaxWidth().height(160.dp)
                .semantics { contentDescription = "Weight chart. 7-day average ${"%.1f".format(last.second)} kg on ${last.first}." },
        ) {
            fun x(d: LocalDate) = ChronoUnit.DAYS.between(first, d).toFloat() / span * size.width
            fun y(v: Double) = ((hi - v) / (hi - lo)).toFloat() * size.height
            points.forEach { (d, v) -> drawCircle(raw, radius = 4f, center = Offset(x(d), y(v))) }
            val path = Path()
            ma.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
            drawPath(path, line, style = Stroke(width = 5f))
        }
        Row {
            Text(first.format(DAY_FMT), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("${"%.1f".format(lo + 0.5)}–${"%.1f".format(hi - 0.5)} kg", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** RG-03: quick manual entry of weight, sleep, steps, protein and measurements (source = manual). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BodyLogDialog(onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val types = listOf(
        Triple("weight", "Weight", "kg"), Triple("sleep", "Sleep", "h"), Triple("steps", "Steps", "steps"),
        Triple("protein", "Protein", "g"), Triple("measurement", "Measurement", "cm"),
    )
    var type by remember { mutableStateOf(types[0]) }
    var value by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Waist") }
    var yesterday by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log body data") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.second) }) }
                }
                if (type.first == "measurement") OutlinedTextField(name, { name = it }, label = { Text("What (e.g. waist, arm)") }, singleLine = true)
                OutlinedTextField(
                    value, { value = it.replace(',', '.') },
                    label = { Text("${type.second} (${type.third})" + if (type.first == "sleep") " last night" else "") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("Today") })
                    Text(" ", Modifier.width(6.dp))
                    FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("Yesterday") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = value.toDoubleOrNull() ?: return@TextButton
                val date = if (yesterday) LocalDate.now().minusDays(1) else LocalDate.now()
                scope.launch {
                    c.repo.logBody(BodyMetricEntity(date = date.toString(), type = type.first, name = if (type.first == "measurement") name else null,
                        value = v, unit = type.third, recordedAt = LocalDateTime.now().toString()))
                    if (type.first == "sleep") c.automation.onSleepLogged()
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SessionDetailScreen(nav: NavController, id: Long) {
    val c = LocalContext.current.container
    val pack = c.packs.current()?.pack ?: return
    val sets by remember(id) { c.repo.observeSets(id) }.collectAsState(initial = emptyList())
    val events by remember(id) { c.repo.observeEvents(id) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<SetLogEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackBar(nav, "Session")
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item { Text("Tap a set to edit or delete it.", style = MaterialTheme.typography.bodySmall) }
            sets.groupBy { it.exerciseId }.forEach { (exId, list) ->
                val ex = pack.exercises[exId]
                item(key = "h$exId") { SectionTitle(ex?.name ?: exId) }
                items(list, key = { "set${it.id}" }) { s ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { editing = s }) {
                        Text(
                            "Set ${s.setIndex}: ${s.reps}${if (ex?.mode == ExerciseMode.REPS) " reps" else " s"} · ${ex?.levelLabel(s.level) ?: s.level} · RIR ${s.rir ?: "-"} · pain ${s.pain}",
                            Modifier.padding(12.dp),
                        )
                    }
                }
            }
            if (events.isNotEmpty()) {
                item { SectionTitle("Skipped or substituted") }
                items(events, key = { "ev${it.id}" }) { e ->
                    Text("${pack.exercises[e.exerciseId]?.name ?: e.exerciseId}: ${e.type}" +
                        (e.replacementId?.let { " → ${pack.exercises[it]?.name ?: it}" } ?: "") + " (${e.reason})")
                }
            }
        }
    }
    editing?.let { s -> EditSetDialog(s, onDismiss = { editing = null }) }
}

/** RG-02: edit or delete a set; metrics recompute automatically from the database. */
@Composable
private fun EditSetDialog(s: SetLogEntity, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var reps by remember { mutableIntStateOf(s.reps) }
    var rir by remember { mutableStateOf(s.rir) }
    var pain by remember { mutableIntStateOf(s.pain) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit set ${s.setIndex}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper("Reps / seconds", reps, { reps = it }, max = 600)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..5).forEach { v -> FilterChip(selected = rir == v, onClick = { rir = v }, label = { Text("$v") }) }
                }
                Text("Pain $pain / 10")
                Slider(value = pain.toFloat(), onValueChange = { pain = it.toInt() }, valueRange = 0f..10f, steps = 9)
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { c.repo.updateSet(s.copy(reps = reps, rir = rir, pain = pain)) }; onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { scope.launch { c.repo.deleteSet(s.id) }; onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
    )
}
