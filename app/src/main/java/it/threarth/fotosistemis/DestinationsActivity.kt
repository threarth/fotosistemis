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
import it.threarth.fotosistemis.core.data.DestinationRepository
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.model.Destination
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import it.threarth.fotosistemis.core.data.ClassificationAdopter
import it.threarth.fotosistemis.core.data.PhotoInventory
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Create, edit and delete the folders photos can be filed into.
 *
 * Deleting a destination removes only the shortcut: photos already filed
 * there keep their files exactly where they are.
 */
class DestinationsActivity : AppCompatActivity() {

    private companion object {
        const val BACKUP_MIME_TYPE = "application/json"
        const val BACKUP_STAMP_PATTERN = "yyyyMMdd-HHmm"
    }

    private lateinit var repository: DestinationRepository
    private lateinit var settings: AppSettings
    private lateinit var backup: BackupRepository
    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var listView: ListView
    private var destinations: List<Destination> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_destinations)
        applySystemBarInsets()

        val database = AndroidDatabase(this)
        repository = DestinationRepository(database)
        settings = AppSettings(this)
        backup = BackupRepository(database, AppSettings(this))
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        listView = findViewById(R.id.destinationList)
        listView.setOnItemClickListener { _, _, position, _ -> editDestination(destinations[position]) }
        findViewById<Button>(R.id.addDestinationButton).setOnClickListener { addDestination() }
        findViewById<Button>(R.id.patternButton).setOnClickListener { editPattern() }
        findViewById<Button>(R.id.adoptButton).setOnClickListener { previewAdoption() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { startExport() }
        findViewById<Button>(R.id.importButton).setOnClickListener { confirmImport() }

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
    private fun describe(destination: Destination): String {
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
     * Writes a backup wherever the user chooses.
     *
     * The system picker is used rather than a fixed folder: it needs no
     * storage permission and lets the copy land somewhere that survives the
     * phone, which is the point of making one.
     */
    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            if (uri == null) return@registerForActivityResult
            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                toast(getString(R.string.message_error, "output"))
                return@registerForActivityResult
            }
            stream.use { output ->
                backup.exportTo(output).fold(
                    onSuccess = { toast(getString(R.string.backup_exported, it.rowCount, it.tableCount)) },
                    onFailure = { showError(it) }
                )
            }
        }

    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val stream = contentResolver.openInputStream(uri)
            if (stream == null) {
                toast(getString(R.string.message_error, "input"))
                return@registerForActivityResult
            }
            stream.use { input ->
                backup.importFrom(input).fold(
                    onSuccess = {
                        toast(getString(R.string.backup_imported, it.rowCount))
                        refresh()
                    },
                    onFailure = { showError(it) }
                )
            }
        }

    /**
     * Offers to record photos that already sit in a destination folder.
     *
     * Useful after moving to another phone, or over an archive tidied by
     * hand: without it those photos come back for review and the work is
     * done twice. Nothing is moved, and what would be recorded is shown
     * first.
     */
    private fun previewAdoption() {
        val entries = inventory.loadForAdoption().getOrElse { return showError(it) }
        val decided = stateRepository.loadAll().getOrElse { return showError(it) }.keys
        val destinations = repository.loadAll().getOrElse { return showError(it) }

        val preview = ClassificationAdopter.preview(entries, destinations, decided)
        if (preview.total == 0) return toast(getString(R.string.adopt_none))

        val breakdown = preview.byDestination.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "${it.key}: ${it.value}" }

        AlertDialog.Builder(this)
            .setTitle(R.string.adopt_title)
            .setMessage(getString(R.string.adopt_message, preview.total, breakdown))
            .setPositiveButton(R.string.adopt_confirm) { _, _ ->
                inventory.adopt(preview.candidates)
                    .onFailure { showError(it) }
                    .onSuccess { toast(getString(R.string.adopt_done, it)) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startExport() {
        val stamp = SimpleDateFormat(BACKUP_STAMP_PATTERN, Locale.ITALY).format(Date())
        createBackup.launch(getString(R.string.backup_file_name, stamp))
    }

    /** Restoring overwrites, so it is stated plainly before it happens. */
    private fun confirmImport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_import_title)
            .setMessage(R.string.backup_import_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                openBackup.launch(arrayOf(BACKUP_MIME_TYPE))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

    private fun editDestination(destination: Destination) {
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
        existing: Destination?,
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
    private fun confirmDelete(destination: Destination) {
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
