package it.threarth.fotosistemis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Vertical prototype for FotoSistemis on Android.
 *
 * It is deliberately not an app: it exists to answer one question with real
 * numbers on a real Samsung device. Does moving photos through MediaStore
 * cost metadata time or byte-copy time?
 *
 * The web version copies every byte through the SAF content-provider bridge,
 * because Android content URIs support neither atomic writes nor atomic
 * renames. If the measurement below shows a rename, the native rewrite is
 * justified. If it shows a copy, it is not, and we stop here.
 */
class MainActivity : AppCompatActivity() {

    private companion object {

        /**
         * Folder scanned for test photos. Trailing slash required by MediaStore.
         *
         * Deliberately a throwaway folder holding copies, never DCIM/Camera:
         * the prototype physically moves whatever it finds here.
         */
        const val SOURCE_RELATIVE_PATH = "DCIM/test_foto/"

        /**
         * Destination folder. Deliberately under DCIM, the same top-level
         * directory and the same storage volume as the source: that is the
         * best case for a rename. Crossing volumes, for instance to an SD
         * card, would force a real copy and invalidate the measurement.
         */
        const val DESTINATION_RELATIVE_PATH = "DCIM/arch_test/"

        /** Photos moved per run. Large enough that per-call overhead averages out. */
        const val BATCH_SIZE = 200

        /** Below this, a batch is a rename. Above it, bytes are moving. */
        const val RENAME_THRESHOLD_MILLIS = 2000L

        const val TIMESTAMP_PATTERN = "HH:mm:ss"
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
    }

    private lateinit var repository: MediaStoreRepository
    private lateinit var mover: BatchMover
    private lateinit var logView: TextView
    private lateinit var scanButton: Button
    private lateinit var moveButton: Button

    private var scannedPhotos: List<MediaStoreRepository.Photo> = emptyList()

    private val requestReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) log("Permesso concesso.") else log("PERMESSO NEGATO: impossibile proseguire.")
            scanButton.isEnabled = granted
        }

    /** Consent covering the whole batch; only after this can update() succeed. */
    private val requestWriteConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                log("Consenso concesso per ${scannedPhotos.size} foto. Avvio spostamento…")
                runBatchMove()
            } else {
                log("Consenso rifiutato: nessuna foto spostata.")
                moveButton.isEnabled = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySystemBarInsets()

        repository = MediaStoreRepository(this)
        mover = BatchMover(this, repository)

        logView = findViewById(R.id.logView)
        scanButton = findViewById(R.id.scanButton)
        moveButton = findViewById(R.id.moveButton)

        scanButton.setOnClickListener { runScan() }
        moveButton.setOnClickListener { startMoveWithConsent() }
        moveButton.isEnabled = false

        ensureReadPermission()
    }

    /** Keeps content clear of status and navigation bars under edge-to-edge. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Android 13 needs only READ_MEDIA_IMAGES; no legacy storage branch. */
    private fun ensureReadPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            log("Permesso già concesso.")
        } else {
            scanButton.isEnabled = false
            requestReadPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    /** Step 1: list candidate photos and time the query. */
    private fun runScan() {
        scanButton.isEnabled = false
        log("Scansione di $SOURCE_RELATIVE_PATH …")

        thread {
            val startedAt = System.currentTimeMillis()
            val outcome = repository.queryPhotos(SOURCE_RELATIVE_PATH, BATCH_SIZE)
            val elapsed = System.currentTimeMillis() - startedAt
            runOnUiThread { reportScanResult(outcome, elapsed) }
        }
    }

    /** Prints scan outcome and enables the move step when there is work to do. */
    private fun reportScanResult(outcome: Result<List<MediaStoreRepository.Photo>>, elapsed: Long) {
        outcome.fold(
            onSuccess = { photos ->
                scannedPhotos = photos
                val megabytes = photos.sumOf { it.sizeBytes } / BYTES_PER_MEGABYTE
                log("Trovate ${photos.size} foto in $elapsed ms (%.1f MB totali).".format(megabytes))
                moveButton.isEnabled = photos.isNotEmpty()
                if (photos.isEmpty()) log("Nessuna foto: controlla il percorso sorgente.")
            },
            onFailure = { error -> log("SCANSIONE FALLITA: ${error.message}") }
        )
        scanButton.isEnabled = true
    }

    /** Step 2: one system consent dialog for the entire batch. */
    private fun startMoveWithConsent() {
        moveButton.isEnabled = false
        try {
            val intentSender = mover.buildWriteConsent(scannedPhotos)
            requestWriteConsent.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (error: Exception) {
            log("RICHIESTA CONSENSO FALLITA: ${error.message}")
            moveButton.isEnabled = true
        }
    }

    /** Step 3: the measurement that decides whether native is worth it. */
    private fun runBatchMove() {
        thread {
            val result = mover.moveAll(scannedPhotos, DESTINATION_RELATIVE_PATH)
            runOnUiThread { reportBatchResult(result) }
        }
    }

    /** Prints the batch numbers and the verdict they imply. */
    private fun reportBatchResult(result: BatchMover.BatchResult) {
        log("--- RISULTATO ---")
        log("Spostate ${result.succeeded}/${result.requested}, fallite ${result.failed}")
        log("Tempo totale: ${result.totalMillis} ms")
        log(
            "Media per foto: %.1f ms · picco singolo: %d ms"
                .format(result.averageMillis, result.slowestMillis)
        )
        log("Throughput apparente: %.0f MB/s".format(result.apparentMegabytesPerSecond))
        result.firstError?.let { log("Primo errore: $it") }
        log(verdictFor(result))

        moveButton.isEnabled = true
        scannedPhotos = emptyList()
    }

    /** Turns the raw timing into the go / no-go call for the native rewrite. */
    private fun verdictFor(result: BatchMover.BatchResult): String = when {
        result.succeeded == 0 -> "VERDETTO: nessuno spostamento riuscito, misura non valida."
        result.totalMillis < RENAME_THRESHOLD_MILLIS -> "VERDETTO: RENAME. Il nativo vince, si procede."
        else -> "VERDETTO: probabile COPIA. La premessa cade, fermarsi e ridiscutere."
    }

    /** Appends a timestamped line to the on-screen log. */
    private fun log(message: String) {
        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ITALY).format(Date())
        logView.append("[$timestamp] $message\n")
    }
}
