package dev.routinecoach.app.data

import android.content.Context
import dev.routinecoach.core.engine.SessionInfo
import dev.routinecoach.core.pack.ContentPack
import dev.routinecoach.core.pack.PackIssue
import dev.routinecoach.core.pack.PackLoadResult
import dev.routinecoach.core.pack.PackLoader
import dev.routinecoach.core.pack.PackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Reads pack files from the APK assets (`assets/pack` and `assets/library`). */
class AssetSource(private val context: Context, private val root: String) : PackSource {
    override fun read(path: String): String? = try {
        context.assets.open("$root/$path").bufferedReader().use { it.readText() }
    } catch (_: java.io.IOException) {
        null
    }

    override fun list(dir: String): List<String> = context.assets.list("$root/$dir")?.toList().orEmpty()

    override fun size(path: String): Long? = try {
        context.assets.open("$root/$path").use { s -> var n = 0L; val buf = ByteArray(8192); while (true) { val r = s.read(buf); if (r < 0) break; n += r }; n }
    } catch (_: java.io.IOException) {
        null
    }
}

sealed interface PackState {
    data object Loading : PackState
    data class Ready(val pack: ContentPack, val sessions: Map<String, SessionInfo>) : PackState
    data class Invalid(val issues: List<PackIssue>) : PackState
}

class PackRepository(private val context: Context) {
    private val _state = MutableStateFlow<PackState>(PackState.Loading)
    val state: StateFlow<PackState> = _state

    suspend fun load() = withContext(Dispatchers.IO) {
        val result = PackLoader().load(AssetSource(context, "pack"), AssetSource(context, "library"))
        _state.value = when (result) {
            is PackLoadResult.Success -> PackState.Ready(result.pack, SessionInfo.index(result.pack))
            is PackLoadResult.Failure -> PackState.Invalid(result.issues)
        }
    }

    suspend fun ready(): PackState.Ready = state.filterIsInstance<PackState.Ready>().first()

    fun current(): PackState.Ready? = state.value as? PackState.Ready

    /** Asset path of an illustration file, from the pack or the library. */
    fun illustrationAsset(pack: ContentPack, file: String): String =
        "file:///android_asset/${pack.illustrationRoots[file] ?: "library"}/illustrations/$file"
}
