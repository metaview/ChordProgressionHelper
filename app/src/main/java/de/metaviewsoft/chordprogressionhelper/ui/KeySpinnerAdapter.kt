package de.metaviewsoft.chordprogressionhelper.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import de.metaviewsoft.chordprogressionhelper.R
import de.metaviewsoft.chordprogressionhelper.model.Key

/**
 * Spinner-Adapter fuer die Tonart-Auswahl: zeigt den Tonart-Namen und daneben ein kleines
 * Notenbild ([KeySignatureView]) mit den Vorzeichen der jeweiligen Tonart.
 *
 * Die Reihenfolge folgt dem Quintenzirkel ([KEY_ORDER]): erst die B-Tonarten mit absteigender
 * Vorzeichenzahl, dann C/Am, dann die Kreuz-Tonarten mit aufsteigender Vorzeichenzahl. Aufrufer
 * duerfen deshalb Positionen NICHT ueber `Key.entries[position]` aufloesen, sondern ueber
 * [KEY_ORDER] (bzw. `getItem`/`getPosition`).
 */
class KeySpinnerAdapter(context: Context) :
    ArrayAdapter<Key>(context, 0, KEY_ORDER) {

    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    private fun bind(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_key_spinner, parent, false)
        val key = getItem(position) ?: Key.C
        view.findViewById<TextView>(R.id.keyName).text = key.displayName
        view.findViewById<KeySignatureView>(R.id.keySignature).key = key
        return view
    }

    companion object {
        /**
         * Tonart-Reihenfolge im Quintenzirkel: absteigende Bs -> C/Am -> aufsteigende Kreuze.
         *   Gb(6b) Db(5b) Ab(4b) Eb(3b) Bb(2b) F(1b) | C(0) | G(1#) D(2#) A(3#) E(4#) B(5#) F#(6#) C#(7#)
         */
        val KEY_ORDER: List<Key> = listOf(
            Key.G_FLAT, Key.D_FLAT, Key.A_FLAT, Key.E_FLAT, Key.B_FLAT, Key.F,
            Key.C,
            Key.G, Key.D, Key.A, Key.E, Key.B, Key.F_SHARP, Key.C_SHARP
        )
    }
}
