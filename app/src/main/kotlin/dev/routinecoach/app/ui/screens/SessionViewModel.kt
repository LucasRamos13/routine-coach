package dev.routinecoach.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.routinecoach.app.AppContainer
import dev.routinecoach.app.data.ExerciseEventEntity
import dev.routinecoach.app.data.SetLogEntity
import dev.routinecoach.core.engine.Decision
import dev.routinecoach.core.engine.PrescribedBlock
import dev.routinecoach.core.engine.PrescribedItem
import dev.routinecoach.core.engine.Prescription
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.pack.ExerciseMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface Step {
    val key: String

    data class Warmup(val lines: List<String>) : Step { override val key = "warmup" }
    data class Work(val block: PrescribedBlock, val item: PrescribedItem, val setIndex: Int, val roundOf: Int) : Step {
        override val key = "work:${block.id}:${item.plannedExerciseId}:$setIndex"
    }
    data class Rest(val seconds: Int, val after: String, val next: String) : Step { override val key = "rest:$after" }
    data class Stretch(val exerciseId: String, val side: String?, val round: Int, val holdSec: Int) : Step {
        override val key = "stretch:$exerciseId:$round:${side ?: "-"}"
    }
    data class Cardio(val description: String, val minutes: Int) : Step { override val key = "cardio" }
    data object Summary : Step { override val key = "summary" }
}

data class SessionUi(
    val loading: Boolean = true,
    val sessionLogId: Long = 0,
    val entryId: Long? = null,
    val prescription: Prescription? = null,
    val steps: List<Step> = emptyList(),
    val index: Int = 0,
    val done: Set<String> = emptySet(),
    val finished: Boolean = false,
    val decisions: List<Decision> = emptyList(),
    val error: String? = null,
) {
    val step: Step? get() = steps.getOrNull(index)
}

class SessionViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui

    /** plannedExerciseId -> substitute exercise id; and skipped planned exercises. */
    private val substitutes = HashMap<String, String>()
    private val skipped = HashSet<String>()
    private var variant = Variant.FULL
    private var sessionId = ""
    private var startedOn: LocalDate = LocalDate.now()
    private var initialized = false

    fun init(entryId: Long?, sessionId: String?, variant: String, resumeId: Long?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                c.packs.ready()
                val logId: Long
                if (resumeId != null) {
                    val s = c.repo.session(resumeId) ?: error("Session not found")
                    logId = s.id
                    this@SessionViewModel.sessionId = s.sessionId
                    this@SessionViewModel.variant = Variant.valueOf(s.variant)
                    startedOn = LocalDateTime.parse(s.startedAt).toLocalDate()
                    _ui.value = _ui.value.copy(entryId = s.entryId)
                    c.db.logs().allEvents().filter { it.sessionLogId == s.id }.forEach { ev ->
                        if (ev.type == "skip") skipped += ev.exerciseId else ev.replacementId?.let { substitutes[ev.exerciseId] = it }
                    }
                } else {
                    this@SessionViewModel.sessionId = sessionId ?: error("No session")
                    this@SessionViewModel.variant = Variant.valueOf(variant)
                    logId = c.repo.startSession(entryId, this@SessionViewModel.sessionId, this@SessionViewModel.variant)
                    _ui.value = _ui.value.copy(entryId = entryId)
                }
                val logged = c.repo.sets(logId).map { "work:${it.blockId}:${it.plannedExerciseId}:${it.setIndex}" }.toSet()
                _ui.value = _ui.value.copy(sessionLogId = logId, done = logged)
                rebuild(jumpToFirstOpen = true)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message)
            }
        }
    }

    private suspend fun rebuild(jumpToFirstOpen: Boolean) {
        val base = c.repo.prescription(sessionId, variant, startedOn)
        val pack = c.packs.ready().pack
        val states = c.repo.states()
        val p = base.copy(blocks = base.blocks.map { b ->
            b.copy(items = b.items.filter { it.plannedExerciseId !in skipped }.map { item ->
                val sub = substitutes[item.plannedExerciseId] ?: return@map item
                val ex = pack.exercise(sub)
                val level = (states[sub]?.level ?: 0).coerceIn(0, (ex.ladder.size - 1).coerceAtLeast(0))
                item.copy(exerciseId = sub, name = ex.name, mode = ex.mode, unilateral = ex.unilateral, cadence = ex.cadence,
                    level = level, levelLabel = ex.levelLabel(level))
            })
        }.filter { it.items.isNotEmpty() })
        val steps = buildSteps(p)
        val cur = _ui.value
        val index = if (jumpToFirstOpen || cur.step == null) {
            steps.indexOfFirst { it.key !in cur.done }.let { if (it < 0) steps.lastIndex else it }
        } else {
            steps.indexOfFirst { it.key == cur.step?.key }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.key !in cur.done }.coerceAtLeast(0)
        }
        _ui.value = cur.copy(loading = false, prescription = p, steps = steps, index = index)
    }

    private fun buildSteps(p: Prescription): List<Step> {
        val pack = c.packs.current()!!.pack
        val out = mutableListOf<Step>()
        if (p.warmup.isNotEmpty()) out += Step.Warmup(p.warmup)
        p.blocks.forEachIndexed { bi, b ->
            val rounds = b.items.maxOf { it.sets }
            for (r in 1..rounds) {
                for (item in b.items) if (r <= item.sets) out += Step.Work(b, item, r, rounds)
                val lastOverall = bi == p.blocks.lastIndex && r == rounds
                if (!lastOverall) {
                    val next = if (r < rounds) b.items.first().name else p.blocks[bi + 1].items.first().name
                    val rest = b.items.lastOrNull { r <= it.sets }?.restSec ?: b.restSec
                    out += Step.Rest(rest, "${b.id}:$r", next)
                }
            }
        }
        p.stretches?.let { st ->
            for (id in st.exercises) {
                val ex = pack.exercises[id] ?: continue
                for (round in 1..st.rounds) {
                    val sides = if (ex.unilateral) listOf("Left", "Right") else listOf(null)
                    for (side in sides) out += Step.Stretch(id, side, round, st.holdSec)
                }
            }
        }
        if (p.blocks.isEmpty() && p.stretches == null) out += Step.Cardio(p.description, p.durationMin)
        out += Step.Summary
        return out
    }

    fun next() {
        val u = _ui.value
        val s = u.step ?: return
        _ui.value = u.copy(index = (u.index + 1).coerceAtMost(u.steps.lastIndex), done = u.done + s.key)
    }

    fun back() {
        val u = _ui.value
        _ui.value = u.copy(index = (u.index - 1).coerceAtLeast(0))
    }

    fun logSet(step: Step.Work, reps: Int, level: Int, rir: Int?, pain: Int) {
        val u = _ui.value
        viewModelScope.launch {
            val now = LocalDateTime.now()
            c.repo.logSet(
                SetLogEntity(
                    sessionLogId = u.sessionLogId, exerciseId = step.item.exerciseId, plannedExerciseId = step.item.plannedExerciseId,
                    blockId = step.block.id, setIndex = step.setIndex, reps = reps, level = level, rir = rir, pain = pain,
                    rangeMin = step.item.min, rangeMax = step.item.max, rirMin = step.item.rirMin, rirMax = step.item.rirMax,
                    loggedAt = now.toString(),
                ),
            )
            if (level != step.item.level) {
                // The user chose another variation by hand; remember it for next time.
                val st = c.db.states().get(step.item.exerciseId) ?: dev.routinecoach.app.data.ExerciseStateEntity(step.item.exerciseId)
                c.db.states().upsert(st.copy(level = level))
            }
            c.automation.onSetLogged(step.item.exerciseId, pain, u.entryId, now)
            next()
        }
    }

    suspend fun previous(exerciseId: String): List<SetLogEntity> = c.repo.previousSets(exerciseId, _ui.value.sessionLogId)

    fun setVariant(v: Variant) {
        if (v == variant) return
        variant = v
        viewModelScope.launch {
            _ui.value.entryId?.let { id -> c.repo.entry(id)?.let { c.repo.updateEntry(it.copy(variant = v.name)) } }
            c.repo.session(_ui.value.sessionLogId)?.let { c.db.logs().updateSession(it.copy(variant = v.name)) }
            rebuild(jumpToFirstOpen = false)
        }
    }

    fun currentVariant() = variant

    fun skipExercise(item: PrescribedItem, reason: String) = viewModelScope.launch {
        c.repo.logEvent(ExerciseEventEntity(sessionLogId = _ui.value.sessionLogId, exerciseId = item.plannedExerciseId, type = "skip",
            replacementId = null, reason = reason, at = LocalDateTime.now().toString()))
        skipped += item.plannedExerciseId
        rebuild(jumpToFirstOpen = false)
    }

    fun substitute(item: PrescribedItem, replacement: String, reason: String) = viewModelScope.launch {
        c.repo.logEvent(ExerciseEventEntity(sessionLogId = _ui.value.sessionLogId, exerciseId = item.plannedExerciseId, type = "substitute",
            replacementId = replacement, reason = reason, at = LocalDateTime.now().toString()))
        substitutes[item.plannedExerciseId] = replacement
        rebuild(jumpToFirstOpen = false)
    }

    fun finish() = viewModelScope.launch {
        val id = _ui.value.sessionLogId
        c.repo.finishSession(id, "completed")
        val decisions = if (_ui.value.prescription?.blocks?.isNotEmpty() == true) c.automation.onSessionFinished(id) else emptyList()
        c.reminders.sync()
        _ui.value = _ui.value.copy(finished = true, decisions = decisions)
    }

    fun stopForWarning(after: () -> Unit) = viewModelScope.launch {
        c.repo.finishSession(_ui.value.sessionLogId, "stopped")
        after()
    }

    fun isTimed(item: PrescribedItem) = item.mode != ExerciseMode.REPS
}
