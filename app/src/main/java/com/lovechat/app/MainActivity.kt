package com.lovechat.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { render() } }
    private val pink = Color.parseColor("#E91E63"); private val dark = Color.parseColor("#4A0E2A")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.parseColor("#FFC2D4")
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#FFF0F5")); addView(root) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, IntentFilter("com.lovechat.app.NEW"), RECEIVER_NOT_EXPORTED)
        else registerReceiver(rx, IntentFilter("com.lovechat.app.NEW"))
        render(); ListenService.start(this)
        Thread { try {
            if (!Core.streamHealthy(this)) Core.poll(this, false)       // fallback only when the stream is down
            Core.sendPresence(this, true)                               // rate-limited inside (max once / 10 min)
            if (Core.outboxSize(this) > 0) Core.flushOutbox(this)
        } catch (_: Exception) {}; runOnUiThread { render() } }.start()
    }
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); h.postDelayed(this, 30000) } }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick); try { unregisterReceiver(rx) } catch (_: Exception) {} }
    override fun onPostResume() { super.onPostResume(); h.removeCallbacks(tick); h.postDelayed(tick, 30000) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float = 16f, color: Int = dark, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, dp(6)) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f; setBackgroundResource(R.drawable.btn_bg)
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) }; setOnClickListener { onClick() } }
    private fun field(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint; setText(value); setBackgroundResource(R.drawable.field_bg); setPadding(dp(14), dp(12), dp(14), dp(12)); setTextColor(dark)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } }
    private fun card(vararg vs: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_bg); setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }; vs.forEach { addView(it) } }

    private fun render() {
        root.removeAllViews()
        root.addView(text("💌 Love Chat", 28f, pink, true).apply { gravity = Gravity.CENTER })
        val p = Core.prefs(this); val code = Core.code(this)
        if (code == null) { renderPair(); return }
        Core.codeCache = code
        val conn = p.getBoolean("conn", false); val seen = p.getLong("seen", 0)
        val ago = (System.currentTimeMillis() - seen) / 60000
        val partner = when { seen == 0L -> "⚪ Партнёр ещё не подключился"
            ago < 75 -> "🟢 Партнёр на связи" + (if (ago >= 2) " (сигнал $ago мин назад)" else "")
            ago < 1440 -> "🟡 Партнёр был(а) в сети ${ago / 60} ч назад"
            else -> "⚪ Партнёр был(а) в сети ${ago / 1440} дн назад" }
        root.addView(card(text(if (conn) "🟢 Ты подключён(а)" else "🔴 Нет связи с сервером (переподключаюсь…)", 14f, dark, true),
            text(partner, 14f, dark, true)))

        root.addView(card(
            text("Сообщение от " + (p.getString("partnerName", "").takeUnless { it.isNullOrBlank() } ?: "любимого человека"), 13f),
            text(p.getString("inText", null) ?: "Пока нет сообщений ❤", 22f, dark, true),
            ImageView(this).apply { val b = if (p.getString("inKind", "t") == "img") Core.loadImg(this@MainActivity, 900) else null
                if (b != null) { setImageBitmap(b); adjustViewBounds = true; setBackgroundColor(Color.WHITE) } else visibility = View.GONE },
            text(Core.fmtTime(p.getLong("inTime", 0)), 12f)))
        val msg = field("Напиши что-нибудь тёплое…").apply { minLines = 2 }
        root.addView(msg)
        root.addView(button("Отправить ❤") {
            val t = msg.text.toString().trim(); if (t.isEmpty()) return@button
            Thread { val err = Core.send(this, t); runOnUiThread {
                if (err == null) { Toast.makeText(this, "Отправлено 💕", Toast.LENGTH_SHORT).show(); render() }
                else if (err.startsWith("QUEUED:")) { Toast.makeText(this, "Сервер занят — отправится чуть позже автоматически ⏳", Toast.LENGTH_LONG).show(); render() }
                else android.app.AlertDialog.Builder(this).setTitle("Не удалось отправить").setMessage(err).setPositiveButton("OK", null).show() } }.start()
        })
        root.addView(button("🎨 Нарисовать") { startActivity(Intent(this, DrawActivity::class.java)) })
        Core.outboxSize(this).let { n -> if (n > 0) root.addView(text("⏳ В очереди: $n — отправится чуть позже автоматически" + (p.getString("outErr", null)?.let { "\n($it)" } ?: ""), 13f, pink)) }
        p.getString("lastSent", null)?.let { root.addView(text("Ты отправил(а): «$it» · ${Core.fmtTime(p.getLong("lastSentTime", 0))}", 13f)) }

        // Permissions / setup checklist
        if (!Settings.canDrawOverlays(this)) {
            root.addView(text("⚠ Чтобы сообщения всплывали поверх любых приложений, разреши «Поверх других приложений»", 14f, pink))
            root.addView(button("Разрешить показ поверх приложений") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            root.addView(text("⚠ Отключи экономию батареи для приложения, иначе телефон может «усыпить» его и сообщения будут приходить с задержкой", 14f, pink))
            root.addView(button("Отключить экономию батареи") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
        }
        if (isMiui()) {
            root.addView(text("📱 Xiaomi / Redmi / POCO: чтобы сообщения и рисунки появлялись, когда приложение закрыто:\n" +
                "1) «Другие разрешения» → включи «Отображать всплывающие окна, когда запущено в фоновом режиме» и «Всплывающие окна» (Поверх других приложений).\n" +
                "2) Включи «Автозапуск».\n3) Контроль активности (Батарея) → «Нет ограничений».\n4) В списке недавних приложений потяни карточку Love Chat вниз и закрепи замком.", 13f, pink))
            root.addView(button("Открыть «Другие разрешения» (MIUI)") { openMiuiPerms() })
            root.addView(button("Открыть «Автозапуск» (MIUI)") { openMiuiAutostart() })
        }
        if (Settings.canDrawOverlays(this)) root.addView(button("Проверить всплывающее окно") { Core.showOverlay(this, "Так будут выглядеть сообщения 💖") })

        root.addView(text("\nДобавь виджет: долгое нажатие на главном экране → Виджеты → Love Chat", 13f))
        val name = field("Твоё имя (видно партнёру)", p.getString("name", "") ?: "")
        root.addView(name)
        root.addView(button("Сохранить имя") { p.edit().putString("name", name.text.toString().trim()).apply(); Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show() })
        root.addView(text("Ваш секретный код (у обоих одинаковый): $code", 15f, dark, true).apply { setTextIsSelectable(true) })
        root.addView(button("Поделиться кодом") { shareCode(code) })
        root.addView(button("Скопировать код") { copyCode(code) })
        root.addView(button("Отключиться / сменить код") {
            p.edit().remove("code").remove("lastId").remove("lastTime").remove("seenIds").remove("outbox").remove("seen").remove("lastPres").remove("inText").remove("inTime").remove("partnerName").remove("lastSent").apply()
            stopService(Intent(this, ListenService::class.java)); Core.updateWidgets(this); render() })
    }

    private fun renderPair() {
        root.addView(text("Оба вводят один и тот же код.\n\n1. Один нажимает «Создать новый код» и отправляет его второму («Поделиться» или «Скопировать»).\n2. Оба вводят этот же код и нажимают «Подключиться ❤».", 15f))
        val f = field("Секретный код, например ABCD-EFGH-JKLM")
        root.addView(f)
        root.addView(button("Создать новый код") { f.setText(Core.genCode()) })
        root.addView(button("Поделиться кодом") {
            if (f.text.toString().isBlank()) f.setText(Core.genCode())
            shareCode(f.text.toString().trim()) })
        root.addView(button("Скопировать код") {
            if (f.text.toString().isBlank()) f.setText(Core.genCode())
            copyCode(f.text.toString().trim()) })
        root.addView(button("Подключиться ❤") {
            val c = Core.norm(f.text.toString()); if (c.length < 8) { Toast.makeText(this, "Код слишком короткий (минимум 8 символов)", Toast.LENGTH_SHORT).show(); return@button }
            Core.prefs(this).edit().putString("code", c).putLong("pairedAt", System.currentTimeMillis() / 1000 - 3600).remove("lastId").remove("lastTime").remove("seenIds").remove("seen").remove("lastPres").apply()
            ListenService.restart(this); Core.updateWidgets(this); render() })
    }

    private fun isMiui() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
    private fun appDetails() = try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    private fun openMiuiPerms() {
        val tries = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity").putExtra("extra_pkgname", packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName))
        for (i in tries) try { startActivity(i); return } catch (_: Exception) {}
        appDetails()
    }
    private fun openMiuiAutostart() {
        try { startActivity(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) }
        catch (_: Exception) { appDetails() }
    }
    private fun shareCode(c: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Мой код для Love Chat 💌: $c\nВведи его в приложении и нажми «Подключиться».")
            startActivity(Intent.createChooser(send, "Поделиться кодом"))
        } catch (e: Throwable) { Toast.makeText(this, "Не удалось открыть «Поделиться», код скопирован", Toast.LENGTH_LONG).show(); copyCode(c) }
    }
    private fun copyCode(c: String) {
        try { getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Love Chat код", c))
            Toast.makeText(this, "Код скопирован: $c", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) { Toast.makeText(this, "Не удалось скопировать: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
