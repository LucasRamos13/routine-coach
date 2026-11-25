# Illustrations

Every exercise has a start and an end frame. They are generated from **poses** (segment angles) by
`PoseRenderer`, so all figures share one simple style, variations are cheap and each SVG stays well under 20 KB.

## Pose format

Angles are absolute directions in degrees in a side view with the figure facing right:
`0` = up, `90` = forward, `180` = down, `270` = backward.

```json
"poses": {
  "start": { "torso": 68, "head": 72, "nearArm": [180, 180], "nearLeg": [248, 248], "nearFoot": 170 },
  "end":   { "torso": 86, "nearArm": [292, 180], "nearLeg": [266, 266] },
  "arrows": ["shoulder"],
  "anchor": "nearToe"
}
```

- Limbs are `[upper, lower]`: arm = upper arm and forearm, leg = thigh and shin. `far*` limbs default to the near ones.
- The figure is grounded on the floor automatically (or placed with `hip: [x, y]`).
- `anchor` keeps one joint at the same horizontal position in both frames (the planted foot, the hands of a push-up).
- `arrows` draws the path of each joint: solid in the start frame, dashed (return) in the end frame.
- Props: `dumbbell`, `band`, `box` (sofa, step; auto-height or fixed `height`), `towel`, `wall`.

Render after editing poses:

```bash
./gradlew :tools:installDist
tools/build/install/routine-coach-tools/bin/routine-coach-tools render --dir content/library
```

## Posture review

Before an illustration enters the library, a person checks it for safe and correct posture (spine, knee and
shoulder alignment, range of motion) and sets `"review": "approved"`. The validator warns while any illustration is
pending. CI re-renders the library and fails if the committed SVGs differ from their poses.

## Third-party images

Not allowed in this repository unless their license permits redistribution and they are listed in
`ATTRIBUTIONS.md`.
