package it.threarth.fotosistemis.core.model

/**
 * Where a photo came into the archive from.
 *
 * Worth recording because it changes what a photo is: one that arrived
 * through a chat app has been recompressed and stripped, and may well be a
 * second copy of one already here. Knowing that at a glance is what lets
 * those copies be hunted later.
 */
object PhotoOrigin {

    /** Recorded in the stamp, and searchable in a file name. */
    const val WHATSAPP = "whatsapp"

    /** WhatsApp's own media folder, wherever it sits under Android/media. */
    private const val WHATSAPP_FOLDER = "com.whatsapp"

    /** The name WhatsApp gives what it saves: IMG-20260904-WA0028.jpg. */
    private val WHATSAPP_NAME = Regex("""(?:^|[^A-Za-z])IMG-\d{8}-WA\d+""", RegexOption.IGNORE_CASE)

    /**
     * The origin of a photo, or null when nothing says.
     *
     * Both the folder and the name are consulted: the folder is the surer
     * sign but disappears the moment the photo is moved out, while the name
     * travels with the file and survives everything.
     */
    fun of(relativePath: String, displayName: String): String? {
        if (relativePath.contains(WHATSAPP_FOLDER, ignoreCase = true)) return WHATSAPP
        if (WHATSAPP_NAME.containsMatchIn(displayName)) return WHATSAPP

        return null
    }
}
