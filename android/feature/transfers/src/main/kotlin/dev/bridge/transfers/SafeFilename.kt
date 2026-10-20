package dev.bridge.transfers

import java.io.File

object SafeFilename {
    const val MAX_FILENAME_LENGTH = 255
    const val DEFAULT_FALLBACK_NAME = "unnamed_file"

    /**
     * Sanitizes a remote filename to prevent directory traversal and filesystem attacks.
     */
    fun sanitize(rawName: String): String {
        val normalized = rawName.replace("\\", "/")
        var name = File(normalized).name
            .filter { it.code in 32..126 } // Printable ASCII only

        while (name.contains("..")) {
            name = name.replace("..", "")
        }

        while (name.startsWith(".")) {
            name = name.removePrefix(".")
        }

        name = name.trim()
        if (name.isEmpty() || name == "_") {
            name = DEFAULT_FALLBACK_NAME
        }

        if (name.length > MAX_FILENAME_LENGTH) {
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.', "")
            name = if (ext.isEmpty()) {
                name.take(MAX_FILENAME_LENGTH)
            } else {
                val allowedBase = MAX_FILENAME_LENGTH - ext.length - 1
                base.take(maxOf(1, allowedBase)) + "." + ext
            }
        }

        return name
    }

    /**
     * Resolves a non-colliding destination file in the target directory by appending ` (1)`, ` (2)`, etc.
     */
    fun uniqueDestination(directory: File, filename: String): File {
        val cleanName = sanitize(filename)
        var destination = File(directory, cleanName)
        if (!destination.exists()) {
            return destination
        }

        val baseName = cleanName.substringBeforeLast('.', "")
        val ext = cleanName.substringAfterLast('.', "")
        var counter = 1

        while (destination.exists()) {
            val newName = if (ext.isEmpty() || ext == cleanName) {
                "$baseName ($counter)"
            } else {
                "$baseName ($counter).$ext"
            }
            destination = File(directory, newName)
            counter++
        }

        return destination
    }
}
