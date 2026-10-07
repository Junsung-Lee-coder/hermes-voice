package com.rumi.hermesvoice.core.diag

import java.io.File

/** What the Android share of a report may hand out: only a report file that sits directly in the app's own diag cache folder. */
object DiagShare {
    const val CACHE_SUBDIR = "diag"
    const val AUTHORITY_SUFFIX = ".diagshare"
    const val CONTENT_TYPE = DiagFileStore.CONTENT_TYPE

    fun authority(applicationId: String) = applicationId + AUTHORITY_SUFFIX

    /** [file] is shareable only if it is a report this store names, directly inside [cacheDir]/diag (no other folder, link or traversal). */
    fun shareable(cacheDir: File, file: File): Boolean = try {
        // toRealPath resolves links on every platform (File.canonicalFile does not on Windows).
        val root = File(cacheDir, CACHE_SUBDIR).toPath().toRealPath()
        val target = file.toPath().toRealPath()
        target.parent == root && DiagFileStore.NAME.matches(target.fileName.toString()) && java.nio.file.Files.isRegularFile(target)
    } catch (_: java.io.IOException) {
        false
    } catch (_: java.nio.file.InvalidPathException) {
        false
    }
}
