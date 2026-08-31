package com.sieve.transcode

import com.sieve.transcode.presets.CustomPreset
import com.sieve.transcode.presets.CustomPresetService
import com.sieve.transcode.presets.CustomPresetStore
import com.sieve.transcode.presets.ImportResult
import com.sieve.transcode.presets.PresetBundle
import com.sieve.transcode.presets.RawCustomPreset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCustomPresetStore(private val failOn: (CustomPreset) -> Boolean = { false }) : CustomPresetStore {
    val saved = mutableListOf<CustomPreset>()
    override suspend fun save(p: CustomPreset) {
        if (failOn(p)) throw RuntimeException("boom")
        saved.add(p)
    }
    override suspend fun getAll(): List<CustomPreset> = saved.toList()
    override suspend fun delete(id: String) {
        saved.removeAll { it.id == id }
    }
}

/** Task 14: import/export with partial-import safety and opaque config round-trip. */
class CustomPresetServiceTest {

    private val jsonEnc = Json { prettyPrint = false }

    private fun bundleJson(presets: List<RawCustomPreset>, kind: String = "transcode-presets"): String =
        jsonEnc.encodeToString(PresetBundle(kind = kind, exportedAt = "2026-01-01T00:00:00Z", presets = presets))

    private fun svc(store: FakeCustomPresetStore, nowMillis: () -> Long = { 0L }) =
        CustomPresetService(store, nowIso = { "2026-01-01T00:00:00Z" }, nowMillis = nowMillis)

    @Test fun P2_badJsonReturnsErr() = runTest {
        val r = svc(FakeCustomPresetStore()).import("not json", emptyList())
        assertTrue(r is ImportResult.Err && r.error.startsWith("Not valid JSON"))
    }

    @Test fun P3_wrongKindRejected() = runTest {
        val r = svc(FakeCustomPresetStore()).import(bundleJson(emptyList(), kind = "other"), emptyList())
        assertTrue(r is ImportResult.Err)
    }

    @Test fun P6_collisionRekeys() = runTest {
        val store = FakeCustomPresetStore()
        val raw = bundleJson(listOf(RawCustomPreset("x", "N", "custom", "{}")))
        val r = svc(store, nowMillis = { 111L }).import(raw, listOf(CustomPreset("x", "old", "custom", "{}")))
        assertTrue(r is ImportResult.Ok && r.imported == 1)
        assertEquals("x-imp-111-0", store.saved.last().id)
    }

    @Test fun P7_missingNameSkipped() = runTest {
        val store = FakeCustomPresetStore()
        val raw = bundleJson(
            listOf(RawCustomPreset("a", null, "custom", "{}"), RawCustomPreset("b", "B", "custom", "{}")),
        )
        val r = svc(store).import(raw, emptyList())
        assertEquals(1, (r as ImportResult.Ok).imported)
        assertEquals("b", store.saved.single().id)
    }

    @Test fun P11_storeFailureSkipsOnlyThatEntry() = runTest {
        val store = FakeCustomPresetStore(failOn = { it.id == "boom" })
        val raw = bundleJson(
            listOf(RawCustomPreset("ok1", "A", "custom", "{}"),
                RawCustomPreset("boom", "B", "custom", "{}"),
                RawCustomPreset("ok2", "C", "custom", "{}")),
        )
        val r = svc(store).import(raw, emptyList())
        assertEquals(2, (r as ImportResult.Ok).imported)
        assertEquals(listOf("ok1", "ok2"), store.saved.map { it.id })
    }

    @Test fun P12_roundtripConfigByteIdentical() = runTest {
        val store = FakeCustomPresetStore()
        val service = svc(store)
        val out = service.export(listOf(CustomPreset("a", "A", "custom", """{"crf":23}""")))
        service.import(out, emptyList())
        assertEquals("""{"crf":23}""", store.saved.last().config) // never re-parsed
    }

    @Test fun validBundleImportsAll() = runTest {
        val store = FakeCustomPresetStore()
        val raw = bundleJson(listOf(RawCustomPreset("a", "A", "custom", "{}"), RawCustomPreset("b", "B", "custom", "{}")))
        val r = svc(store).import(raw, emptyList())
        assertEquals(2, (r as ImportResult.Ok).imported)
    }
}
