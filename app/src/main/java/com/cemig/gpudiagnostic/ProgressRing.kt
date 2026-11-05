package com.cemig.gpudiagnostic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/** Circular progress with a percentage, completion check and optional activity marker. */
class ProgressRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var active = false
    private var completed = false
    private var failed = false
    fun update(fraction: Float, active: Boolean = false, completed: Boolean = false, failed: Boolean = false) {
        if (this.fraction == fraction.coerceIn(0f, 1f) && this.active == active && this.completed == completed && this.failed == failed) return
        this.fraction = fraction.coerceIn(0f, 1f); this.active = active; this.completed = completed; this.failed = failed
        contentDescription = if (completed) "Complete" else "${(this.fraction * 100).toInt()} percent${if (active) ", in progress" else ""}"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val stroke = width * 0.07f
        val bounds = RectF(stroke, stroke, width - stroke, height - stroke)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(222, 231, 225); canvas.drawOval(bounds, paint)
        paint.color = if (failed) Color.rgb(174, 49, 49) else Color.rgb(0, 132, 61)
        canvas.drawArc(bounds, -90f, fraction * 360f, false, paint)
        if (active && !completed) {
            paint.color = Color.rgb(77, 174, 114)
            canvas.drawArc(bounds, (SystemClock.uptimeMillis() % 2000) * 0.18f, 24f, false, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(22, 57, 41)
        paint.textAlign = Paint.Align.CENTER; paint.textSize = width * 0.23f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(if (completed) "✓" else "${(fraction * 100).toInt()}%", width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        if (active && isShown) postInvalidateDelayed(40)
    }
}
