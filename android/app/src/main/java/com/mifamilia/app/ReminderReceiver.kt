package com.mifamilia.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Fires the AlarmManager alarm scheduled by [SyncService.scheduleReminders]
 * from the retained `<room>/rem` snapshot, so reminders ring while the app
 * and the service are closed.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: ""
        if (text.isBlank()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_REM, "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
                    .apply { enableVibration(true) }
            )
        }
        val from = intent.getStringExtra("from") ?: ""
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_REM)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (from.isBlank()) "Recordatorio" else "Recordatorio de $from")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = intent.getStringExtra("id") ?: "rem"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id.hashCode(), n)
    }

    companion object {
        const val CH_REM = "nexo_rem"
    }
}
