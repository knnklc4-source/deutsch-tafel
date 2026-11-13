package com.aile.mesaj

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    private const val CH_SERVICE = "service"
    private const val CH_MSG = "messages"
    const val SERVICE_ID = 1

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Bağlantı", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Mesajlar", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun service(c: Context): Notification = NotificationCompat.Builder(c, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("Aile Mesaj açık")
        .setContentText("Yeni aile mesajları bekleniyor")
        .setOngoing(true)
        .setContentIntent(open(c))
        .build()

    @SuppressLint("MissingPermission")
    fun message(c: Context, m: Msg) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val public = NotificationCompat.Builder(c, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Aile Mesaj").setContentText("Yeni mesaj").build()
        val n = NotificationCompat.Builder(c, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(m.senderName.ifBlank { "Aile" })
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(open(c))
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(m.id.hashCode(), n) }
    }

    fun clearMessages(c: Context) {
        val nm = NotificationManagerCompat.from(c)
        nm.activeNotifications.filter { it.id != SERVICE_ID }.forEach { nm.cancel(it.id) }
    }
}
