package dev.routinecoach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import dev.routinecoach.app.container
import dev.routinecoach.core.pack.Exercise
import java.time.format.DateTimeFormatter
import java.util.Locale

val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(nav: NavController, title: String, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 2) },
        navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun Bullet(text: String, number: Int? = null) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(if (number != null) "$number." else "•", modifier = Modifier.padding(end = 8.dp), fontWeight = FontWeight.SemiBold)
        Text(text)
    }
}

private var svgLoader: ImageLoader? = null

@Composable
fun Illustration(exercise: Exercise, end: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = ctx.container
    val pack = c.packs.current()?.pack ?: return
    val ref = if (end) exercise.illustrations.end else exercise.illustrations.start
    val loader = svgLoader ?: ImageLoader.Builder(ctx).components { add(SvgDecoder.Factory()) }.build().also { svgLoader = it }
    var failed by remember(ref.file) { mutableStateOf(false) }
    if (failed) {
        // Never show an empty box: fall back to the description of the position.
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
            Text(ref.alt, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(c.packs.illustrationAsset(pack, ref.file)).build(),
        imageLoader = loader,
        contentDescription = ref.alt,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.White),
    )
}

@Composable
fun IllustrationPair(exercise: Exercise, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Illustration(exercise, end = false, modifier = Modifier.fillMaxWidth().height(150.dp))
            Text("Start", style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Illustration(exercise, end = true, modifier = Modifier.fillMaxWidth().height(150.dp))
            Text("End", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Big +/- stepper; the value itself is readable by screen readers. */
@Composable
fun Stepper(label: String, value: Int, onChange: (Int) -> Unit, step: Int = 1, min: Int = 0, max: Int = 999, suffix: String = "") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, modifier = Modifier.size(48.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text(
                "$value$suffix",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "$label $value$suffix" },
            )
            FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, modifier = Modifier.size(48.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

fun muscleLabel(m: String) = m.replace('_', ' ').replaceFirstChar { it.uppercase() }
