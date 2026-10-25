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

    /** Low-level publish. Returns null on success or a human-readable error. Blocking. */
    fun publish(body: ByteArray, headers: Map<String, String>, put: Boolean = false): String? {
        val code0 = codeCache ?: return "Нет кода"
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$SERVER/${topic(code0)}").openConnection() as HttpURLConnection
            conn.requestMethod = if (put) "PUT" else "POST"; conn.doOutput = true
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("User-Agent", "LoveChat/1.1")
            conn.setRequestProperty("Content-Type", if (put) "application/octet-stream" else "text/plain")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body) }
            val rc = conn.responseCode
            if (rc in 200..299) null else {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (e: Exception) { null }
                "Сервер ответил HTTP $rc" + (if (rc == 429) " (слишком много запросов, подожди минуту)" else "") + (err?.let { ": $it" } ?: "")
            }
        } catch (e: java.net.UnknownHostException) { "Нет доступа к ntfy.sh (DNS): ${e.message}"
        } catch (e: java.net.SocketTimeoutException) { "Сервер не ответил вовремя (таймаут)"
        } catch (e: javax.net.ssl.SSLException) { "Ошибка TLS/SSL: ${e.message}"
        } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}"
        } finally { conn?.disconnect() }
    }
    @Volatile var codeCache: String? = null

    private fun meta(c: Context, kind: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("s", myId(c)).put("k", kind).put("n", prefs(c).getString("name", "")).apply(extra).toString()

    /** Send text. Returns null on success or error text. Blocking. */
    fun send(c: Context, text: String): String? {
        val code = code(c) ?: return "Сначала введи код"; codeCache = code
        val err = publish(encrypt(code, meta(c, "t") { put("t", text) }).toByteArray(), mapOf("Firebase" to "no"))
        if (err == null) prefs(c).edit().putString("lastSent", text).putLong("lastSentTime", System.currentTimeMillis()).apply()
        return err
    }

    /** Send drawing as encrypted attachment. */
    fun sendImage(c: Context, png: ByteArray): String? {
        val code = code(c) ?: return "Сначала введи код"; codeCache = code
        val encBytes = Base64.decode(encrypt(code, Base64.encodeToString(png, Base64.NO_WRAP)), Base64.NO_WRAP)
        val err = publish(encBytes, mapOf("Filename" to "d.bin", "Message" to encrypt(code, meta(c, "img")), "Firebase" to "no"), put = true)
        if (err == null) prefs(c).edit().putString("lastSent", "🎨 рисунок").putLong("lastSentTime", System.currentTimeMillis()).apply()
        return err
    }

    /** Presence ping, not stored on server (Cache: no). */
    fun sendPresence(c: Context, ask: Boolean) {
        val code = code(c) ?: return; codeCache = code
        publish(encrypt(code, meta(c, "p") { if (ask) put("q", 1) }).toByteArray(), mapOf("Cache" to "no", "Firebase" to "no"))
    }

    fun sinceParam(c: Context): String {
        val p = prefs(c); return p.getString("lastId", null) ?: (p.getLong("pairedAt", System.currentTimeMillis() / 1000).toString())
    }

    fun notifyUi(c: Context) = c.sendBroadcast(Intent("com.lovechat.app.NEW").setPackage(c.packageName))
    fun setConnected(c: Context, v: Boolean) {
        val p = prefs(c); if (p.getBoolean("conn", false) != v || v) { p.edit().putBoolean("conn", v).putLong("connAt", System.currentTimeMillis()).apply(); notifyUi(c) }
    }

    fun imgFile(c: Context) = java.io.File(c.filesDir, "in.png")
    fun loadImg(c: Context, maxPx: Int): android.graphics.Bitmap? {
        val f = imgFile(c); if (!f.exists()) return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var ss = 1; while (o.outWidth / ss > maxPx) ss *= 2
        return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
    }

    /** Handle one ntfy JSON line. Returns true if a new partner message was stored. */
    fun handleLine(c: Context, line: String, notifyUser: Boolean): Boolean {
        val code = code(c) ?: return false
        val o = try { JSONObject(line) } catch (e: Exception) { return false }
        when (o.optString("event")) { "open", "keepalive" -> { setConnected(c, true); return false }; "message" -> {}; else -> return false }
        val id = o.optString("id"); val p = prefs(c)
        if (id == p.getString("lastId", null)) return false
        val dec = decrypt(code, o.optString("message"))
        val m = dec?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val kind = m?.optString("k", "t") ?: "t"
        if (m != null && kind == "p") { // presence: never stored, don't move lastId
            if (m.optString("s") != myId(c)) {
                p.edit().putLong("seen", System.currentTimeMillis()).putString("partnerName", m.optString("n")).apply(); notifyUi(c)
                if (m.optInt("q") == 1) Thread { sendPresence(c, false) }.start()
            }
            return false
        }
        p.edit().putString("lastId", id).apply()
        if (m == null || m.optString("s") == myId(c)) return false
        val time = o.optLong("time", System.currentTimeMillis() / 1000) * 1000
        val ed = p.edit().putLong("inTime", time).putString("partnerName", m.optString("n")).putLong("seen", maxOf(p.getLong("seen", 0), time))
        val text: String
        if (kind == "img") {
            val url = o.optJSONObject("attachment")?.optString("url") ?: return false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                val enc = conn.inputStream.use { it.readBytes() }; conn.disconnect()
                val b64 = decrypt(code, Base64.encodeToString(enc, Base64.NO_WRAP)) ?: return false
                imgFile(c).writeBytes(Base64.decode(b64, Base64.NO_WRAP))
            } catch (e: Exception) { return false }
            text = "🎨 Рисунок"; ed.putString("inKind", "img")
        } else { text = m.optString("t"); ed.putString("inKind", "t") }
        ed.putString("inText", text).apply()
        updateWidgets(c); notifyUi(c)
        if (notifyUser) alert(c, text)
        return true
    }

    /** Blocking poll. */
    fun poll(c: Context, notifyUser: Boolean) {
        val code = code(c) ?: return
        try {
            val conn = URL("$SERVER/${topic(code)}/json?poll=1&since=${sinceParam(c)}").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            val lines = conn.inputStream.bufferedReader().readLines(); conn.disconnect()
            var changed = false
            for (l in lines) if (handleLine(c, l, false)) changed = true
            if (changed && notifyUser) prefs(c).getString("inText", null)?.let { alert(c, it) }
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
        if (bmp != null) { rv.setImageViewBitmap(R.id.w_img, bmp); rv.setViewVisibility(R.id.w_img, View.VISIBLE); rv.setViewVisibility(R.id.w_msg, View.GONE) }
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
                v.alpha = 0f; v.translationY = -100f; v.animate().alpha(1f).translationY(0f).setDuration(250).start()
                v.postDelayed({ v.animate().alpha(0f).setDuration(300).withEndAction { dismiss() }.start() }, 9000)
            } catch (e: Exception) {}
        }
        return true
    }
}
