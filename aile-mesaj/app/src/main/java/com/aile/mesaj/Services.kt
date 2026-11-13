package com.aile.mesaj

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Notifier.channels(this)
        // Deletion runs even when the app and its service are closed.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.HOURS).build(),
        )
    }
}

class CleanupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        Hub.init(applicationContext)
        Hub.cleanup()
        return Result.success()
    }
}

/** Keeps Bluetooth and internet listening while the app is in the background. */
class MessagingService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, Notifier.service(this), type)
        if (Hub.config.value == null) { stopSelf(); return }
        Hub.serviceRunning = true
        Hub.startTransports(this)
        job = Hub.scope.launch {
            while (isActive) {
                Hub.cleanup()
                delay(5 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        if (Hub.serviceRunning) {
            Hub.serviceRunning = false
            Hub.stopTransports()
        }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        Hub.init(c)
        if (Hub.keepRunning.value) Hub.startService(c)
    }
}
