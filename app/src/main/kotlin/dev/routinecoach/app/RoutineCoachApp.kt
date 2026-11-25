package dev.routinecoach.app

import android.app.Application
import android.content.Context
import dev.routinecoach.app.automation.Automation
import dev.routinecoach.app.backup.BackupManager
import dev.routinecoach.app.data.AppDatabase
import dev.routinecoach.app.data.PackRepository
import dev.routinecoach.app.data.SettingsStore
import dev.routinecoach.app.data.TrainingRepository
import dev.routinecoach.app.reminders.Background
import dev.routinecoach.app.reminders.Notifier
import dev.routinecoach.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val packs = PackRepository(context)
    val notifier = Notifier(context)
    val repo = TrainingRepository(db, packs, settings)
    val reminders = ReminderScheduler(context, repo, packs, settings, notifier)
    val automation = Automation(repo, packs, settings, notifier) { reminders.sync() }
    val backup = BackupManager(db)
}

class RoutineCoachApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.appScope.launch {
            container.packs.load()
            if (container.packs.current() != null) {
                container.automation.dailyCheck()
                container.reminders.sync()
            }
        }
        Background.schedule(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as RoutineCoachApp).container
