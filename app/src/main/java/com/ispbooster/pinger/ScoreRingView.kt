package com.ispbooster.pinger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Circular 0..100 score gauge. */
class ScoreRingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val stroke = dp(14f)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = context.getColor(R.color.track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = sp(54f); typeface = Typeface.DEFAULT_BOLD
        color = context.getColor(R.color.text_primary)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = sp(15f); color = context.getColor(R.color.text_secondary)
    }
    private val rect = RectF()

    private var score = -1
    private var label = ""

    fun set(score: Int, label: String, color: Int) {
        if (score == this.score && label == this.label && color == arcPaint.color) return
        this.score = score
        this.label = label
        arcPaint.color = color
        contentDescription = if (score < 0) label else "Score $score, $label"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = stroke / 2
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (score > 0) canvas.drawArc(rect, -90f, 360f * score / 100f, false, arcPaint)

        val cx = width / 2f
        val baseline = height / 2f + valuePaint.textSize * 0.2f
        canvas.drawText(if (score < 0) "--" else score.toString(), cx, baseline, valuePaint)
        canvas.drawText(label, cx, baseline + labelPaint.textSize * 1.7f, labelPaint)
    }
}
