package de.metaviewsoft.chordprogressionhelper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import de.metaviewsoft.chordprogressionhelper.R

/**
 * Renders the chords of a song section on the exact same 0..1 timeline as the
 * playback progress bar. A chord anchored at quarter-note [ChordMark.fraction]
 * is drawn at fraction * width, so its label sits precisely where the progress
 * edge reaches it during playback.
 */
class ChordTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ChordMark moved to :shared commonMain (same package) so the view model core can produce it.

    private val density = resources.displayMetrics.density
    private val cornerRadius = 6f * density
    private val labelPadding = 3f * density
    private val trackRect = RectF()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.section_item_bg)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.section_item_beat_bg)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = resolveTextColor(context)
    }

    private var marks: List<ChordMark> = emptyList()
    private var isPlaying: Boolean = false
    private var progress: Float = 0f

    fun setChords(chords: List<ChordMark>) {
        if (chords == marks) return
        marks = chords
        invalidate()
    }

    fun setPlaying(playing: Boolean) {
        if (playing == isPlaying) return
        isPlaying = playing
        if (!playing) progress = 0f
        invalidate()
    }

    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == progress) return
        progress = clamped
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (18f * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return

        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, basePaint)

        if (isPlaying && progress > 0f) {
            val save = canvas.save()
            canvas.clipRect(0f, 0f, w * progress, h)
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint)
            canvas.restoreToCount(save)
        }

        if (marks.isEmpty()) return
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for ((i, mark) in marks.withIndex()) {
            val x = mark.fraction * w + labelPadding
            // Clip each label to its own slot so labels never overlap the next chord.
            val slotEnd = if (i + 1 < marks.size) marks[i + 1].fraction * w else w
            val save = canvas.save()
            canvas.clipRect(x - labelPadding, 0f, slotEnd, h)
            canvas.drawText(mark.label, x, baseline, textPaint)
            canvas.restoreToCount(save)
        }
    }

    private fun resolveTextColor(context: Context): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        }
        return Color.WHITE
    }
}
