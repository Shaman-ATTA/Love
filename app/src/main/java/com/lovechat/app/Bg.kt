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
        try { if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n) }
        catch (e: Throwable) { android.util.Log.w("LoveChat", "startForeground failed", e); stopSelf(); return START_NOT_STICKY }
        if (Core.code(this) == null) { stopSelf(); return START_NOT_STICKY }
        if (thread?.isAlive != true) { running = true; thread = Thread { loop() }.also { it.start() }
            worker = Thread { work() }.also { it.start() } }
        return START_STICKY
    }
    private var worker: Thread? = null
    /** Slow housekeeping: hourly presence heartbeat + outbox retries. No other network use. */
    private fun work() {
        while (running) {
            try { Thread.sleep(30_000) } catch (e: InterruptedException) { break }
            if (!running) break
            try {
                val p = Core.prefs(this); val now = System.currentTimeMillis()
                if (Core.outboxSize(this) > 0 && p.getLong("blockedUntil", 0) <= now) Core.flushOutbox(this)
                if (Core.streamHealthy(this) && now - p.getLong("lastPres", 0) > 60 * 60_000L) Core.sendPresence(this, false)
            } catch (e: Exception) {}
        }
    }
    private fun loop() {
        var backoff = 10_000L
        while (running) {
            val code = Core.code(this) ?: break
            var opened = 0L
            try {
                val conn = URL("${Core.SERVER}/${Core.topic(code)}/json?since=${Core.sinceParam(this)}").openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 120_000 // ntfy keepalive ~45s
                conn.setRequestProperty("User-Agent", "LoveChat/1.4")
                val rc = conn.responseCode
                if (rc == 429) { backoff = maxOf(backoff, 120_000L); conn.disconnect(); throw java.io.IOException("429") }
                conn.inputStream.bufferedReader().use { r ->
                    opened = System.currentTimeMillis(); Core.setConnected(this, true)
                    Thread { try { Core.sendPresence(this, true); Core.flushOutbox(this) } catch (_: Exception) {} }.start()
                    while (running) { val l = r.readLine() ?: break; if (Core.code(this) != code) break; Core.handleLine(this, l, true) }
                }
                conn.disconnect()
            } catch (e: Exception) {}
            Core.setConnected(this, false)
            if (!running) break
            if (opened > 0 && System.currentTimeMillis() - opened > 60_000L) backoff = 10_000L
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            backoff = minOf(backoff * 2, 10 * 60_000L)
        }
    }
    override fun onDestroy() { running = false; thread?.interrupt(); worker?.interrupt(); Core.setConnected(this, false); super.onDestroy() }

    companion object {
        fun start(c: Context) {
            if (Core.code(c) == null) return
            try { c.startForegroundService(Intent(c, ListenService::class.java)) } catch (e: Throwable) { android.util.Log.w("LoveChat", "service start failed", e) }
            try { PollJob.schedule(c) } catch (e: Throwable) { android.util.Log.w("LoveChat", "job schedule failed", e) }
        }
        fun restart(c: Context) { try { c.stopService(Intent(c, ListenService::class.java)) } catch (e: Throwable) {}; start(c) }
    }
}

class PollJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread { try {
            if (!Core.streamHealthy(this)) Core.poll(this, true)   // fallback only
            if (Core.outboxSize(this) > 0) Core.flushOutbox(this)
            ListenService.start(this) } catch (_: Exception) {}
            jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
    companion object {
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(7) != null) return
            js.schedule(JobInfo.Builder(7, ComponentName(c, PollJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { try { ListenService.start(c); Core.updateWidgets(c) } catch (e: Throwable) {} }
}

class LoveWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        try { Core.updateWidgets(c) } catch (e: Throwable) {}
        ListenService.start(c)
    }
}
