package com.lovechat.app

import android.app.*
import android.app.job.*
import android.appwidget.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import java.net.HttpURLConnection
import java.net.URL

class ListenService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Core.channels(this)
        val n = Notification.Builder(this, Core.CH_SERVICE).setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Love Chat на связи ❤").setContentText("Жду сообщения от любимого человека")
            .setContentIntent(Core.openAppPI(this)).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (Core.code(this) == null) { stopSelf(); return START_NOT_STICKY }
        if (thread?.isAlive != true) { running = true; thread = Thread { loop() }.also { it.start() }
            Thread { while (running) { try { Thread.sleep(120000) } catch (e: InterruptedException) { break }
                if (running && Core.prefs(this).getBoolean("conn", false)) Core.sendPresence(this, false) } }.start() }
        return START_STICKY
    }
    private fun loop() {
        var backoff = 2000L
        while (running) {
            val code = Core.code(this) ?: break
            try {
                val conn = URL("${Core.SERVER}/${Core.topic(code)}/json?since=${Core.sinceParam(this)}").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 90000 // ntfy sends keepalive every ~45s
                conn.inputStream.bufferedReader().use { r ->
                    backoff = 2000L; Core.setConnected(this, true)
                    Thread { Core.sendPresence(this, true) }.start()
                    while (running) { val l = r.readLine() ?: break; if (Core.code(this) != code) break; Core.handleLine(this, l, true) }
                }
                conn.disconnect()
            } catch (e: Exception) {}
            Core.setConnected(this, false)
            if (!running) break
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            backoff = minOf(backoff * 2, 60000L)
        }
    }
    override fun onDestroy() { running = false; thread?.interrupt(); Core.setConnected(this, false); super.onDestroy() }

    companion object {
        fun start(c: Context) {
            if (Core.code(c) == null) return
            try { c.startForegroundService(Intent(c, ListenService::class.java)) } catch (e: Exception) {}
            PollJob.schedule(c)
        }
        fun restart(c: Context) { c.stopService(Intent(c, ListenService::class.java)); start(c) }
    }
}

class PollJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread { Core.poll(this, true); ListenService.start(this); jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
    companion object {
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            js.schedule(JobInfo.Builder(7, ComponentName(c, PollJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { ListenService.start(c); Core.updateWidgets(c) }
}

class LoveWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Core.updateWidgets(c)
        val pr = goAsync(); Thread { Core.poll(c, false); pr.finish() }.start()
    }
}
