package com.veymur.recorder.core

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager

/** Uses OS channels and notification permission; never bypasses silent mode or DND. */
internal class RangeAlertNotifications(private val context: Context, private val soundEnabled: Boolean, private val vibrationEnabled: Boolean) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channelId = "range-${if (soundEnabled) "sound" else "quiet"}-${if (vibrationEnabled) "vibration" else "still"}-v1"
    init {
        manager.createNotificationChannel(NotificationChannel(channelId, "User range alerts (${if (soundEnabled) "sound" else "quiet"}, ${if (vibrationEnabled) "vibration" else "no vibration"})", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(if (soundEnabled) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            enableVibration(vibrationEnabled)
            if (vibrationEnabled) vibrationPattern = longArrayOf(0, 150)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }
    fun status(): String = when {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "notification_permission_denied"
        !manager.areNotificationsEnabled() || manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE -> "notifications_disabled"
        else -> "enabled"
    }
    fun show(range: AlertRange) {
        if (status() != "enabled") return
        try {
            manager.notify(2, Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Heart rate range")
                .setContentText(range.label).setVisibility(Notification.VISIBILITY_SECRET)
                .setTimeoutAfter(10000).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Permission can change between the check and notify. */ }
    }
    fun clear() { manager.cancel(2) }
}
