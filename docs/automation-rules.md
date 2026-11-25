# Automation rules

Implementation: `core/src/main/kotlin/dev/routinecoach/core/engine/`. Tests: `core/src/test/kotlin/`.
All thresholds are defaults in `RuleConfig` and can be overridden per pack in `plan.json › rules`.

## Calendar (`Rescheduler`)

| Trigger | Reaction |
|---|---|
| Session not started by the end of the day | Moved to the next valid slot of the same week; short version if only a short window fits; otherwise marked missed with a notice |
| Day marked unavailable | Its sessions are redistributed over the rest of the week |
| Sleep under 6 h for 3 nights in a row (manual log) | Next strength session switches to the short version |
| Pain ≥ 6 in any set | Next session for the same muscles is replaced by the recovery session (walk/mobility) |

A slot is valid when it is inside one of the user's time windows and long enough for the session, and when:

- sessions training the same muscles are at least **48 h** apart;
- the week has at most **4** strength sessions and a day at most **1**;
- no **interval** session falls in the 48 h before a leg session.

Only strength sessions are moved when missed; cardio and mobility are marked missed. Entries the user edited by hand
(`manual`) are never modified by automation.

## Progression (`ProgressionEngine`)

| Rule | Condition | Action |
|---|---|---|
| Increase difficulty | All sets at the top of the range with RIR ≥ target minimum, 2 sessions in a row | Next ladder rung (variation, stronger band or slower tempo); at most 1 change per exercise per 7 days |
| Add a set | Weekly sets for the primary muscle below target, last 2 sessions easy (RIR above target), no pain in 7 days | +1 set, up to 4 per exercise and +1 per muscle every 14 days |
| Deload | Performance drop (average reps −10 %) in 2 sessions in a row, or 5 nights under 6 h in 7 days, or pain ≥ 4 in 3 sessions in a row | Half the sets for 7 days starting now; not within 21 days of the previous one |
| Swap exercise | Pain ≥ 4 in 3 sessions in a row for one exercise | First alternative from the pack; the original stops progressing while swapped |

Phases with `autoProgression: false` (e.g. the ramp-up) never increase difficulty or volume.

## Safety limits

- Reductions (deload, short version, recovery, swap) apply immediately; increases only from the next session,
  because prescriptions are computed when a session starts.
- No automatic increase with pain ≥ 4 in the last 7 days.
- Each change records a notice with the reason and the data used, a 24 h undo window and a history entry.
- At most 2 automatic reschedules per week (moves/skips of missed sessions). Safety reductions are never blocked by
  this limit. User-triggered redistribution (unavailable day) does not count.
- Pause mode turns all automation off.
- Warning signs (chest pain, dizziness, sharp pain) stop the session and show guidance; nothing is rescheduled.
