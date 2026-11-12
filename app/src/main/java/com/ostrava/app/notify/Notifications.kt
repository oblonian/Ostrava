package com.ostrava.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ostrava.app.MainActivity
import com.ostrava.app.R
import com.ostrava.app.data.db.RankedEffort
import com.ostrava.app.domain.Insight
import com.ostrava.app.domain.InsightAction
import com.ostrava.app.domain.formatDuration

/** Notification channels and the notifications that aren't the live recording one. */
object Notifications {

    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ACHIEVEMENTS = "achievements"
    const val CHANNEL_REMINDERS = "reminders"

    /** Extras understood by MainActivity to open a specific place. */
    const val EXTRA_OPEN_ACTIVITY_ID = "open_activity_id"
    const val EXTRA_OPEN_DESTINATION = "open_destination"
    const val DESTINATION_RECORD = "record"
    const val DESTINATION_STATS = "stats"
    const val DESTINATION_PROFILE = "profile"

    private const val RECORD_NOTIFICATION_ID = 100
    private const val REMINDER_NOTIFICATION_ID = 101

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_TRACKING,
                    context.getString(R.string.tracking_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.tracking_channel_description) },
                NotificationChannel(
                    CHANNEL_ACHIEVEMENTS,
                    "Personal records",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "When an activity sets a new personal record" },
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    "Reminders and tips",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Occasional nudges about your streak, weekly goal and backups" },
            )
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun showPersonalRecord(context: Context, activityId: Long, title: String, records: List<RankedEffort>) {
        if (!canPost(context) || records.isEmpty()) return
        val headline = if (records.size == 1) "New personal record 🏆" else "${records.size} new personal records 🏆"
        val detail = records.joinToString(" · ") { "${it.label} in ${formatDuration(it.durationMillis)}" }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ACTIVITY_ID, activityId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ACHIEVEMENTS)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(headline)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n$title"))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(context, RECORD_NOTIFICATION_ID, intent))
            .build()
        post(context, RECORD_NOTIFICATION_ID, notification)
    }

    fun showReminder(context: Context, insight: Insight) {
        if (!canPost(context)) return
        val destination = when (insight.action) {
            InsightAction.RECORD -> DESTINATION_RECORD
            InsightAction.STATS -> DESTINATION_STATS
            InsightAction.BACKUP -> DESTINATION_PROFILE
            InsightAction.NONE -> null
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (destination != null) intent.putExtra(EXTRA_OPEN_DESTINATION, destination)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("${insight.emoji} ${insight.title}")
            .setContentText(insight.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(insight.body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(context, REMINDER_NOTIFICATION_ID, intent))
            .build()
        post(context, REMINDER_NOTIFICATION_ID, notification)
    }

    private fun pendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the post.
        }
    }
}
