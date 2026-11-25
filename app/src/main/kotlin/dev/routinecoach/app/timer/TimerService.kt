package dev.routinecoach.app.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.routinecoach.app.R
import dev.routinecoach.app.reminders.Notifier
import dev.routinecoach.core.pack.Cadence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TimerKind { REST, HOLD, CADENCE, WORK }

data class TimerState(
    val kind: TimerKind? = null,
    val label: String = "",
    val remainingSec: Int = 0,
    val totalSec: Int = 0,
    /** Cadence phase label ("Down", "Hold", "Up") for the metronome. */
    val phase: String = "",
    val reps: Int = 0,
    /** Incremented every time a countdown finishes, so the UI can react. */
    val finishedCount: Int = 0,
) {
    val running get() = kind != null
}

/**
 * Foreground service that runs rest, hold and cadence timers with sound and vibration. It holds a partial wake
 * lock while a timer runs, so timers keep working with the screen off.
 */
class TimerService : Service() {
    companion object {
        private val _state = MutableStateFlow(TimerState())
        val state: StateFlow<TimerState> = _state

        private const val ACTION_COUNTDOWN = "countdown"
        private const val ACTION_CADENCE = "cadence"
        private const val ACTION_STOP = "stop"

        fun countdown(context: Context, kind: TimerKind, seconds: Int, label: String) {
            start(context, Intent(context, TimerService::class.java).setAction(ACTION_COUNTDOWN)
                .putExtra("kind", kind.name).putExtra("seconds", seconds).putExtra("label", label))
        }

        fun cadence(context: Context, cadence: Cadence, label: String) {
            start(context, Intent(context, TimerService::class.java).setAction(ACTION_CADENCE)
                .putExtra("ecc", cadence.eccentricSec).putExtra("pb", cadence.pauseBottomSec)
                .putExtra("con", cadence.concentricSec).putExtra("pt", cadence.pauseTopSec).putExtra("label", label))
        }

        fun stop(context: Context) {
            if (_state.value.running) context.startService(Intent(context, TimerService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COUNTDOWN -> {
                val kind = TimerKind.valueOf(intent.getStringExtra("kind") ?: TimerKind.REST.name)
                val sec = intent.getIntExtra("seconds", 60)
                val label = intent.getStringExtra("label").orEmpty()
                goForeground(label)
                runCountdown(kind, sec, label)
            }
            ACTION_CADENCE -> {
                val c = Cadence(intent.getIntExtra("ecc", 2), intent.getIntExtra("pb", 0), intent.getIntExtra("con", 1), intent.getIntExtra("pt", 0))
                val label = intent.getStringExtra("label").orEmpty()
                goForeground(label)
                runCadence(c, label)
            }
            ACTION_STOP -> finish()
            else -> if (!_state.value.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(label: String) {
        val n = NotificationCompat.Builder(this, Notifier.CH_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label.ifBlank { "Timer running" })
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifier(this).openAppIntent("session"))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_TIMER, n, type)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "routinecoach:timer").apply { acquire(2 * 60 * 60 * 1000L) }
        }
    }

    private fun runCountdown(kind: TimerKind, seconds: Int, label: String) {
        job?.cancel()
        job = scope.launch {
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            var last = -1
            while (true) {
                val remaining = ((end - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                if (remaining != last) {
                    last = remaining
                    _state.value = _state.value.copy(kind = kind, label = label, remainingSec = remaining, totalSec = seconds, phase = "")
                    if (remaining in 1..3) beep(ToneGenerator.TONE_PROP_BEEP, 120)
                }
                if (remaining == 0) break
                delay(200)
            }
            beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            vibrate(longArrayOf(0, 400, 200, 400))
            _state.value = TimerState(finishedCount = _state.value.finishedCount + 1)
            finish()
        }
    }

    private fun runCadence(c: Cadence, label: String) {
        job?.cancel()
        job = scope.launch {
            var reps = 0
            val phases = listOf("Down" to c.eccentricSec, "Hold" to c.pauseBottomSec, "Up" to c.concentricSec, "Top" to c.pauseTopSec)
                .filter { it.second > 0 }
            while (true) {
                for ((name, secs) in phases) {
                    for (s in secs downTo 1) {
                        _state.value = _state.value.copy(kind = TimerKind.CADENCE, label = label, phase = name, remainingSec = s, totalSec = secs, reps = reps)
                        beep(if (s == secs) (if (name == "Up") ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP) else ToneGenerator.TONE_CDMA_PIP, if (s == secs) 150 else 60)
                        if (s == secs && name == "Up") vibrate(longArrayOf(0, 80))
                        delay(1000)
                    }
                }
                reps++
            }
        }
    }

    private fun beep(tone: Int, ms: Int) {
        runCatching { this.tone?.startTone(tone, ms) }
    }

    private fun vibrate(pattern: LongArray) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun finish() {
        job?.cancel()
        val finished = _state.value.finishedCount
        _state.value = TimerState(finishedCount = finished)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        tone?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
