package com.example.maxassistant

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

// ✅ WAVE VIEW - short voice wave animation
// "Max" antē wave show avutundi, speaking time animate avutundi
class WaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private var amplitude = 0f    // Voice loudness
    private var phase = 0f        // Wave movement
    private var isActive = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val animRunnable = object : Runnable {
        override fun run() {
            phase += 0.15f
            invalidate()
            if (isActive) handler.postDelayed(this, 16) // 60fps
        }
    }

    // Called from onRmsChanged - voice amplitude update
    fun updateAmplitude(rms: Float) {
        amplitude = (rms / 10f).coerceIn(0f, 30f)
        if (!isActive) startWave()
    }

    fun startWave() {
        isActive = true
        handler.post(animRunnable)
    }

    fun stopWave() {
        isActive = false
        amplitude = 0f
        handler.removeCallbacks(animRunnable)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isActive && amplitude == 0f) return

        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f

        // ✅ SHORT wave - 3 bars style (like Jarvis)
        val barCount = 5
        val barWidth = w / (barCount * 2f)
        val spacing  = w / barCount

        for (i in 0 until barCount) {
            val x = spacing * i + spacing / 2f
            // Each bar height = sin wave based on position + phase
            val barH = (sin(phase + i * 1.2f) * amplitude * 1.5f + amplitude + 8f)
                .coerceIn(4f, h / 2f - 4f)

            // Color intensity based on amplitude
            val alpha = (150 + (amplitude * 3).toInt()).coerceIn(150, 255)
            paint.color = Color.argb(alpha, 79, 195, 247)
            paint.strokeWidth = barWidth * 0.6f
            paint.strokeCap = Paint.Cap.ROUND

            canvas.drawLine(x, midY - barH, x, midY + barH, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopWave()
    }
}
