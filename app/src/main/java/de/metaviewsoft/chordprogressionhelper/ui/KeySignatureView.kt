package de.metaviewsoft.chordprogressionhelper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import de.metaviewsoft.chordprogressionhelper.R
import de.metaviewsoft.chordprogressionhelper.model.Key

/**
 * Zeichnet ein kleines Notenbild fuer eine Tonart: ein 5-Linien-System mit Violinschluessel
 * und den korrekt platzierten Vorzeichen (Kreuze bzw. Bs) der jeweiligen Dur-Tonart.
 *
 * Reine Canvas-Zeichnung, faerbt sich mit der Theme-Textfarbe. Der Schluessel wird als
 * Unicode-Glyph (U+1D11E) gezeichnet und ueber seine gemessenen Bounds exakt auf dem System
 * positioniert; fehlt der Glyph auf sehr alten Geraeten, wird ein Notensymbol als Fallback genutzt.
 */
class KeySignatureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clefPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val glyphBounds = Rect()
    private val flatBulb = Path()

    private var accidentalCount = 0
    private var sharps = true
    private val hasClefGlyph = clefPaint.hasGlyph(CLEF)

    var key: Key = Key.C
        set(value) {
            field = value
            val sig = signatureFor(value)
            accidentalCount = sig.first
            sharps = sig.second
            requestLayout()
            invalidate()
        }

    init {
        // textColorPrimary ist i.d.R. eine ColorStateList – ueber resolveAttribute bekaeme man nur
        // die Resource-ID (als ARGB fast unsichtbar). Daher sauber als ColorStateList aufloesen.
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val color = try {
            ta.getColorStateList(0)?.defaultColor ?: ta.getColor(0, Color.GRAY)
        } finally {
            ta.recycle()
        }
        linePaint.color = color
        clefPaint.color = color
        accPaint.color = color
        accFill.color = color
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultH = (40 * density).toInt()

        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> minOf(defaultH, hSize)
            else -> defaultH
        }

        val lineGap = h / 7f
        val contentW = paddingLeft + paddingRight +
            (clefBoxWidth(lineGap) + GAP_AFTER_CLEF * lineGap + accidentalCount * (ACC_WIDTH * lineGap) + 0.4f * lineGap).toInt()

        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val w = when (wMode) {
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> minOf(contentW, wSize)
            else -> contentW
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val lineGap = h / 7f
        val halfStep = lineGap / 2f
        val topLineY = (h - 4f * lineGap) / 2f

        linePaint.strokeWidth = maxOf(1f, lineGap * 0.09f)
        accPaint.strokeWidth = maxOf(1.5f, lineGap * 0.13f)

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()

        // 5 Notenlinien
        for (i in 0..4) {
            val y = topLineY + i * lineGap
            canvas.drawLine(left, y, right, y, linePaint)
        }

        // Violinschluessel
        val clefX = left + 0.3f * lineGap
        drawClef(canvas, clefX, topLineY, lineGap)

        // Vorzeichen
        val steps = if (sharps) SHARP_STEPS else FLAT_STEPS
        var x = clefX + clefBoxWidth(lineGap) + GAP_AFTER_CLEF * lineGap
        for (i in 0 until accidentalCount) {
            val cy = topLineY + steps[i] * halfStep
            if (sharps) drawSharp(canvas, x + ACC_WIDTH * lineGap / 2f, cy, lineGap)
            else drawFlat(canvas, x + ACC_WIDTH * lineGap / 2f, cy, lineGap)
            x += ACC_WIDTH * lineGap
        }
    }

    /** Breite, die der Schluessel horizontal einnimmt. */
    private fun clefBoxWidth(lineGap: Float): Float = 2.3f * lineGap

    private fun drawClef(canvas: Canvas, clefX: Float, topLineY: Float, lineGap: Float) {
        val targetHeight = lineGap * 6.2f
        if (hasClefGlyph) {
            // Groesse so skalieren, dass die gemessene Glyphenhoehe der Zielhoehe entspricht.
            clefPaint.textSize = 100f
            clefPaint.getTextBounds(CLEF, 0, CLEF.length, glyphBounds)
            if (glyphBounds.height() > 0) {
                clefPaint.textSize = 100f * targetHeight / glyphBounds.height()
                clefPaint.getTextBounds(CLEF, 0, CLEF.length, glyphBounds)
            }
            // Schleifenzentrum des G-Schluessels sitzt ungefaehr auf der G-Linie (2. Linie von unten).
            // Wir richten den Glyph vertikal ueber seine Bounds mittig zum System aus.
            val clefTopY = topLineY - lineGap * 1.1f
            val baselineY = clefTopY - glyphBounds.top
            val glyphX = clefX - glyphBounds.left
            canvas.drawText(CLEF, glyphX, baselineY, clefPaint)
        } else {
            val d = AppCompatResources.getDrawable(context, R.drawable.ic_music_note) ?: return
            d.setTint(clefPaint.color)
            val top = (topLineY).toInt()
            val size = (lineGap * 4f).toInt()
            d.setBounds(clefX.toInt(), top, clefX.toInt() + size, top + size)
            d.draw(canvas)
        }
    }

    private fun drawSharp(canvas: Canvas, cx: Float, cy: Float, g: Float) {
        val w = g * 0.30f          // halber Abstand der beiden senkrechten Striche
        val vTop = cy - 0.85f * g
        val vBot = cy + 0.85f * g
        // zwei senkrechte Striche
        canvas.drawLine(cx - w, vTop + 0.15f * g, cx - w, vBot, accPaint)
        canvas.drawLine(cx + w, vTop, cx + w, vBot - 0.15f * g, accPaint)
        // zwei (leicht ansteigende) Querstriche
        val hw = g * 0.5f
        val tilt = g * 0.13f
        val y1 = cy - 0.28f * g
        val y2 = cy + 0.28f * g
        canvas.drawLine(cx - hw, y1 + tilt, cx + hw, y1 - tilt, accPaint)
        canvas.drawLine(cx - hw, y2 + tilt, cx + hw, y2 - tilt, accPaint)
    }

    private fun drawFlat(canvas: Canvas, cx: Float, cy: Float, g: Float) {
        val stemX = cx - 0.28f * g
        val stemTop = cy - 1.35f * g
        val stemBot = cy + 0.45f * g
        // Notenhals
        canvas.drawLine(stemX, stemTop, stemX, stemBot, accPaint)
        // Bauch (kleine Schleife nach rechts) – gefuellt fuer sauberes, kleines Erscheinungsbild
        flatBulb.reset()
        flatBulb.moveTo(stemX, cy - 0.45f * g)
        flatBulb.cubicTo(
            stemX + 0.9f * g, cy - 0.7f * g,
            stemX + 0.9f * g, cy + 0.35f * g,
            stemX, cy + 0.4f * g
        )
        flatBulb.cubicTo(
            stemX + 0.5f * g, cy + 0.15f * g,
            stemX + 0.5f * g, cy - 0.2f * g,
            stemX, cy - 0.45f * g
        )
        canvas.drawPath(flatBulb, accFill)
    }

    companion object {
        /** MUSICAL SYMBOL G CLEF (U+1D11E) */
        private const val CLEF = "𝄞"

        private const val GAP_AFTER_CLEF = 0.5f
        private const val ACC_WIDTH = 0.95f

        // Vertikale Positionen (in Halb-Schritten ab der obersten Linie F5 = 0, nach unten positiv).
        // Reihenfolge entspricht der Standard-Vorzeichenreihenfolge.
        // Kreuze:  F#  C#  G#  D#  A#  E#  B#
        private val SHARP_STEPS = intArrayOf(0, 3, -1, 2, 5, 1, 4)
        // Bs:      Bb  Eb  Ab  Db  Gb  Cb  Fb
        private val FLAT_STEPS = intArrayOf(4, 1, 5, 2, 6, 3, 7)

        /** Anzahl Vorzeichen und ob es Kreuze (true) oder Bs (false) sind, fuer die Dur-Tonart. */
        fun signatureFor(key: Key): Pair<Int, Boolean> = when (key) {
            Key.C -> 0 to true
            Key.G -> 1 to true
            Key.D -> 2 to true
            Key.A -> 3 to true
            Key.E -> 4 to true
            Key.B -> 5 to true
            Key.F_SHARP -> 6 to true
            Key.C_SHARP -> 7 to true
            Key.F -> 1 to false
            Key.B_FLAT -> 2 to false
            Key.E_FLAT -> 3 to false
            Key.A_FLAT -> 4 to false
            Key.D_FLAT -> 5 to false
            Key.G_FLAT -> 6 to false
        }
    }
}
