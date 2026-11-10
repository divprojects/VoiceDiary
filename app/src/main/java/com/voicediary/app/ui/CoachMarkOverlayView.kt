package com.voicediary.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.voicediary.app.R

internal class CoachMarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var accentColor = ContextCompat.getColor(context, R.color.ios_dark_blue)

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8000000.toInt()
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        alpha = 150
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val target = RectF()
    private val card = RectF()
    private val density = resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        contentDescription = "App guide. Use Next or Skip to continue."
    }

    fun updateTarget(bounds: RectF) {
        target.set(bounds)
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        outlinePaint.color = color
        connectorPaint.color = color
        invalidate()
    }

    fun updateCard(bounds: RectF) {
        card.set(bounds)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        if (target.isEmpty) return

        val radius = 14 * density
        canvas.drawRoundRect(target, radius, radius, clearPaint)
        canvas.drawRoundRect(target, radius, radius, outlinePaint)

        if (!card.isEmpty) {
            val targetX = target.centerX()
            val cardX = targetX.coerceIn(card.left + 20 * density, card.right - 20 * density)
            val cardY = if (card.top > target.bottom) card.top else card.bottom
            val targetY = if (card.top > target.bottom) target.bottom else target.top
            canvas.drawLine(targetX, targetY, cardX, cardY, connectorPaint)
        }
    }
}
