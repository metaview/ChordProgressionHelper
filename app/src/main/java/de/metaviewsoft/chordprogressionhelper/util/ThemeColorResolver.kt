package de.metaviewsoft.chordprogressionhelper.util

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes

object ThemeColorResolver {
    fun primary(context: Context): Int =
        resolve(context, intArrayOf(android.R.attr.colorPrimary, android.R.attr.colorAccent), Color.GRAY)

    fun surface(context: Context): Int =
        resolve(context, intArrayOf(android.R.attr.colorBackground), Color.WHITE)

    fun surfaceBright(context: Context): Int =
        surface(context)

    fun onSurface(context: Context): Int =
        resolve(context, intArrayOf(android.R.attr.textColorPrimary), Color.DKGRAY)

    fun onBackground(context: Context): Int =
        onSurface(context)

    private fun resolve(context: Context, @AttrRes attrs: IntArray, fallback: Int): Int {
        val tv = TypedValue()
        for (attr in attrs) {
            if (context.theme.resolveAttribute(attr, tv, true)) {
                return tv.data
            }
        }
        return fallback
    }
}

