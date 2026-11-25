package dev.routinecoach.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.routinecoach.app.container
import dev.routinecoach.app.timer.TimerService
import dev.routinecoach.app.ui.Bullet
import dev.routinecoach.app.ui.Gap
import dev.routinecoach.core.pack.PackIssue
import dev.routinecoach.core.pack.Severity
import kotlinx.coroutines.launch

@Composable
fun DisclaimerScreen() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Gap(24)
        Text("Before you start", style = MaterialTheme.typography.headlineMedium)
        Text("Routine Coach guides home workouts and adjusts your plan automatically. It is general information and does not replace advice from a doctor or a qualified trainer.")
        Bullet("Stop on sharp pain. Avoid training with persistent joint pain.")
        Bullet("Chest pain, dizziness or sudden sharp pain: stop immediately and seek medical help. The app has a \"Warning signs\" button in every session.")
        Bullet("Automatic changes are always explained and can be undone for 24 hours. You can pause automation at any time.")
        Bullet("Your training data stays on this device. It only leaves through exports you make or the reminder relay you configure.")
        Gap()
        Button(onClick = { scope.launch { c.settings.update { it.copy(disclaimerAccepted = true) } } }, modifier = Modifier.fillMaxWidth()) {
            Text("I understand")
        }
    }
}

@Composable
fun PackErrorScreen(issues: List<PackIssue>) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Gap(24)
            Text("The content pack is invalid", style = MaterialTheme.typography.headlineSmall)
            Text("Fix these problems in the pack files and rebuild the app.")
        }
        items(issues.sortedBy { it.severity != Severity.ERROR }) { i ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${i.file}${i.line?.let { ":$it" } ?: ""}", fontWeight = FontWeight.SemiBold,
                        color = if (i.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    i.field?.let { Text("Field: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(i.message)
                }
            }
        }
    }
}

/** Shown when the user reports a warning sign. No rescheduling happens here. */
@Composable
fun StopScreen(nav: NavController) {
    val ctx = LocalContext.current
    TimerService.stop(ctx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Gap(16)
        Text("Stop exercising now", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
        Text("Sit or lie down somewhere safe.", style = MaterialTheme.typography.titleMedium)
        Bullet("Chest pain or pressure, shortness of breath that does not settle, fainting or confusion: call your local emergency number now (for example 192 in Brazil, 911 in the US, 112 in Europe).")
        Bullet("Dizziness or sudden sharp pain: stop the session. If it does not go away within a few minutes, or comes back, seek medical help.")
        Bullet("Do not resume training today. Talk to a health professional before your next session.")
        Gap()
        Button(
            onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open phone dialer") }
        OutlinedButton(onClick = { nav.navigate("today") { popUpTo("today") { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Today")
        }
        Text("The session was stopped and saved. Your plan was not changed.", style = MaterialTheme.typography.bodySmall)
    }
}
