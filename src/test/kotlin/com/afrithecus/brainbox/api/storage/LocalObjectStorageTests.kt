package com.afrithecus.brainbox.api.storage

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalObjectStorageTests {

    @Test
    fun roundTripsAndDeleteIsRepeatSafe(@TempDir directory: Path) {
        val storage = LocalObjectStorage(directory)
        val bytes = byteArrayOf(1, 2, 3, 4)
        storage.put("abc.png", bytes, "image/png")
        assertContentEquals(bytes, storage.get("abc.png"))
        storage.delete("abc.png")
        assertNull(storage.get("abc.png"))
        storage.delete("abc.png")
    }

    @Test
    fun missingObjectIsNull(@TempDir directory: Path) {
        assertNull(LocalObjectStorage(directory).get("nope.pdf"))
    }

    /**
     * Traversal stays refused, but a namespaced key is legitimate: quarantine stores an
     * object under `quarantine/<key>` so it can never be served at its original URL.
     */
    @Test
    fun refusesTraversalButAllowsNamespacedKeys(@TempDir directory: Path) {
        val storage = LocalObjectStorage(directory)
        assertFailsWith<IllegalArgumentException> { storage.put("../escape", byteArrayOf(1), "text/plain") }
        assertFailsWith<IllegalArgumentException> { storage.put("nested/../../escape", byteArrayOf(1), "text/plain") }
        assertFailsWith<IllegalArgumentException> { storage.put("/absolute", byteArrayOf(1), "text/plain") }
        assertFailsWith<IllegalArgumentException> { storage.put("back\\slash", byteArrayOf(1), "text/plain") }
        assertFailsWith<IllegalArgumentException> { storage.get("../escape") }

        val bytes = byteArrayOf(9, 8, 7)
        storage.put("quarantine/abc.png", bytes, "image/png")
        assertContentEquals(bytes, storage.get("quarantine/abc.png"))
        assertEquals(3L, storage.head("quarantine/abc.png")?.sizeBytes)
        storage.delete("quarantine/abc.png")
        assertNull(storage.get("quarantine/abc.png"))
    }

    /** The presign confirm step HEADs before it reads, so the local backend must answer it. */
    @Test
    fun headAndPrefixReadsBehaveLikeTheObjectStore(@TempDir directory: Path) {
        val storage = LocalObjectStorage(directory)
        storage.put("doc.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46, 1, 2, 3, 4), "application/pdf")

        val metadata = storage.head("doc.pdf")
        assertEquals(8L, metadata?.sizeBytes)
        assertNull(storage.head("missing.pdf"))

        assertContentEquals(byteArrayOf(0x25, 0x50, 0x44, 0x46), storage.getRange("doc.pdf", 0L, 4))
        assertContentEquals(byteArrayOf(1, 2), storage.getRange("doc.pdf", 4L, 2))
        assertContentEquals(ByteArray(0), storage.getRange("doc.pdf", 99L, 4))
        assertNull(storage.getRange("missing.pdf", 0L, 4))
    }
}
