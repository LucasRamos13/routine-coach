package dev.routinecoach.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.routinecoach.app.container
import dev.routinecoach.app.ui.BackBar
import dev.routinecoach.app.ui.Bullet
import dev.routinecoach.app.ui.Illustration
import dev.routinecoach.app.ui.IllustrationPair
import dev.routinecoach.app.ui.SectionTitle
import dev.routinecoach.app.ui.muscleLabel
import dev.routinecoach.core.pack.ExerciseCategory

@Composable
fun LibraryScreen(nav: NavController) {
    val pack = LocalContext.current.container.packs.current()?.pack ?: return
    // Exercises used by the plan first, then the rest of the library.
    val used = pack.plan.sessions.flatMap { s -> s.blocks.flatMap { b -> b.items.map { it.exercise } } + s.stretches?.exercises.orEmpty() }.toSet()
    val groups = pack.exercises.values.sortedBy { it.name }.groupBy {
        when {
            it.id in used && it.category == ExerciseCategory.MOBILITY -> "Stretches in your plan"
            it.id in used -> "Exercises in your plan"
            else -> "Other exercises"
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Exercises", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
        item {
            androidx.compose.material3.OutlinedButton(onClick = { nav.navigate("guide") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Plan guide: phases, how hard, safety")
            }
        }
        for (title in listOf("Exercises in your plan", "Stretches in your plan", "Other exercises")) {
            val list = groups[title] ?: continue
            item { SectionTitle(title) }
            items(list, key = { it.id }) { ex ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("exercise/${ex.id}") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Illustration(ex, end = true, modifier = Modifier.size(64.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(ex.name, fontWeight = FontWeight.SemiBold)
                        Text(muscleLabel(ex.primaryMuscle) + if (ex.equipment.isNotEmpty()) " · " + ex.equipment.joinToString() else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Exercise card (EX-01): steps, tips and the two illustrations, all bundled for offline use. */
@Composable
fun ExerciseScreen(nav: NavController, id: String) {
    val pack = LocalContext.current.container.packs.current()?.pack ?: return
    val ex = pack.exercises[id] ?: return
    Column(Modifier.fillMaxSize()) {
        BackBar(nav, ex.name)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IllustrationPair(ex)
            if (ex.summary.isNotBlank()) Text(ex.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Main muscle: ${muscleLabel(ex.primaryMuscle)}" +
                    (if (ex.secondaryMuscles.isNotEmpty()) " · also ${ex.secondaryMuscles.joinToString { muscleLabel(it) }}" else "") +
                    (if (ex.equipment.isNotEmpty()) "\nEquipment: ${ex.equipment.joinToString()}" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            SectionTitle("Steps")
            ex.steps.forEachIndexed { i, s -> Bullet(s, i + 1) }
            if (ex.commonMistakes.isNotEmpty()) {
                SectionTitle("Common mistakes")
                ex.commonMistakes.forEach { Bullet(it) }
            }
            if (ex.breathing.isNotBlank() || ex.cadence != null) {
                SectionTitle("Breathing and tempo")
                if (ex.breathing.isNotBlank()) Text(ex.breathing)
                ex.cadence?.let { c ->
                    Text("Tempo: ${c.eccentricSec} s down" + (if (c.pauseBottomSec > 0) ", ${c.pauseBottomSec} s pause" else "") +
                        ", ${c.concentricSec} s up" + (if (c.pauseTopSec > 0) ", ${c.pauseTopSec} s squeeze" else ""))
                }
            }
            if (ex.ladder.isNotEmpty()) {
                SectionTitle("Progression")
                ex.ladder.forEachIndexed { i, s -> Bullet(s, i + 1) }
            }
            if (ex.easier.isNotBlank()) { SectionTitle("Easier"); Text(ex.easier) }
            if (ex.harder.isNotBlank()) { SectionTitle("Harder"); Text(ex.harder) }
            if (ex.contraindications.isNotEmpty()) {
                SectionTitle("Take care")
                ex.contraindications.forEach { Bullet(it) }
            }
            if (ex.alternatives.isNotEmpty()) {
                SectionTitle("Alternatives")
                ex.alternatives.mapNotNull { pack.exercises[it] }.forEach { alt ->
                    Text(alt.name, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.navigate("exercise/${alt.id}") }.padding(vertical = 6.dp))
                }
            }
        }
    }
}

/** Plan guide: phases and the general guidance sections from the pack. */
@Composable
fun GuideScreen(nav: NavController) {
    val pack = LocalContext.current.container.packs.current()?.pack ?: return
    Column(Modifier.fillMaxSize()) {
        BackBar(nav, "Plan guide")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(pack.manifest.name, style = MaterialTheme.typography.titleLarge)
            if (pack.manifest.description.isNotBlank()) Text(pack.manifest.description)
            SectionTitle("Phases")
            pack.plan.phases.forEach { p ->
                Text("${p.name}: ${p.sets} sets, ${p.rirMin}-${p.rirMax} reps in reserve" + (p.weeks?.let { " · $it weeks" } ?: ""),
                    fontWeight = FontWeight.SemiBold)
                if (p.notes.isNotBlank()) Text(p.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            pack.plan.deload.everyWeeks?.let { Text("Deload every $it weeks (sets × ${pack.plan.deload.setFactor}).", style = MaterialTheme.typography.bodyMedium) }
            pack.plan.guide.forEach { g ->
                SectionTitle(g.title)
                g.items.forEach { Bullet(it) }
            }
        }
    }
}
