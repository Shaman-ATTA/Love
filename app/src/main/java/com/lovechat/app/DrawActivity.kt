package com.lovechat.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.ByteArrayOutputStream

class DrawView(c: Context) : View(c) {
    class Stroke(val path: Path, val paint: Paint)
    val strokes = mutableListOf<Stroke>()
    var color = Color.parseColor("#E91E63"); var width = 12f; var eraser = false
    private var cur: Path? = null
    override fun onDraw(cv: Canvas) { cv.drawColor(Color.WHITE); strokes.forEach { cv.drawPath(it.path, it.paint) } }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { cur = Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y) }
                strokes.add(Stroke(cur!!, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND; this.color = if (eraser) Color.WHITE else this@DrawView.color
                    strokeWidth = if (eraser) width * 3 else width * resources.displayMetrics.density / 2 })) }
            MotionEvent.ACTION_MOVE -> cur?.lineTo(e.x, e.y)
            MotionEvent.ACTION_UP -> cur = null
        }
        invalidate(); return true
    }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1); invalidate() }
    fun clear() { strokes.clear(); invalidate() }
    fun export(): ByteArray {
        val scale = minOf(1f, 600f / maxOf(width(), height()))
        val b = Bitmap.createBitmap((width() * scale).toInt().coerceAtLeast(1), (height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.scale(scale, scale); draw(cv)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    private fun width() = getWidth().toFloat(); private fun height() = getHeight().toFloat()
}

class DrawActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val d = resources.displayMetrics.density; fun dp(v: Int) = (v * d).toInt()
        val dv = DrawView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#FFF0F5")); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        root.addView(TextView(this).apply { text = "🎨 Нарисуй что-нибудь"; textSize = 20f; setTextColor(Color.parseColor("#E91E63")); typeface = Typeface.DEFAULT_BOLD })
        root.addView(dv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val colors = LinearLayout(this)
        listOf("#E91E63", "#F44336", "#FF9800", "#FFEB3B", "#4CAF50", "#2196F3", "#9C27B0", "#000000").forEach { hex ->
            colors.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor(hex)); setStroke(dp(2), Color.WHITE) }
                setOnClickListener { dv.color = Color.parseColor(hex); dv.eraser = false; Toast.makeText(this@DrawActivity, "Цвет выбран", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(colors) })
        root.addView(SeekBar(this).apply { max = 60; progress = 12; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, u: Boolean) { dv.width = v.coerceAtLeast(2).toFloat() }
            override fun onStartTrackingTouch(sb: SeekBar) {}; override fun onStopTrackingTouch(sb: SeekBar) {} }) })
        val row = LinearLayout(this)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; textSize = 13f; setOnClickListener { f() } }
        row.addView(btn("↶ Отменить") { dv.undo() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("🧽 Ластик") { dv.eraser = !dv.eraser; Toast.makeText(this, if (dv.eraser) "Ластик" else "Кисть", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("🗑 Очистить") { dv.clear() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(Button(this).apply { text = "Отправить рисунок ❤"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f; setBackgroundResource(R.drawable.btn_bg)
            setOnClickListener {
                if (dv.strokes.isEmpty()) return@setOnClickListener
                isEnabled = false; text = "Отправляю…"
                val png = dv.export()
                Thread { val err = Core.sendImage(this@DrawActivity, png); runOnUiThread {
                    isEnabled = true; text = "Отправить рисунок ❤"
                    if (err == null) { Toast.makeText(this@DrawActivity, "Рисунок отправлен 💕", Toast.LENGTH_SHORT).show(); finish() }
                    else AlertDialog.Builder(this@DrawActivity).setTitle("Не удалось отправить").setMessage(err).setPositiveButton("OK", null).show() } }.start()
            } }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        setContentView(root)
    }
}
