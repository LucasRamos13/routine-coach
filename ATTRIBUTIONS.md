# Attributions

## Illustrations

All exercise illustrations in `content/library/illustrations` were created for this project. They are generated
from joint-angle poses stored in `content/library/exercises/*.json` by `PoseRenderer`
(`core/src/main/kotlin/dev/routinecoach/core/illustration/PoseRenderer.kt`) and are released under the project's
MIT license. No third-party images are included.

If you add third-party images to a pack, list them here with their author, source and a license that allows
redistribution.

## Dependencies

| Component | License |
|---|---|
| Kotlin, kotlinx.serialization, kotlinx.coroutines | Apache-2.0 |
| AndroidX (Compose, Room, WorkManager, DataStore, Navigation, Lifecycle) | Apache-2.0 |
| Coil (and coil-svg, which uses AndroidSVG) | Apache-2.0 |
| JUnit 4 | EPL-1.0 (tests only) |

All of them are compatible with distributing this project under MIT. The companion uses only the Python standard
library; the optional `win11toast` package (MIT) improves Windows notifications.
