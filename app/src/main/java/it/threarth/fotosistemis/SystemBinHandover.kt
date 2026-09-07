package it.threarth.fotosistemis

import android.app.Activity
import android.app.AlertDialog
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.review.ReviewSession

/**
 * Hands photos this app cannot move to Android's own bin.
 *
 * Two situations end here and they are the same situation: a photo inside
 * another app's folder cannot be moved, only copied, so after the copy the
 * picture is on the phone twice and the original is the half taking the
 * room. Whether the photo was being filed or thrown away, the original has
 * nowhere to go but Android's bin.
 *
 * Which is a different bin, and is named as one every time: it is not the
 * app's, it empties itself after thirty days, and it is recovered from the
 * gallery. Nothing is destroyed here — Android is asked, and it asks the
 * user, and only then does anything happen.
 *
 * The handover is written down once it is accepted. These photos never move,
 * so their own paths would go on saying the work is still to do, and every
 * check would offer them again for ever.
 */
class SystemBinHandover(
    private val activity: AppCompatActivity,
    private val photoSource: MediaStorePhotoSource,
    private val stateRepository: PhotoStateRepository,
    private val onFinished: () -> Unit
) {

    private var pending: List<ReviewSession.PendingMove> = emptyList()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val handed = pending
        pending = emptyList()
        val accepted = result.resultCode == Activity.RESULT_OK

        if (accepted) {
            for (move in handed) {
                stateRepository.recordSystemBin(
                    move.photo.photoId, move.photo.relativePath, move.photo.displayName
                )
            }
        }
        toast(
            activity.getString(
                if (accepted) R.string.system_bin_done else R.string.system_bin_refused,
                handed.size
            )
        )
        onFinished()
    }

    /**
     * States what is about to happen and waits.
     *
     * [copied] says how many of them are originals whose copy is already
     * filed, because that changes what the user needs to know: the picture
     * is not being given up, only its second copy.
     */
    fun offer(photos: List<ReviewSession.PendingMove>, copied: Int = 0) {
        if (photos.isEmpty()) return
        pending = photos

        AlertDialog.Builder(activity)
            .setTitle(R.string.system_bin_title)
            .setMessage(
                if (copied > 0) activity.getString(R.string.system_bin_copied, copied, photos.size)
                else activity.getString(R.string.system_bin_message, photos.size)
            )
            .setPositiveButton(R.string.system_bin_do) { _, _ -> ask() }
            // Declining is an answer too, and whoever asked is waiting for
            // one: a grid that stayed open on a refusal had nothing left
            // to show.
            .setNegativeButton(R.string.action_cancel) { _, _ -> decline() }
            .setOnCancelListener { decline() }
            .show()
    }

    private fun decline() {
        pending = emptyList()
        onFinished()
    }

    /** Android asks; the app only proposes, and never destroys. */
    private fun ask() {
        if (pending.isEmpty()) return
        try {
            launcher.launch(
                IntentSenderRequest.Builder(
                    MediaStore.createTrashRequest(
                        activity.contentResolver,
                        pending.map { photoSource.uriFor(it.photo) },
                        true
                    ).intentSender
                ).build()
            )
        } catch (error: Exception) {
            toast(activity.getString(R.string.message_error, error.message.orEmpty()))
            decline()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
