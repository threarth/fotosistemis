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
import android.content.Intent
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

        /** How faded a field looks while a checkbox has taken it out of use. */
        const val DISABLED_ALPHA = 0.4f
    }

    private lateinit var repository: DestinationRepository
    private lateinit var settings: AppSettings
    private lateinit var backup: BackupRepository
    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository

    /** Held while the user looks at the photos it would affect. */
    private var pendingProposal: ClassificationAdopter.Proposal? = null

    private val reviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) applyAdoption() else pendingProposal = null
        }
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
        findViewById<Button>(R.id.cloudBackupButton).setOnClickListener { editCloudBackup() }
        findViewById<Button>(R.id.sourceRootsButton).setOnClickListener { editSourceRoots() }
        findViewById<Button>(R.id.destinationRootButton).setOnClickListener {
            editDestinationRoot()
        }
        findViewById<Button>(R.id.reorganizeButton).setOnClickListener {
            startActivity(Intent(this, ReorganizeActivity::class.java))
        }
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

        val roots = settings.effectiveSourceRoots().ifEmpty { ClassificationAdopter.DEFAULT_ROOTS }
        val proposal = ClassificationAdopter.propose(entries, destinations, decided, roots)
        if (proposal.total == 0) return toast(getString(R.string.adopt_none))

        val breakdown = proposal.byCategory.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  ${it.key}: ${it.value}" }
        val newFolders = if (!proposal.createsCategories) "" else getString(
            R.string.adopt_new_categories,
            proposal.proposedCategories.joinToString(", ") { it.label }
        )

        pendingProposal = proposal
        AlertDialog.Builder(this)
            .setTitle(R.string.adopt_title)
            .setMessage(getString(R.string.adopt_message, proposal.total, breakdown, newFolders))
            .setPositiveButton(R.string.adopt_confirm) { _, _ -> applyAdoption() }
            .setNeutralButton(R.string.adopt_review) { _, _ -> reviewAdoption(proposal) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Opens the photos before anything is written.
     *
     * A count cannot be checked: three thousand is either right or a
     * disaster, and only looking tells them apart.
     */
    private fun reviewAdoption(proposal: ClassificationAdopter.Proposal) {
        val records = inventory.loadRecords(proposal.candidates.map { it.photoId })
            .getOrElse { return showError(it) }
        if (records.isEmpty()) return toast(getString(R.string.adopt_none))

        PhotoPreviewActivity.pendingPhotos = records
        PhotoPreviewActivity.pendingSummary = getString(R.string.adopt_review_summary, records.size)
        reviewLauncher.launch(Intent(this, PhotoPreviewActivity::class.java))
    }

    private fun applyAdoption() {
        val proposal = pendingProposal ?: return
        pendingProposal = null
        inventory.adopt(proposal, repository)
            .onFailure { showError(it) }
            .onSuccess {
                toast(getString(R.string.adopt_done, it))
                refresh()
            }
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
        } ?: run {
            // A new folder starts under the destination root: typing the
            // same prefix for every category is work the app can do.
            pathField.setText(settings.destinationRoot + "/")
            yearCheck.isChecked = true
        }

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

    /**
     * Chooses whether Android may take this app's data.
     *
     * Stated in full rather than summarised: a backup that leaves the device
     * is the one thing here the user cannot inspect afterwards, so what it
     * contains has to be readable before deciding, not after.
     */
    private fun editCloudBackup() {
        val check = CheckBox(this).apply {
            setText(R.string.cloud_backup_enabled)
            isChecked = settings.backupToCloud
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cloud_backup_title)
            .setMessage(R.string.cloud_backup_explained)
            .setView(check)
            .setPositiveButton(R.string.action_save) { _, _ ->
                settings.backupToCloud = check.isChecked
                toast(
                    getString(
                        if (check.isChecked) R.string.cloud_backup_on
                        else R.string.cloud_backup_off
                    )
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Chooses where the app looks for photos to sort.
     *
     * Ticking the whole device greys the list out rather than clearing it,
     * so unticking gives the roots back instead of losing them.
     */
    private fun editSourceRoots() {
        val form = LayoutInflater.from(this).inflate(R.layout.dialog_roots, null)
        val field = form.findViewById<EditText>(R.id.rootsField)
        val wholeDevice = form.findViewById<CheckBox>(R.id.rootsWholeDevice)

        field.setText(settings.sourceRoots.joinToString("\n"))
        wholeDevice.isChecked = settings.wholeDeviceAsSource
        applyWholeDevice(field, wholeDevice.isChecked)
        wholeDevice.setOnCheckedChangeListener { _, checked ->
            applyWholeDevice(field, checked)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.roots_title)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                settings.sourceRoots = field.text.toString().split("\n")
                settings.wholeDeviceAsSource = wholeDevice.isChecked
                announceRoots()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** The roots stay visible while the whole device is chosen, but inert. */
    private fun applyWholeDevice(field: EditText, wholeDevice: Boolean) {
        field.isEnabled = !wholeDevice
        field.alpha = if (wholeDevice) DISABLED_ALPHA else 1f
    }

    private fun announceRoots() {
        toast(
            if (settings.wholeDeviceAsSource) getString(R.string.roots_whole_device_saved)
            else getString(R.string.roots_saved, settings.sourceRoots.joinToString(", "))
        )
    }

    /**
     * Chooses the single folder every category lives under.
     *
     * Changing it offers to bring the existing categories along. Nothing on
     * disk moves here: that is the reorganisation's job, and it shows what it
     * would do first.
     */
    private fun editDestinationRoot() {
        val field = EditText(this).apply {
            setText(settings.destinationRoot)
            hint = getString(R.string.destination_path_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.destination_root_title)
            .setMessage(R.string.destination_root_explained)
            .setView(field)
            .setPositiveButton(R.string.action_save) { _, _ ->
                settings.destinationRoot = field.text.toString()
                offerRebase()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Asks before rewriting the path of categories that sit elsewhere. */
    private fun offerRebase() {
        val root = settings.destinationRoot
        val strays = destinations.filterNot { it.relativePath.startsWith("$root/") }
        if (strays.isEmpty()) return refresh()

        AlertDialog.Builder(this)
            .setTitle(R.string.destination_root_rebase_title)
            .setMessage(
                getString(
                    R.string.destination_root_rebase, strays.size, root,
                    strays.joinToString("\n") { "  ${it.relativePath}" }
                )
            )
            .setPositiveButton(R.string.action_save) { _, _ -> rebase(strays, root) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> refresh() }
            .show()
    }

    /** Moves the categories under [root] in the database, not on disk. */
    private fun rebase(strays: List<Destination>, root: String) {
        for (destination in strays) {
            val outcome = repository.update(
                destination.id,
                destination.label,
                "$root/${destination.relativePath.substringAfterLast('/')}",
                destination.yearSubfolder
            )
            if (outcome.isFailure) return showError(outcome.exceptionOrNull()!!)
        }
        refresh()
        AlertDialog.Builder(this)
            .setTitle(R.string.reorganize_title)
            .setMessage(getString(R.string.destination_root_rebased, strays.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                startActivity(Intent(this, ReorganizeActivity::class.java))
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
