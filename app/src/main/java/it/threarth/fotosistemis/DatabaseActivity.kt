package it.threarth.fotosistemis

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import it.threarth.fotosistemis.core.data.InventoryDump
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * What the app knows, read on the phone that holds it.
 *
 * The same content the backup writes out, which until now could only be
 * looked at by exporting the file and opening it on a computer. Debugging
 * this app means asking about one photograph — why is it still called
 * missing, where has it been, what is asked of it — and those questions
 * arrive while the phone is in hand and the computer is somewhere else.
 *
 * Read-only, deliberately: nothing here changes a decision. A screen that
 * showed the truth and also edited it would be two tools, and the second
 * would be the one nobody could check.
 */
class DatabaseActivity : AppCompatActivity() {

    private companion object {

        /** Enough of a fingerprint to recognise it, too little to read out. */
        const val HASH_SHOWN = 12

        /** Timestamps to the minute: the second is never the question. */
        const val WHEN_PATTERN = "dd/MM/yyyy HH:mm"

        const val DAY_PATTERN = "dd/MM/yyyy"
        const val BYTES_PER_KB = 1024
    }

    private lateinit var dump: InventoryDump
    private lateinit var search: EditText
    private lateinit var outcome: TextView
    private lateinit var list: android.widget.ListView

    private val whenFormat = SimpleDateFormat(WHEN_PATTERN, Locale.ITALY)
    private val dayFormat = SimpleDateFormat(DAY_PATTERN, Locale.ITALY)

    private var everything: List<InventoryDump.Entry> = emptyList()
    private var shown: List<InventoryDump.Entry> = emptyList()
    private var order = InventoryDump.Order.LAST_ACTION

    /** Cards opened to their history, by photo. */
    private val opened = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_database)
        findViewById<View>(R.id.databaseRoot).padForSystemBars()

        dump = InventoryDump(AndroidDatabase(this))
        search = findViewById(R.id.databaseSearch)
        outcome = findViewById(R.id.databaseOutcome)
        list = findViewById(R.id.databaseList)

        buildOrders()
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = redraw()
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ -> toggleHistory(position) }

        load()
    }

    /** The three orders, named by what they answer. */
    private fun buildOrders() {
        val spinner = findViewById<Spinner>(R.id.databaseOrder)
        val labels = listOf(
            getString(R.string.database_order_last),
            getString(R.string.database_order_taken),
            getString(R.string.database_order_place)
        )
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, at: Int, id: Long) {
                order = InventoryDump.Order.entries[at]
                redraw()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Reads the whole inventory off the main thread; it is tens of thousands. */
    private fun load() {
        outcome.setText(R.string.database_loading)
        thread {
            val read = dump.load().getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                everything = read
                redraw()
            }
        }
    }

    private fun redraw() {
        shown = InventoryDump.search(everything, search.text.toString(), order)
        outcome.text = getString(R.string.database_found, shown.size, everything.size)
        (list.adapter as? EntryAdapter)?.notifyDataSetChanged()
            ?: run { list.adapter = EntryAdapter() }
    }

    /** A card opens onto where the photograph has been, and closes again. */
    private fun toggleHistory(position: Int) {
        val photoId = shown[position].photoId
        if (!opened.remove(photoId)) opened.add(photoId)
        (list.adapter as EntryAdapter).notifyDataSetChanged()
    }

    private inner class EntryAdapter : BaseAdapter() {

        private val inflater = LayoutInflater.from(this@DatabaseActivity)

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): InventoryDump.Entry = shown[position]
        override fun getItemId(position: Int): Long = shown[position].photoId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: inflater.inflate(R.layout.item_database_entry, parent, false)
            val entry = shown[position]

            view.findViewById<TextView>(R.id.entryName).text =
                "${entry.relativePath}${entry.displayName}"
            view.findViewById<TextView>(R.id.entryFacts).text = factsOf(entry)

            val history = view.findViewById<TextView>(R.id.entryHistory)
            val open = entry.photoId in opened
            history.visibility = if (open) View.VISIBLE else View.GONE
            if (open) history.text = historyOf(entry)
            return view
        }
    }

    /**
     * The facts about one photograph, in the order they are asked for.
     *
     * What became of it first, then what is still asked, then what would
     * explain a surprise: gone since when, a date nobody trusts, the two
     * fingerprints. The identifiers last, because they matter only once the
     * rest has raised a question.
     */
    private fun factsOf(entry: InventoryDump.Entry): String {
        val lines = ArrayList<String>()
        lines.add(
            getString(
                R.string.database_state,
                entry.status ?: getString(R.string.database_state_none),
                entry.category ?: "-",
                entry.statusAt?.let { whenFormat.format(Date(it)) } ?: "-"
            )
        )
        if (entry.proposedAction != null) {
            lines.add(
                getString(
                    R.string.database_asked, entry.proposedAction, entry.proposedCategory ?: "-",
                    entry.proposedAt?.let { whenFormat.format(Date(it)) } ?: "-"
                )
            )
        }
        entry.missingSince?.let {
            lines.add(getString(R.string.database_missing, whenFormat.format(Date(it))))
        }
        lines.add(
            getString(
                R.string.database_taken,
                dayFormat.format(Date(entry.dateTakenMillis)),
                entry.dateSource ?: "-",
                entry.sizeBytes / BYTES_PER_KB
            ) + if (entry.dateSuspect) getString(R.string.database_date_suspect) else ""
        )
        lines.add(
            getString(
                R.string.database_hashes,
                entry.contentHash?.take(HASH_SHOWN) ?: "-",
                entry.imageHash?.take(HASH_SHOWN) ?: "-"
            )
        )
        if (entry.tags.isNotEmpty() || entry.stars != null || entry.ignoredBy.isNotEmpty()) {
            lines.add(
                getString(
                    R.string.database_marks, entry.tags.joinToString(", ").ifEmpty { "-" },
                    entry.stars?.toString() ?: "-",
                    entry.ignoredBy.joinToString(", ").ifEmpty { "-" }
                )
            )
        }
        lines.add(getString(R.string.database_ids, entry.photoId, entry.mediaId ?: 0))
        lines.add(getString(R.string.database_history_hint, entry.history.size))
        return lines.joinToString("\n")
    }

    /** Every place the photograph has been, oldest first. */
    private fun historyOf(entry: InventoryDump.Entry): String {
        if (entry.history.isEmpty()) return getString(R.string.database_history_none)

        return entry.history.joinToString("\n") { step ->
            "${whenFormat.format(Date(step.recordedAt))}  ${step.kind}\n" +
                    "   ${step.path}${step.displayName.orEmpty()}"
        }
    }
}
