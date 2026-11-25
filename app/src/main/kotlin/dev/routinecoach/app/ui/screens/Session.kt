package dev.routinecoach.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.routinecoach.app.container
import dev.routinecoach.app.timer.TimerKind
import dev.routinecoach.app.timer.TimerService
import dev.routinecoach.app.ui.BackBar
import dev.routinecoach.app.ui.Bullet
import dev.routinecoach.app.ui.Gap
import dev.routinecoach.app.ui.Illustration
import dev.routinecoach.app.ui.Stepper
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.pack.ExerciseMode

@Composable
fun SessionScreen(nav: NavController, entryId: Long?, sessionId: String?, variant: String, resumeId: Long?) {
    val ctx = LocalContext.current
    val c = ctx.container
    val vm: SessionViewModel = viewModel { SessionViewModel(c) }
    LaunchedEffect(Unit) { vm.init(entryId, sessionId, variant, resumeId) }
    val ui by vm.ui.collectAsState()
    val timer by TimerService.state.collectAsState()
    var menu by remember { mutableStateOf(false) }

    // Auto-advance when a rest or hold timer finishes.
    var seenFinished by remember { mutableIntStateOf(timer.finishedCount) }
    LaunchedEffect(timer.finishedCount) {
        if (timer.finishedCount != seenFinished) {
            seenFinished = timer.finishedCount
            val s = ui.step
            if (s is Step.Rest || s is Step.Stretch) vm.next()
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav, ui.prescription?.name ?: "Session") {
            TextButton(onClick = { vm.stopForWarning { nav.navigate("stop") } }) { Text("Warning signs", color = MaterialTheme.colorScheme.error) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val other = if (vm.currentVariant() == Variant.FULL) Variant.SHORT else Variant.FULL
                DropdownMenuItem(text = { Text("Switch to ${other.name.lowercase()} version") }, onClick = { menu = false; vm.setVariant(other) })
                DropdownMenuItem(text = { Text("Finish now") }, onClick = { menu = false; TimerService.stop(ctx); vm.finish() })
            }
        }
        when {
            ui.error != null -> Text("Could not open the session: ${ui.error}", Modifier.padding(16.dp))
            ui.loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            else -> {
                val total = ui.steps.size.coerceAtLeast(1)
                LinearProgressIndicator(progress = { ui.index / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                ui.prescription?.let { p ->
                    Text(
                        "${p.phase.name} · ${if (p.variant == Variant.FULL) "full" else "short"}" + (if (p.deload) " · deload" else "") +
                            " · stop ${p.phase.rirMin}-${p.phase.rirMax} reps before failure",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val s = ui.step) {
                        is Step.Warmup -> WarmupStep(s, vm)
                        is Step.Work -> WorkStep(nav, s, vm, ui)
                        is Step.Rest -> RestStep(s, vm, timer)
                        is Step.Stretch -> StretchStep(nav, s, vm, timer)
                        is Step.Cardio -> CardioStep(s, vm, timer)
                        Step.Summary -> SummaryStep(nav, vm, ui)
                        null -> Text("Nothing to do.")
                    }
                }
            }
        }
    }
}

@Composable
private fun WarmupStep(s: Step.Warmup, vm: SessionViewModel) {
    Text("Warm-up · ~3 min", style = MaterialTheme.typography.headlineSmall)
    s.lines.forEach { Bullet(it) }
    Text("Only dynamic mobility before strength work; stretching comes after.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) { Text("Start training") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkStep(nav: NavController, s: Step.Work, vm: SessionViewModel, ui: SessionUi) {
    val ctx = LocalContext.current
    val c = ctx.container
    val ex = c.packs.current()?.pack?.exercises?.get(s.item.exerciseId) ?: return
    val item = s.item
    val timed = item.mode != ExerciseMode.REPS
    val timer by TimerService.state.collectAsState()
    // Pre-fill with the values of the last time (RG-01).
    var reps by remember(s.key) { mutableIntStateOf(if (timed) item.min else item.min) }
    var level by remember(s.key) { mutableIntStateOf(item.level) }
    var rir by remember(s.key) { mutableStateOf<Int?>(item.rirMin) }
    var pain by remember(s.key) { mutableIntStateOf(0) }
    LaunchedEffect(s.key) {
        val prev = vm.previous(item.exerciseId)
        val same = prev.firstOrNull { it.setIndex == s.setIndex } ?: prev.lastOrNull()
        if (same != null) {
            reps = same.reps
            if (same.level == item.level) rir = same.rir ?: item.rirMin
        } else if (timed) reps = item.min
    }
    var skipDialog by remember { mutableStateOf(false) }
    var subDialog by remember { mutableStateOf(false) }

    val superset = s.block.isSuperset
    val label = if (superset) "${s.block.id}${s.block.items.indexOf(item) + 1}" else s.block.id
    Text("$label · Set ${s.setIndex} of ${item.sets}" + if (superset) " · superset" else "", style = MaterialTheme.typography.labelLarge)
    Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        (if (timed) "${item.min}-${item.max} s" else "${item.min}-${item.max} reps") + (if (item.unilateral) " per side" else "") +
            " · ${item.levelLabel}" + (item.cadence?.let { " · tempo ${it.eccentricSec}s down, ${it.concentricSec}s up" } ?: ""),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Illustration(ex, end = false, modifier = Modifier.weight(1f).height(130.dp))
        Illustration(ex, end = true, modifier = Modifier.weight(1f).height(130.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { nav.navigate("exercise/${ex.id}") }) { Text("How to") }
        if (!timed && item.cadence != null) {
            val on = timer.kind == TimerKind.CADENCE
            OutlinedButton(onClick = { if (on) TimerService.stop(ctx) else TimerService.cadence(ctx, item.cadence!!, item.name) }) {
                Text(if (on) "Stop beeps" else "Tempo beeps")
            }
        }
        if (timed) {
            val on = timer.kind == TimerKind.WORK
            OutlinedButton(onClick = { if (on) TimerService.stop(ctx) else TimerService.countdown(ctx, TimerKind.WORK, reps, item.name) }) {
                Text(if (on) "Stop ${timer.remainingSec}s" else "Start ${reps}s timer")
            }
        }
    }
    if (timer.kind == TimerKind.CADENCE) {
        Text("${timer.phase} ${timer.remainingSec} · rep ${timer.reps + 1}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(if (timed) "Seconds" else "Reps", reps, { reps = it }, step = if (timed) 5 else 1, max = 600)
            if (ex.ladder.size > 1) {
                Text("Variation", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ex.ladder.forEachIndexed { i, name -> FilterChip(selected = level == i, onClick = { level = i }, label = { Text(name) }) }
                }
            }
            Text("Reps in reserve (target ${item.rirMin}-${item.rirMax})", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..5).forEach { v -> FilterChip(selected = rir == v, onClick = { rir = v }, label = { Text(if (v == 5) "5+" else "$v") }) }
            }
            Text("Joint or sharp pain: $pain / 10", style = MaterialTheme.typography.labelLarge)
            Slider(value = pain.toFloat(), onValueChange = { pain = it.toInt() }, valueRange = 0f..10f, steps = 9)
            if (pain >= 4) Text(
                if (pain >= 6) "High pain: stop this exercise. The next session for these muscles will become recovery work."
                else "Pain noted: no automatic load increases this week.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Button(onClick = { TimerService.stop(ctx); vm.logSet(s, reps, level, rir, pain) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Log set", fontSize = 18.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { skipDialog = true }) { Text("Skip exercise") }
        if (ex.alternatives.isNotEmpty()) TextButton(onClick = { subDialog = true }) { Text("Substitute") }
        Spacer(Modifier.weight(1f))
        if (ui.index > 0) TextButton(onClick = vm::back) { Text("Back") }
    }

    if (skipDialog) ReasonDialog("Skip ${item.name}", onDismiss = { skipDialog = false }) { reason ->
        skipDialog = false; vm.skipExercise(item, reason)
    }
    if (subDialog) {
        var reason by remember { mutableStateOf<String?>(null) }
        if (reason == null) ReasonDialog("Substitute ${item.name}", onDismiss = { subDialog = false }) { reason = it }
        else PickDialog("Replace with", ex.alternatives.mapNotNull { id -> c.packs.current()?.pack?.exercises?.get(id)?.let { id to it.name } },
            onDismiss = { subDialog = false }) { alt -> subDialog = false; vm.substitute(item, alt, reason!!) }
    }
}

@Composable
private fun ReasonDialog(title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Why?")
                listOf("pain" to "Pain", "equipment" to "Equipment not available", "time" to "Short on time").forEach { (id, label) ->
                    TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestStep(s: Step.Rest, vm: SessionViewModel, timer: dev.routinecoach.app.timer.TimerState) {
    val ctx = LocalContext.current
    LaunchedEffect(s.key) {
        if (timer.kind != TimerKind.REST) TimerService.countdown(ctx, TimerKind.REST, s.seconds, "Rest · next: ${s.next}")
    }
    Text("Rest", style = MaterialTheme.typography.headlineSmall)
    val remaining = if (timer.kind == TimerKind.REST) timer.remainingSec else s.seconds
    Text("%d:%02d".format(remaining / 60, remaining % 60), style = MaterialTheme.typography.displayLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text("Next: ${s.next}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text("The timer keeps running with the screen off and beeps at the end.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { TimerService.countdown(ctx, TimerKind.REST, remaining + 15, "Rest · next: ${s.next}") }) { Text("+15 s") }
        Button(onClick = { TimerService.stop(ctx); vm.next() }) { Text("Skip rest") }
    }
}

@Composable
private fun StretchStep(nav: NavController, s: Step.Stretch, vm: SessionViewModel, timer: dev.routinecoach.app.timer.TimerState) {
    val ctx = LocalContext.current
    val ex = ctx.container.packs.current()?.pack?.exercises?.get(s.exerciseId) ?: return
    Text("Stretch · round ${s.round}" + (s.side?.let { " · $it side" } ?: ""), style = MaterialTheme.typography.labelLarge)
    Text(ex.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Illustration(ex, end = true, modifier = Modifier.fillMaxWidth().height(180.dp))
    ex.steps.forEachIndexed { i, t -> Bullet(t, i + 1) }
    Text("Hold to moderate discomfort, never sharp pain.", style = MaterialTheme.typography.bodySmall)
    val running = timer.kind == TimerKind.HOLD
    if (running) Text("${timer.remainingSec} s", style = MaterialTheme.typography.displayMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (running) TimerService.stop(ctx) else TimerService.countdown(ctx, TimerKind.HOLD, s.holdSec, ex.name) }) {
            Text(if (running) "Stop" else "Start ${s.holdSec} s hold")
        }
        OutlinedButton(onClick = { TimerService.stop(ctx); vm.next() }) { Text("Next") }
        TextButton(onClick = { nav.navigate("exercise/${ex.id}") }) { Text("How to") }
    }
}

@Composable
private fun CardioStep(s: Step.Cardio, vm: SessionViewModel, timer: dev.routinecoach.app.timer.TimerState) {
    val ctx = LocalContext.current
    Text(s.description.ifBlank { "Easy cardio" }, style = MaterialTheme.typography.titleMedium)
    Text("About ${s.minutes} minutes.", style = MaterialTheme.typography.bodyLarge)
    val running = timer.kind == TimerKind.WORK
    if (running) Text("%d:%02d".format(timer.remainingSec / 60, timer.remainingSec % 60), style = MaterialTheme.typography.displayMedium)
    Button(onClick = { if (running) TimerService.stop(ctx) else TimerService.countdown(ctx, TimerKind.WORK, s.minutes * 60, "Cardio") }) {
        Text(if (running) "Stop timer" else "Start ${s.minutes} min timer")
    }
    OutlinedButton(onClick = { TimerService.stop(ctx); vm.next() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun SummaryStep(nav: NavController, vm: SessionViewModel, ui: SessionUi) {
    val c = LocalContext.current.container
    val sets by remember(ui.sessionLogId) { c.repo.observeSets(ui.sessionLogId) }.collectAsState(initial = emptyList())
    Text(if (ui.finished) "Session saved" else "Well done", style = MaterialTheme.typography.headlineSmall)
    Text("${sets.size} sets logged.")
    if (!ui.finished) {
        Button(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Finish session") }
        TextButton(onClick = vm::back) { Text("Back") }
    } else {
        if (ui.decisions.isEmpty()) Text("No plan changes after this session.")
        else {
            Text("Plan changes", style = MaterialTheme.typography.titleMedium)
            ui.decisions.forEach { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.title, fontWeight = FontWeight.SemiBold)
                        Text(d.reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("You can undo any of them within 24 hours from Today or Settings › Plan changes.", style = MaterialTheme.typography.bodySmall)
        }
        Gap()
        Button(
            onClick = { nav.navigate("today") { popUpTo("today") { inclusive = true } } },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(),
        ) { Text("Done") }
    }
}
