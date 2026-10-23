package dev.proprio.wear.platform

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.proprio.wear.MainActivity
import dev.proprio.wear.ProprioApp
import dev.proprio.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (`foregroundServiceType="health"`) that keeps the process and the sensor
 * listeners alive while a session records. It owns only the notification and the partial wake lock;
 * the session itself lives in [dev.proprio.wear.session.RecordingController].
 *
 * Started and stopped by the controller (`ForegroundHost`). It is `START_NOT_STICKY`: if the system
 * kills the process the recording is over, the session files written so far stay readable, and
 * the next app start zips them (`TransferManager.recoverOrphans`) and sends them as an incomplete session.
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var scope: CoroutineScope? = null
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val graph = (application as ProprioApp).graph
        val ongoing = graph.strategy.current.ongoing
        val notification = buildNotification(this, "Starting...", ongoing)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proprio:recording").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS) // safety timeout; released in onDestroy
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val controller = graph.controller
        observer = s.launch {
            controller.state
                .map { st ->
                    if (st.recording) {
                        val sec = st.elapsedS.toInt()
                        val what = if (st.kind == dev.proprio.protocol.SessionKind.CALIBRATION) "Calibrating" else "Recording"
                        "$what %d:%02d".format(sec / 60, sec % 60)
                    } else {
                        "Starting..."
                    }
                }
                .distinctUntilChanged()
                .collect { text ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(this@RecordingService, text, ongoing))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        observer?.cancel()
        scope?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "recording"
        private const val MAX_WAKE_LOCK_MS = 4 * 60 * 60 * 1000L

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
            )
        }

        /**
         * The recording notification. With [ongoingActivity] the Wear OS Ongoing Activity API is attached
         * (category workout, touch intent = open the app, status = [text]); see the README for why.
         */
        fun buildNotification(context: Context, text: String, ongoingActivity: Boolean = false): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(ICON)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setContentIntent(open)
            if (ongoingActivity) {
                OngoingActivity.Builder(context.applicationContext, NOTIFICATION_ID, builder)
                    .setStaticIcon(ICON)
                    .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                    .setTouchIntent(open)
                    .setStatus(Status.Builder().addTemplate("#text#").addPart("text", Status.TextPart(text)).build())
                    .build()
                    .apply(context.applicationContext)
            }
            return builder.build()
        }

        private const val ICON = android.R.drawable.ic_menu_recent_history
    }
}
