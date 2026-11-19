package com.localmodels.app.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private fun entry(id: String = "test", name: String = "model.gguf", bytes: Long = 100) =
        """[{"id":"$id","vision":true,"files":[{"name":"$name","role":"model","bytes":$bytes,"url":"https://example.com/model.gguf"}]}]"""

    @Test fun requiresRealProjector() {
        val model = Catalog.parse(entry()).single()
        assertFalse(model.vision)
        assertEquals(100L, model.bytes)
    }

    @Test fun rejectsInvalidFilePathsAndOversizeModels() {
        for (raw in listOf(entry(id = ".."), entry(name = "../model.gguf"), entry(bytes = Catalog.MAX_MODEL_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { Catalog.parse(raw) }
        }
    }

    @Test fun acceptsModelsUpToTenGiBUsingLongSizes() {
        assertEquals(5L * 1024 * 1024 * 1024, Catalog.parse(entry(bytes = 5L * 1024 * 1024 * 1024)).single().bytes)
        assertEquals(Catalog.MAX_MODEL_BYTES, Catalog.parse(entry(bytes = Catalog.MAX_MODEL_BYTES)).single().bytes)
    }

    @Test fun builtinCatalogHasValidSizesVisionAndRamHints() {
        val models = Catalog.parse(File("src/main/assets/catalog.json").readText())
        assertEquals(15, models.size)
        assertEquals(models.size, models.map { it.id }.distinct().size)
        assertTrue(models.any { it.bytes > 4L * 1024 * 1024 * 1024 })
        assertTrue(models.all { it.bytes in 1..Catalog.MAX_MODEL_BYTES && it.recommendedRamGb > 0 })
        assertTrue(models.all { !it.vision || it.mmproj != null })
        assertEquals(2, models.first { it.id == "gemma3-12b" }.files.size)
    }

    @Test fun totalLimitIncludesProjector() {
        val sixGiB = 6L * 1024 * 1024 * 1024
        val raw = """[{"id":"large","files":[{"name":"model.gguf","role":"model","bytes":$sixGiB,"url":"https://example.com/m"},{"name":"mmproj.gguf","role":"mmproj","bytes":$sixGiB,"url":"https://example.com/p"}]}]"""
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(raw) }
    }
}
