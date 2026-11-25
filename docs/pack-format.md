# Content pack format (schema v1)

A pack is a folder:

```
my-pack/
├─ pack.json            manifest
├─ plan.json            phases, sessions, weekly template, targets, rules
├─ exercises/*.json     optional: pack exercises (new ones or translations of library ones)
└─ illustrations/*.svg  optional: illustrations for new pack exercises
```

The generic library (`content/library`) is always available: a plan can reference any library exercise by id.

## pack.json

```json
{ "schemaVersion": 1, "id": "demo", "name": "Demo: home full body", "language": "en", "description": "..." }
```

`schemaVersion` is checked first. A pack with a newer version than the app is rejected; older versions are migrated
in memory by `PackMigrations` (each schema change ships with a migration and a test).

## Exercises

| Field | Notes |
|---|---|
| `id` | lowercase, digits and `_` |
| `name`, `summary` | display texts |
| `primaryMuscle`, `secondaryMuscles` | volume counts only the primary muscle |
| `equipment` | free list, e.g. `dumbbell`, `band`, `sofa` |
| `category` | `strength`, `mobility`, `warmup`, `cardio` |
| `mode` | `reps`, `time` (logged in seconds) or `hold` (stretches) |
| `unilateral` | one side at a time |
| `steps`, `commonMistakes`, `breathing` | numbered steps are required |
| `cadence` | `eccentricSec`, `pauseBottomSec`, `concentricSec`, `pauseTopSec` |
| `ladder` | progression rungs, easiest first; the logged *level* is an index into it |
| `easier`, `harder`, `contraindications` | texts |
| `alternatives` | exercise ids offered for substitution and pain swaps |
| `illustrations` | `start` and `end` (`file`, `alt`) plus `review` (`pending`/`approved`) |
| `poses` | source poses for the generator (see illustrations.md) |

### Extending a library exercise

```json
{ "id": "flexao", "extends": "push_up", "name": "Flexão", "steps": ["...", "..."] }
```

All fields not given are inherited, including the illustrations.

## plan.json

```json
{
  "phases": [ { "id": "ramp", "name": "Ramp-up", "weeks": 2, "sets": 2, "rirMin": 3, "rirMax": 3, "eccentricSec": 3, "autoProgression": false },
              { "id": "main", "name": "Main", "sets": 3, "rirMin": 1, "rirMax": 2 } ],
  "deload": { "everyWeeks": 5, "setFactor": 0.5 },
  "sessions": [ {
      "id": "upper", "name": "Upper", "type": "strength", "durationMin": 30, "shortDurationMin": 20,
      "warmup": ["..."],
      "blocks": [ { "id": "A", "restSec": 75, "items": [
          { "exercise": "push_up", "range": [8, 20] },
          { "exercise": "band_row", "range": [12, 20] } ] } ],
      "shortBlocks": ["A", "B"],
      "stretches": { "exercises": ["chest_stretch"], "holdSec": 30, "rounds": 2 } } ],
  "week": { "mon": ["upper"], "tue": ["lower"] },
  "timeWindows": { "mon": ["18:00-21:00"] },
  "muscleTargets": { "chest": 8 },
  "recoverySession": "mobility",
  "guide": [ { "title": "How hard", "items": ["..."] } ],
  "rules": { "maxStrengthPerWeek": 4 }
}
```

- The last phase may omit `weeks` (open-ended). The phase decides sets, reps in reserve and the minimum eccentric time.
- A block with two items is a superset: A1 → A2 without rest, then the block rest.
- `sets` on an item fixes its sets; `maxSets` caps phase sets and automatic extra sets.
- `type` is `strength`, `cardio`, `interval` or `mobility`.
- `rules` overrides any threshold in `RuleConfig` (see automation-rules.md).

## Validation

Errors are reported as `file:line [field] message`, for example:

```
error: plan.json:42 [sessions[0].blocks[1].items[0].exercise] unknown exercise 'lunge'
```

Checked: JSON syntax, unknown or missing fields, types, ids and references, ranges, time windows, phases, short
blocks, alternatives, illustrations present and under 20 KB, alternative text present. A pending posture review is a
warning.
