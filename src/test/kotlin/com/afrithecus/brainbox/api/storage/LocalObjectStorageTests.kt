package com.afrithecus.brainbox.api.storage

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun rejectsKeysThatCouldEscapeTheRoot(@TempDir directory: Path) {
        val storage = LocalObjectStorage(directory)
        assertFailsWith<IllegalArgumentException> { storage.put("../escape", byteArrayOf(1), "text/plain") }
        assertFailsWith<IllegalArgumentException> { storage.get("a/b") }
    }
}
