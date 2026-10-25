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
        Thread { Core.poll(this, false); runOnUiThread { render() } }.start()
    }
    override fun onPause() { super.onPause(); try { unregisterReceiver(rx) } catch (_: Exception) {} }

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

        root.addView(card(
            text("Сообщение от " + (p.getString("partnerName", "").takeUnless { it.isNullOrBlank() } ?: "любимого человека"), 13f),
            text(p.getString("inText", null) ?: "Пока нет сообщений ❤", 22f, dark, true),
            text(Core.fmtTime(p.getLong("inTime", 0)), 12f)))
        val msg = field("Напиши что-нибудь тёплое…").apply { minLines = 2 }
        root.addView(msg)
        root.addView(button("Отправить ❤") {
            val t = msg.text.toString().trim(); if (t.isEmpty()) return@button
            Thread { val ok = Core.send(this, t); runOnUiThread {
                Toast.makeText(this, if (ok) "Отправлено 💕" else "Не удалось отправить. Проверь интернет", Toast.LENGTH_SHORT).show()
                if (ok) render() } }.start()
        })
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
        if (Settings.canDrawOverlays(this)) root.addView(button("Проверить всплывающее окно") { Core.showOverlay(this, "Так будут выглядеть сообщения 💖") })

        root.addView(text("\nДобавь виджет: долгое нажатие на главном экране → Виджеты → Love Chat", 13f))
        val name = field("Твоё имя (видно партнёру)", p.getString("name", "") ?: "")
        root.addView(name)
        root.addView(button("Сохранить имя") { p.edit().putString("name", name.text.toString().trim()).apply(); Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show() })
        root.addView(text("Ваш секретный код: $code", 13f))
        root.addView(button("Отключиться / сменить код") {
            p.edit().remove("code").remove("lastId").remove("inText").remove("inTime").remove("partnerName").remove("lastSent").apply()
            stopService(Intent(this, ListenService::class.java)); Core.updateWidgets(this); render() })
    }

    private fun renderPair() {
        root.addView(text("Соединитесь с любимым человеком по секретному коду. Один создаёт код и передаёт его второму, второй вводит тот же код.", 15f))
        val f = field("Секретный код, например ABCD-EFGH-JKLM")
        root.addView(f)
        root.addView(button("Создать новый код") { f.setText(Core.genCode()) })
        root.addView(button("Поделиться кодом") {
            val c = f.text.toString().trim(); if (c.isEmpty()) return@button
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Мой код для Love Chat 💌: $c"), "Поделиться")) })
        root.addView(button("Подключиться ❤") {
            val c = Core.norm(f.text.toString()); if (c.length < 8) { Toast.makeText(this, "Код слишком короткий (минимум 8 символов)", Toast.LENGTH_SHORT).show(); return@button }
            Core.prefs(this).edit().putString("code", c).putLong("pairedAt", System.currentTimeMillis() / 1000 - 3600).remove("lastId").apply()
            ListenService.restart(this); Core.updateWidgets(this); render() })
    }
}
