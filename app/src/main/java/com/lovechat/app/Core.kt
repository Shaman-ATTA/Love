package com.lovechat.app

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.view.*
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Core {
    const val SERVER = "https://ntfy.sh"
    const val CH_SERVICE = "listen"; const val CH_MSG = "msg"

    fun prefs(c: Context) = c.getSharedPreferences("lc", Context.MODE_PRIVATE)
    fun code(c: Context) = prefs(c).getString("code", null)
    fun myId(c: Context): String {
        val p = prefs(c); var id = p.getString("me", null)
        if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("me", id).apply() }
        return id
    }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun topic(code: String) = "lovechat-" + hex(sha("topic:" + norm(code))).take(40)
    private fun key(code: String) = SecretKeySpec(sha("key:" + norm(code)), "AES")
    fun norm(code: String) = code.trim().uppercase().replace(" ", "")

    fun genCode(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; val r = SecureRandom()
        return (1..12).map { a[r.nextInt(a.length)] }.joinToString("").chunked(4).joinToString("-")
    }

    fun encrypt(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(code: String, b64: String): String? = try {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    } catch (e: Exception) { null }

    class Pub(val ok: Boolean, val retry: Boolean, val msg: String)
    @Volatile var codeCache: String? = null

    /** Low-level publish. Blocking. Respects server 429 back-off. */
    fun publish(c: Context, body: ByteArray, headers: Map<String, String>, put: Boolean = false): Pub {
        val code0 = code(c) ?: return Pub(false, false, "Нет кода")
        val p = prefs(c); val now = System.currentTimeMillis()
        val blocked = p.getLong("blockedUntil", 0)
        if (blocked > now) return Pub(false, true, "Сервер просил подождать ещё ${(blocked - now) / 1000 + 1} с")
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$SERVER/${topic(code0)}").openConnection() as HttpURLConnection
            conn.requestMethod = if (put) "PUT" else "POST"; conn.doOutput = true
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("User-Agent", "LoveChat/1.4")
            conn.setRequestProperty("Content-Type", if (put) "application/octet-stream" else "text/plain")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body) }
            val rc = conn.responseCode
            if (rc in 200..299) { try { conn.inputStream.use { it.readBytes() } } catch (_: Exception) {}
                p.edit().putLong("penalty", 0).apply(); Pub(true, false, "") }
            else {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (e: Exception) { null } ?: ""
                if (rc == 429) {
                    val pen = (p.getLong("penalty", 0).coerceAtLeast(30_000L) * 2).coerceAtMost(15 * 60_000L)
                    p.edit().putLong("penalty", pen).putLong("blockedUntil", now + pen).apply()
                    Pub(false, true, "Лимит сервера ntfy.sh (HTTP 429). Повторю автоматически. $err")
                } else Pub(false, rc >= 500, "Сервер ответил HTTP $rc: $err")
            }
        } catch (e: java.net.UnknownHostException) { Pub(false, true, "Нет доступа к ntfy.sh (DNS)")
        } catch (e: java.io.IOException) { Pub(false, true, "Нет связи: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } catch (e: Exception) { Pub(false, false, "${e.javaClass.simpleName}: ${e.message}")
        } finally { conn?.disconnect() }
    }

    private fun meta(c: Context, kind: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("s", myId(c)).put("k", kind).put("n", prefs(c).getString("name", "")).apply(extra).toString()

    private fun pubText(c: Context, text: String): Pub {
        val code = code(c) ?: return Pub(false, false, "Сначала введи код")
        return publish(c, encrypt(code, meta(c, "t") { put("t", text) }).toByteArray(), mapOf("Firebase" to "no"))
    }
    private fun pubImage(c: Context, png: ByteArray): Pub {
        val code = code(c) ?: return Pub(false, false, "Сначала введи код")
        val enc = Base64.decode(encrypt(code, Base64.encodeToString(png, Base64.NO_WRAP)), Base64.NO_WRAP)
        return publish(c, enc, mapOf("Filename" to "d.bin", "Message" to encrypt(code, meta(c, "img")), "Firebase" to "no"), put = true)
    }

    // ---------- outbox (queued sends) ----------
    private val outLock = Any()
    private fun outbox(c: Context) = try { org.json.JSONArray(prefs(c).getString("outbox", "[]")) } catch (e: Exception) { org.json.JSONArray() }
    fun outboxSize(c: Context) = outbox(c).length()
    private fun enqueue(c: Context, item: JSONObject) = synchronized(outLock) {
        val a = outbox(c); a.put(item); prefs(c).edit().putString("outbox", a.toString()).apply() }

    /** Result: null = sent, "QUEUED:..." = will retry automatically, else hard error. */
    fun send(c: Context, text: String): String? {
        if (outboxSize(c) > 0) { enqueue(c, JSONObject().put("k", "t").put("t", text)); flushOutbox(c); return if (outboxSize(c) == 0) null else "QUEUED:в очереди" }
        val r = pubText(c, text)
        if (r.ok) { sent(c, text); return null }
        if (r.retry) { enqueue(c, JSONObject().put("k", "t").put("t", text)); return "QUEUED:" + r.msg }
        return r.msg
    }
    fun sendImage(c: Context, png: ByteArray): String? {
        val r = if (outboxSize(c) > 0) Pub(false, true, "в очереди") else pubImage(c, png)
        if (r.ok) { sent(c, "🎨 рисунок"); return null }
        if (r.retry) {
            val f = java.io.File(c.filesDir, "out_${System.currentTimeMillis()}.png"); f.writeBytes(png)
            enqueue(c, JSONObject().put("k", "img").put("f", f.path)); return "QUEUED:" + r.msg
        }
        return r.msg
    }
    private fun sent(c: Context, what: String) {
        prefs(c).edit().putString("lastSent", what).putLong("lastSentTime", System.currentTimeMillis()).apply(); notifyUi(c) }

    /** Try to send queued items in order; stops at first retryable failure. Blocking. */
    fun flushOutbox(c: Context) = synchronized(outLock) {
        while (true) {
            val a = outbox(c); if (a.length() == 0) break
            val it = a.getJSONObject(0)
            val r = if (it.optString("k") == "img") {
                val f = java.io.File(it.optString("f")); if (!f.exists()) Pub(true, false, "") else pubImage(c, f.readBytes())
            } else pubText(c, it.optString("t"))
            if (!r.ok && r.retry) { prefs(c).edit().putString("outErr", r.msg).apply(); notifyUi(c); break }
            if (r.ok) sent(c, if (it.optString("k") == "img") "🎨 рисунок" else it.optString("t"))
            if (it.optString("k") == "img") java.io.File(it.optString("f")).delete()
            val b = org.json.JSONArray(); for (i in 1 until a.length()) b.put(a.get(i))
            prefs(c).edit().putString("outbox", b.toString()).remove("outErr").apply(); notifyUi(c)
        }
    }

    // ---------- presence (cheap: Cache: no, rate-limited) ----------
    const val PRESENCE_MIN_GAP = 10 * 60_000L
    const val ONLINE_WINDOW = 75 * 60_000L
    /** ask=true asks the partner to answer. Skips if we published presence recently (unless force for a reply). */
    fun sendPresence(c: Context, ask: Boolean, minGap: Long = PRESENCE_MIN_GAP) {
        val p = prefs(c); val now = System.currentTimeMillis()
        if (code(c) == null || now - p.getLong("lastPres", 0) < minGap) return
        p.edit().putLong("lastPres", now).apply()
        val code = code(c)!!
        publish(c, encrypt(code, meta(c, "p") { if (ask) put("q", 1) }).toByteArray(), mapOf("Cache" to "no", "Firebase" to "no"))
    }

    fun notifyUi(c: Context) = c.sendBroadcast(Intent("com.lovechat.app.NEW").setPackage(c.packageName))
    fun setConnected(c: Context, v: Boolean) {
        val p = prefs(c); val changed = p.getBoolean("conn", false) != v
        p.edit().putBoolean("conn", v).putLong("connAt", System.currentTimeMillis()).apply()
        if (changed) notifyUi(c)
    }
    fun streamHealthy(c: Context): Boolean { val p = prefs(c)
        return p.getBoolean("conn", false) && System.currentTimeMillis() - p.getLong("connAt", 0) < 3 * 60_000L }

    fun imgFile(c: Context) = java.io.File(c.filesDir, "in.png")
    fun loadImg(c: Context, maxPx: Int): android.graphics.Bitmap? {
        val f = imgFile(c); if (!f.exists()) return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var ss = 1; while (o.outWidth / ss > maxPx) ss *= 2
        return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
    }

    fun sinceParam(c: Context): String {
        val p = prefs(c); val t = p.getLong("lastTime", 0)
        return if (t > 0) t.toString() else p.getLong("pairedAt", System.currentTimeMillis() / 1000).toString()
    }
    private fun seenIds(c: Context) = (prefs(c).getString("seenIds", "") ?: "").split(",").filter { it.isNotEmpty() }
    private fun markSeen(c: Context, id: String, time: Long) {
        val l = (seenIds(c) + id).takeLast(40); val p = prefs(c)
        p.edit().putString("seenIds", l.joinToString(",")).putLong("lastTime", maxOf(p.getLong("lastTime", 0), time)).apply()
    }

    /** Handle one ntfy JSON line. Thread-safe, deduplicated. Returns true if a new partner item was stored. */
    @Synchronized fun handleLine(c: Context, line: String, notifyUser: Boolean): Boolean {
        val code = code(c) ?: return false
        val o = try { JSONObject(line) } catch (e: Exception) { return false }
        when (o.optString("event")) { "open", "keepalive" -> { setConnected(c, true); return false }; "message" -> {}; else -> return false }
        val id = o.optString("id"); if (id.isEmpty() || id in seenIds(c)) return false
        val time = o.optLong("time", System.currentTimeMillis() / 1000)
        val m = decrypt(code, o.optString("message"))?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val p = prefs(c)
        if (m == null) { markSeen(c, id, time); return false } // foreign/undecryptable
        val kind = m.optString("k", "t"); val mine = m.optString("s") == myId(c)
        if (kind == "p") { // presence is not cached by the server; don't move the cursor
            if (!mine) {
                p.edit().putLong("seen", System.currentTimeMillis()).putString("partnerName", m.optString("n")).apply(); notifyUi(c)
                if (m.optInt("q") == 1) Thread { sendPresence(c, false, 3 * 60_000L) }.start()
            }
            return false
        }
        if (mine) { markSeen(c, id, time); return false }
        val ed = p.edit().putLong("inTime", time * 1000).putString("partnerName", m.optString("n"))
            .putLong("seen", maxOf(p.getLong("seen", 0), time * 1000))
        val text: String
        if (kind == "img") {
            val url = o.optJSONObject("attachment")?.optString("url")
            if (url.isNullOrEmpty()) { markSeen(c, id, time); return false }
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                if (conn.responseCode != 200) { conn.disconnect(); markSeen(c, id, time); return false } // expired attachment
                val enc = conn.inputStream.use { it.readBytes() }; conn.disconnect()
                val b64 = decrypt(code, Base64.encodeToString(enc, Base64.NO_WRAP)) ?: run { markSeen(c, id, time); return false }
                val tmp = java.io.File(c.filesDir, "in.tmp"); tmp.writeBytes(Base64.decode(b64, Base64.NO_WRAP)); tmp.renameTo(imgFile(c))
            } catch (e: Exception) { return false } // network error: not marked, will retry on next poll/stream
            text = "🎨 Рисунок"; ed.putString("inKind", "img")
        } else { text = m.optString("t"); ed.putString("inKind", "t") }
        ed.putString("inText", text).apply()
        markSeen(c, id, time)
        updateWidgets(c); notifyUi(c)
        if (notifyUser) alert(c, text)
        return true
    }

    /** Blocking poll (fallback only). */
    fun poll(c: Context, notifyUser: Boolean) {
        val code = code(c) ?: return
        if (prefs(c).getLong("blockedUntil", 0) > System.currentTimeMillis()) return
        try {
            val conn = URL("$SERVER/${topic(code)}/json?poll=1&since=${sinceParam(c)}").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            if (conn.responseCode == 429) { prefs(c).edit().putLong("blockedUntil", System.currentTimeMillis() + 120_000).apply(); conn.disconnect(); return }
            val lines = conn.inputStream.bufferedReader().readLines(); conn.disconnect()
            for (l in lines) handleLine(c, l, notifyUser)
        } catch (e: Exception) {}
    }

    fun fmtTime(ms: Long): String {
        if (ms == 0L) return ""
        val now = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
        val same = now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR) && now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
        return SimpleDateFormat(if (same) "'сегодня в' HH:mm" else "d MMM, HH:mm", Locale("ru")).format(Date(ms))
    }

    fun openAppPI(c: Context) = PendingIntent.getActivity(c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun updateWidgets(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, LoveWidget::class.java))
        val p = prefs(c)
        val rv = RemoteViews(c.packageName, R.layout.widget)
        val name = p.getString("partnerName", "")
        rv.setTextViewText(R.id.w_title, if (name.isNullOrBlank()) "💌 Love Chat" else "💌 $name")
        rv.setTextViewText(R.id.w_msg, p.getString("inText", null) ?: if (code(c) == null) "Открой приложение и введи код" else "Пока нет сообщений ❤")
        rv.setTextViewText(R.id.w_time, fmtTime(p.getLong("inTime", 0)))
        val bmp = if (p.getString("inKind", "t") == "img") loadImg(c, 400) else null
        if (bmp != null) { rv.setImageViewBitmap(R.id.w_img, bmp); rv.setViewVisibility(R.id.w_img, View.VISIBLE); rv.setViewVisibility(R.id.w_msg, View.INVISIBLE) }
        else { rv.setViewVisibility(R.id.w_img, View.GONE); rv.setViewVisibility(R.id.w_msg, View.VISIBLE) }
        rv.setOnClickPendingIntent(R.id.root, openAppPI(c))
        mgr.updateAppWidget(ids, rv)
    }

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Фоновое подключение", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Новые сообщения", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }

    fun alert(c: Context, text: String) {
        val overlayShown = Settings.canDrawOverlays(c) && showOverlay(c, text)
        channels(c)
        val n = Notification.Builder(c, CH_MSG).setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("💌 " + (prefs(c).getString("partnerName", "").takeUnless { it.isNullOrBlank() } ?: "Новое сообщение"))
            .setContentText(text)
            .setStyle(if (prefs(c).getString("inKind", "t") == "img") loadImg(c, 800)?.let { Notification.BigPictureStyle().bigPicture(it) } ?: Notification.BigTextStyle().bigText(text) else Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppPI(c)).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (overlayShown) n.setOnlyAlertOnce(true)
        try { c.getSystemService(NotificationManager::class.java).notify(2, n.build()) } catch (e: Exception) {}
    }

    private var current: View? = null
    fun showOverlay(c: Context, text: String): Boolean {
        val app = c.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = app.getSystemService(WindowManager::class.java)
                current?.let { try { wm.removeView(it) } catch (_: Exception) {} }
                val v = LayoutInflater.from(app).inflate(R.layout.overlay, null)
                v.findViewById<TextView>(R.id.o_msg).text = text
                if (prefs(app).getString("inKind", "t") == "img" && text.startsWith("🎨")) loadImg(app, 600)?.let {
                    v.findViewById<android.widget.ImageView>(R.id.o_img).apply { setImageBitmap(it); visibility = View.VISIBLE } }
                val name = prefs(app).getString("partnerName", "")
                if (!name.isNullOrBlank()) v.findViewById<TextView>(R.id.o_title).text = "💌 $name"
                val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = 60 }
                val dismiss = { try { wm.removeView(v) } catch (_: Exception) {}; if (current === v) current = null }
                var downX = 0f; var downY = 0f
                v.setOnTouchListener { view, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                        MotionEvent.ACTION_MOVE -> { view.translationX = e.rawX - downX; view.alpha = 1f - Math.min(1f, Math.abs(view.translationX) / 600f) }
                        MotionEvent.ACTION_UP -> {
                            val dx = e.rawX - downX; val dy = e.rawY - downY
                            if (Math.abs(dx) > 150 || dy < -80) dismiss()
                            else if (Math.abs(dx) < 20 && Math.abs(dy) < 20) { dismiss(); try { openAppPI(app).send() } catch (_: Exception) {} }
                            else { view.translationX = 0f; view.alpha = 1f }
                        }
                    }
                    true
                }
                wm.addView(v, lp); current = v
                v.alpha = 0f; v.animate().alpha(1f).setDuration(450).start()
                v.postDelayed({ v.animate().alpha(0f).setDuration(300).withEndAction { dismiss() }.start() }, if (text.startsWith("🎨")) 14000L else 10000L)
            } catch (e: Exception) {}
        }
        return true
    }
}
