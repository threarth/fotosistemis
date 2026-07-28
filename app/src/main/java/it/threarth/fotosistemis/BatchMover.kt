package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore

/**
 * Runs a batch of moves and measures how long they take.
 *
 * Kept separate from MediaStoreRepository so that timing logic never
 * contaminates the storage calls being measured.
 */
class BatchMover(private val context: Context, private val repository: MediaStoreRepository) {

    /** Outcome of a measured batch. Timings are wall clock, in milliseconds. */
    data class BatchResult(
        val requested: Int,
        val succeeded: Int,
        val failed: Int,
        val totalMillis: Long,
        val slowestMillis: Long,
        val totalBytes: Long,
        val firstError: String?
    ) {

        /** Average cost per photo; the headline number of the prototype. */
        val averageMillis: Double
            get() = if (succeeded == 0) 0.0 else totalMillis.toDouble() / succeeded

        /**
         * Apparent throughput. A real rename is metadata-only, so this figure
         * should be absurdly high. A value close to actual storage bandwidth
         * means the provider is copying bytes underneath.
         */
        val apparentMegabytesPerSecond: Double
            get() {
                if (totalMillis == 0L) return 0.0
                return (totalBytes.toDouble() / BYTES_PER_MEGABYTE) /
                        (totalMillis / MILLIS_PER_SECOND)
            }

        private companion object {
            const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
            const val MILLIS_PER_SECOND = 1000.0
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }

    /**
     * Builds the system consent dialog covering every photo in [photos].
     *
     * A single IntentSender for the whole batch is what makes the queued
     * "apply changes" model workable: one prompt, not one per file.
     */
    fun buildWriteConsent(photos: List<MediaStoreRepository.Photo>): IntentSender =
        MediaStore.createWriteRequest(context.contentResolver, photos.map { it.uri }).intentSender

    /**
     * Moves every photo to [destinationRelativePath], timing each call.
     * Must run off the main thread.
     */
    fun moveAll(
        photos: List<MediaStoreRepository.Photo>,
        destinationRelativePath: String
    ): BatchResult {
        var succeeded = 0
        var failed = 0
        var slowestMillis = 0L
        var totalBytes = 0L
        var firstError: String? = null

        val startedAt = System.nanoTime()
        for (photo in photos) {
            val itemStartedAt = System.nanoTime()
            val outcome = repository.move(photo, destinationRelativePath)
            val itemMillis = (System.nanoTime() - itemStartedAt) / NANOS_PER_MILLI

            if (itemMillis > slowestMillis) slowestMillis = itemMillis

            outcome.fold(
                onSuccess = {
                    succeeded++
                    totalBytes += photo.sizeBytes
                },
                onFailure = { error ->
                    failed++
                    if (firstError == null) {
                        firstError = "${photo.displayName}: " +
                                (error.message ?: error::class.java.simpleName)
                    }
                }
            )
        }
        val totalMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

        return BatchResult(
            requested = photos.size,
            succeeded = succeeded,
            failed = failed,
            totalMillis = totalMillis,
            slowestMillis = slowestMillis,
            totalBytes = totalBytes,
            firstError = firstError
        )
    }
}
