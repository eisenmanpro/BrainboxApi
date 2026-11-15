package com.afrithecus.brainbox.api.report

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic zip bundle for multi-artifact report jobs (D19): per-class tables with
 * "All" render one PDF per class, and the job stores a single zip so polling, history
 * and the signed download route keep working unchanged.
 */
object ReportBundles {

    /** Entries are sorted by name and stamped with a fixed time so the bytes are reproducible. */
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        require(entries.isNotEmpty()) { "Bundle requires at least one file" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.sortedBy { it.first }.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
