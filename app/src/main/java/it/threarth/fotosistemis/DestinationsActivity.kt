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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
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

    companion object {
        private const val BACKUP_MIME_TYPE = "application/json"
        private const val BACKUP_STAMP_PATTERN = "yyyyMMdd-HHmm"

        /** Which function to run on opening, when the menu asked for one. */
        const val EXTRA_ACTION = "it.threarth.fotosistemis.ACTION"

        /** Read the classification already on disk. */
        const val ACTION_ADOPT = "adopt"

        /** Write the database out to a file the user chooses. */
        const val ACTION_EXPORT = "export"

        /** Read a database back in from a file. */
        const val ACTION_IMPORT = "import"

        /** Decide what Android is allowed to back up. */
        const val ACTION_BACKUP = "backup"
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
        listView.setOnItemClickListener { _, _, position, _ ->
            editDestination(destinations[position])
        }
        findViewById<Button>(R.id.addDestinationButton).setOnClickListener { addDestination() }
        findViewById<Button>(R.id.destinationRootButton).setOnClickListener {
            editDestinationRoot()
        }
        findViewById<Button>(R.id.advancedButton).setOnClickListener { showAdvanced() }

        // Opened from the menu to run one function: go straight to it.
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_ADOPT -> previewAdoption()
            ACTION_EXPORT -> startExport()
            ACTION_IMPORT -> confirmImport()
            ACTION_BACKUP -> editCloudBackup()
        }

        refresh()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.destinationsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Reloads the categories and says where they all live. */
    private fun refresh() {
        destinations = repository.loadAll().getOrElse {
            showError(it)
            emptyList()
        }
        findViewById<TextView>(R.id.destinationRootLine).text =
            getString(R.string.destination_root_line, settings.destinationRoot)

        val uses = inventory.countByDestination().getOrElse { emptyList() }
            .associateBy { it.destinationId }

        listView.adapter = DestinationAdapter(
            context = this,
            destinations = destinations,
            uses = uses,
            yearFolderPattern = settings.yearFolderPattern,
            currentYear = Calendar.getInstance().get(Calendar.YEAR),
            onYearToggled = ::setYearSubfolder
        )
    }

    /**
     * Flips the year switch straight from the row.
     *
     * It decides only what new photos will do: folders already created keep
     * the photos they hold, and moving those is the reorganisation's job.
     */
    private fun setYearSubfolder(destination: Destination, yearSubfolder: Boolean) {
        repository.update(
            destination.id,
            destination.label,
            destination.relativePath,
            yearSubfolder
        ).onFailure { showError(it) }.onSuccess { refresh() }
    }

    /**
     * The operations that are not everyday work.
     *
     * Recognising, reorganising and the backups are each rare and weighty;
     * beside "add a category" they read as equally ordinary, which they are
     * not.
     */
    private fun showAdvanced() {
        val voci = arrayOf<CharSequence>(
            getString(R.string.action_adopt),
            getString(R.string.action_reorganize),
            getString(R.string.action_placement),
            getString(R.string.action_export),
            getString(R.string.action_import),
            getString(R.string.action_cloud_backup)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.advanced_title)
            .setItems(voci) { _, which ->
                when (which) {
                    0 -> previewAdoption()
                    1 -> startActivity(Intent(this, ReorganizeActivity::class.java))
                    2 -> startActivity(Intent(this, PlacementActivity::class.java))
                    3 -> startExport()
                    4 -> confirmImport()
                    else -> editCloudBackup()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

        // Everywhere, not just the scope: recognising is meant to find the
        // whole of what is already sorted in one pass, and the same category
        // living under two roots is one category, not two.
        val proposal = ClassificationAdopter.propose(
            entries, destinations, decided, ClassificationAdopter.ANYWHERE
        )
        if (proposal.total == 0) return toast(getString(R.string.adopt_none))

        chooseCategories(proposal)
    }

    /**
     * Asks which of the categories found actually mean something.
     *
     * Searching the whole device for a shape finds things that merely have
     * the shape: a folder whose name contains a year makes its parent look
     * like a category, so DCIM and storage-0 turn up beside Famiglia. Only
     * the user can tell which is which.
     */
    private fun chooseCategories(proposal: ClassificationAdopter.Proposal) {
        val offerte = proposal.categories
        val checked = BooleanArray(offerte.size) { !offerte[it].isNew }
        val labels = offerte.map { offerta ->
            getString(
                if (offerta.isNew) R.string.adopt_category_new else R.string.adopt_category_known,
                offerta.label,
                offerta.photoCount
            )
        }.toTypedArray<CharSequence>()

        AlertDialog.Builder(this)
            .setTitle(R.string.adopt_choose_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val tenute = offerte.filterIndexed { i, _ -> checked[i] }.map { it.label }
                if (tenute.isEmpty()) return@setPositiveButton toast(getString(R.string.adopt_none_kept))

                summariseAdoption(proposal.restrictedTo(tenute.toSet()))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** States what accepting would record, before anything is written. */
    private fun summariseAdoption(proposal: ClassificationAdopter.Proposal) {
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
        val patternGroup = form.findViewById<View>(R.id.patternGroup)
        val patternField = form.findViewById<EditText>(R.id.destinationPattern)
        val patternExample = form.findViewById<TextView>(R.id.patternExample)

        patternField.setText(settings.yearFolderPattern)
        patternField.addTextChangedListener(
            afterTextChanged = { showPatternExample(labelField, patternField, patternExample) }
        )

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

        // The name of the year folders means nothing while there are none.
        patternGroup.isVisible = yearCheck.isChecked
        showPatternExample(labelField, patternField, patternExample)
        yearCheck.setOnCheckedChangeListener { _, checked ->
            patternGroup.isVisible = checked
            showPatternExample(labelField, patternField, patternExample)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.destination_new else R.string.destination_edit)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                settings.yearFolderPattern = patternField.text.toString()
                confirmEditor(labelField, pathField, yearCheck, onConfirm)
            }
            .setNegativeButton(R.string.action_cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.action_delete) { _, _ -> confirmDelete(existing) }
        }
        builder.show()
    }

    /**
     * Shows what the pattern would produce, and that it is shared.
     *
     * The name is one setting for every category, so editing it here changes
     * them all: saying so under the field is cheaper than a surprise.
     */
    private fun showPatternExample(
        labelField: EditText,
        patternField: EditText,
        example: TextView
    ) {
        val label = labelField.text.toString().trim().ifEmpty {
            getString(R.string.destination_label_hint)
        }
        val anno = Calendar.getInstance().get(Calendar.YEAR)
        val cartella = Destination(0, label, label, yearSubfolder = true, sortOrder = 0)
            .yearFolderName(anno, patternField.text.toString())

        example.text = getString(R.string.pattern_example, label, cartella)
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

    /**
     * Deleting a shortcut is not deleting photos, and says so.
     *
     * A category holding photos is offered a home for them first: deleting
     * it outright would leave every one of them filed under something that
     * no longer exists, which is worse than either keeping or merging.
     */
    private fun confirmDelete(destination: Destination) {
        val filed = stateRepository.countFor(destination.id).getOrElse {
            return showError(it)
        }
        if (filed > 0) return offerMerge(destination, filed)

        AlertDialog.Builder(this)
            .setTitle(R.string.destination_delete_title)
            .setMessage(getString(R.string.destination_delete_message, destination.label))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteDestination(destination) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Offers the other categories as a home for the photos of this one.
     *
     * The case this exists for: a phone transfer splitting one category in
     * two, so the archive holds Famiglia twice and neither is wrong.
     */
    private fun offerMerge(destination: Destination, filed: Int) {
        val altre = destinations.filter { it.id != destination.id }
        if (altre.isEmpty()) {
            return toast(getString(R.string.merge_nowhere, filed))
        }
        val voci = altre.map { "${it.label}  —  ${it.relativePath}/" }
            .toTypedArray<CharSequence>()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.merge_title, destination.label))
            .setMessage(getString(R.string.merge_message, filed, destination.label))
            .setItems(voci) { _, which -> merge(destination, altre[which], filed) }
            .setNeutralButton(R.string.merge_delete_anyway) { _, _ ->
                confirmDeleteAndForget(destination, filed)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Moves the photos, then removes the category they came from. */
    private fun merge(from: Destination, into: Destination, filed: Int) {
        stateRepository.reassign(from.id, into.id)
            .onFailure { return showError(it) }

        repository.delete(from.id)
            .onFailure { showError(it) }
            .onSuccess {
                toast(getString(R.string.merge_done, filed, into.label))
                refresh()
            }
    }

    /** Deleting a category with photos in it is spelled out before it happens. */
    private fun confirmDeleteAndForget(destination: Destination, filed: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.destination_delete_title)
            .setMessage(getString(R.string.destination_forget_message, filed, destination.label))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                stateRepository.forgetDestination(destination.id)
                    .onFailure { return@setPositiveButton showError(it) }

                deleteDestination(destination)
                toast(getString(R.string.destination_forget_done, filed))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteDestination(destination: Destination) {
        repository.delete(destination.id)
            .onFailure { showError(it) }
            .onSuccess { refresh() }
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
