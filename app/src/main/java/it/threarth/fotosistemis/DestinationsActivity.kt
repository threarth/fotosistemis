package it.threarth.fotosistemis

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar

/**
 * Create, edit and delete the folders photos can be filed into.
 *
 * Deleting a destination removes only the shortcut: photos already filed
 * there keep their files exactly where they are.
 */
class DestinationsActivity : AppCompatActivity() {

    private lateinit var repository: DestinationRepository
    private lateinit var settings: AppSettings
    private lateinit var listView: ListView
    private var destinations: List<DestinationRepository.Destination> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_destinations)
        applySystemBarInsets()

        repository = DestinationRepository(this)
        settings = AppSettings(this)
        listView = findViewById(R.id.destinationList)
        listView.setOnItemClickListener { _, _, position, _ -> editDestination(destinations[position]) }
        findViewById<Button>(R.id.addDestinationButton).setOnClickListener { addDestination() }
        findViewById<Button>(R.id.patternButton).setOnClickListener { editPattern() }

        refresh()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.destinationsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Reloads the list and rebuilds its labels. */
    private fun refresh() {
        destinations = repository.loadAll().getOrElse {
            showError(it)
            emptyList()
        }
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            destinations.map(::describe)
        )
    }

    /** One line per destination: label, path, and whether years are added. */
    private fun describe(destination: DestinationRepository.Destination): String {
        if (!destination.yearSubfolder) {
            return "${destination.label}\n${destination.relativePath}/ · " +
                    getString(R.string.destination_without_year)
        }
        val example = destination.yearFolderName(
            Calendar.getInstance().get(Calendar.YEAR),
            settings.yearFolderPattern
        )
        return "${destination.label}\n${destination.relativePath}/$example/"
    }

    /**
     * Edits the year folder name shared by every destination.
     *
     * Changing it does not touch folders already created: photos filed
     * earlier stay where they are, and only new ones follow the new name.
     */
    private fun editPattern() {
        val form = LayoutInflater.from(this).inflate(R.layout.dialog_tag, null)
        val field = form.findViewById<EditText>(R.id.tagName)
        field.setText(settings.yearFolderPattern)

        AlertDialog.Builder(this)
            .setTitle(R.string.pattern_title)
            .setMessage(getString(R.string.destination_pattern_help))
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                settings.yearFolderPattern = field.text.toString()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun addDestination() {
        showEditor(null) { label, path, yearSubfolder ->
            repository.insert(label, path, yearSubfolder)
                .onFailure { showError(it) }
                .onSuccess { refresh() }
        }
    }

    private fun editDestination(destination: DestinationRepository.Destination) {
        showEditor(destination) { label, path, yearSubfolder ->
            repository.update(destination.id, label, path, yearSubfolder)
                .onFailure { showError(it) }
                .onSuccess { refresh() }
        }
    }

    /**
     * Shows the create/edit form. [existing] is null when adding.
     * The delete button only appears when editing.
     */
    private fun showEditor(
        existing: DestinationRepository.Destination?,
        onConfirm: (String, String, Boolean) -> Unit
    ) {
        val form = LayoutInflater.from(this).inflate(R.layout.dialog_destination, null)
        val labelField = form.findViewById<EditText>(R.id.destinationLabel)
        val pathField = form.findViewById<EditText>(R.id.destinationPath)
        val yearCheck = form.findViewById<CheckBox>(R.id.destinationYearSubfolder)

        existing?.let {
            labelField.setText(it.label)
            pathField.setText(it.relativePath)
            yearCheck.isChecked = it.yearSubfolder
        } ?: run { yearCheck.isChecked = true }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.destination_new else R.string.destination_edit)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                confirmEditor(labelField, pathField, yearCheck, onConfirm)
            }
            .setNegativeButton(R.string.action_cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.action_delete) { _, _ -> confirmDelete(existing) }
        }
        builder.show()
    }

    /** Validates the form before handing values back. */
    private fun confirmEditor(
        labelField: EditText,
        pathField: EditText,
        yearCheck: CheckBox,
        onConfirm: (String, String, Boolean) -> Unit
    ) {
        val label = labelField.text.toString().trim()
        val path = pathField.text.toString().trim().trim('/')
        if (label.isEmpty() || path.isEmpty()) {
            toast(getString(R.string.destination_invalid))
            return
        }
        onConfirm(label, path, yearCheck.isChecked)
    }

    /** Deleting a shortcut is not deleting photos, and says so. */
    private fun confirmDelete(destination: DestinationRepository.Destination) {
        AlertDialog.Builder(this)
            .setTitle(R.string.destination_delete_title)
            .setMessage(getString(R.string.destination_delete_message, destination.label))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.delete(destination.id)
                    .onFailure { showError(it) }
                    .onSuccess { refresh() }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showError(error: Throwable) {
        toast(getString(R.string.message_error, error.message ?: error::class.java.simpleName))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
