package it.threarth.fotosistemis

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * The manual, one section per thing the app does that cannot be guessed by
 * looking at it.
 *
 * New feature. Written because five behaviours are invisible from the
 * screens themselves — above all that "da eliminare" deletes nothing, and
 * that the cloud copy survives anything done on the phone — and a person
 * who does not know them will either distrust the app or trust it wrongly.
 * The text lives entirely in string resources; this class only lays it out.
 */
class HelpActivity : AppCompatActivity() {

    private companion object {

        /** Title and body of every section, in reading order. */
        val SECTIONS = listOf(
            R.string.help_what_title to R.string.help_what_body,
            R.string.help_words_title to R.string.help_words_body,
            R.string.help_main_title to R.string.help_main_body,
            R.string.help_filters_title to R.string.help_filters_body,
            R.string.help_deciding_title to R.string.help_deciding_body,
            R.string.help_trash_title to R.string.help_trash_body,
            R.string.help_two_bins_title to R.string.help_two_bins_body,
            R.string.help_whatsapp_title to R.string.help_whatsapp_body,
            R.string.help_dates_title to R.string.help_dates_body,
            R.string.help_names_title to R.string.help_names_body,
            R.string.help_categories_title to R.string.help_categories_body,
            R.string.help_adopt_title to R.string.help_adopt_body,
            R.string.help_checks_title to R.string.help_checks_body,
            R.string.help_reorganize_title to R.string.help_reorganize_body,
            R.string.help_restore_title to R.string.help_restore_body,
            R.string.help_inventory_title to R.string.help_inventory_body,
            R.string.help_backup_title to R.string.help_backup_body,
            R.string.help_never_title to R.string.help_never_body
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_help)
        findViewById<View>(R.id.helpRoot).padForSystemBars()

        val content = findViewById<LinearLayout>(R.id.helpContent)
        val inflater = LayoutInflater.from(this)
        for ((title, body) in SECTIONS) addSection(inflater, content, title, body)
    }

    /** Appends one section, inflated from the shared item layout. */
    private fun addSection(inflater: LayoutInflater, content: LinearLayout, title: Int, body: Int) {
        val section = inflater.inflate(R.layout.item_help_section, content, false)
        section.findViewById<TextView>(R.id.helpSectionTitle).setText(title)
        section.findViewById<TextView>(R.id.helpSectionBody).setText(body)
        content.addView(section)
    }
}
