package com.retroid.translator.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [BackupBundle]'s type-tagging - no Android framework
 * or Context involved (see that file's own doc comment), so no Robolectric
 * needed here, matching [com.retroid.translator.engine.VoskResultParsingTest]'s
 * existing precedent for org.json-only logic.
 *
 * The real point of these tests: SharedPreferences.Editor has 6 distinct
 * value types, but plain JSON collapses Int/Long into one number type and
 * has no Set at all - a naive encode/decode would silently produce the
 * WRONG type back out (e.g. an Int round-tripping as a Long) without ever
 * throwing, exactly this app's own already-documented "every line executes
 * while still returning wrong values" bug class. Every assertion below
 * checks the real Kotlin runtime type of the decoded value
 * (`is Int`/`is Long`), not just value equality, since `1 == 1L` is true in
 * Kotlin and would hide exactly this bug.
 */
class BackupBundleTest {

    @Test
    fun `boolean round-trips as a real Boolean`() {
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue(true))
        assertTrue(decoded is Boolean)
        assertEquals(true, decoded)
    }

    @Test
    fun `int round-trips as a real Int, not a Long`() {
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue(42))
        assertTrue("expected Int, got ${decoded::class.java}", decoded is Int)
        assertEquals(42, decoded)
    }

    @Test
    fun `long round-trips as a real Long, not an Int`() {
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue(1_700_000_000_000L))
        assertTrue("expected Long, got ${decoded::class.java}", decoded is Long)
        assertEquals(1_700_000_000_000L, decoded)
    }

    @Test
    fun `float round-trips as a real Float with its real precision`() {
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue(3.14f))
        assertTrue(decoded is Float)
        assertEquals(3.14f, decoded as Float, 0.0001f)
    }

    @Test
    fun `string round-trips unchanged`() {
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue("single_circle"))
        assertEquals("single_circle", decoded)
    }

    @Test
    fun `string set round-trips as a real Set with every element, order-independent`() {
        val original = setOf("es", "fr", "de")
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue(original))
        assertTrue(decoded is Set<*>)
        assertEquals(original, decoded)
    }

    @Test
    fun `empty string set round-trips as a real empty Set, not null or absent`() {
        val decoded = BackupBundle.decodePrefValue(BackupBundle.encodePrefValue(emptySet<String>()))
        assertTrue(decoded is Set<*>)
        assertTrue((decoded as Set<*>).isEmpty())
    }

    @Test
    fun `encoded value carries an explicit type tag distinguishing int from long`() {
        // The actual mechanism under test: without this tag, a plain
        // JSONObject.put(k, v) would make 42 and 42L indistinguishable once
        // serialized to text and re-parsed.
        assertEquals("int", BackupBundle.encodePrefValue(42).getString("type"))
        assertEquals("long", BackupBundle.encodePrefValue(42L).getString("type"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoding an unsupported type throws rather than silently mis-tagging it`() {
        BackupBundle.encodePrefValue(listOf("not", "a", "real", "prefs", "type"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decoding an unknown type tag throws rather than silently guessing`() {
        BackupBundle.decodePrefValue(JSONObject().apply { put("type", "nonexistent"); put("value", "x") })
    }

    @Test
    fun `encoded value survives a real JSON string round-trip, not just an in-memory one`() {
        // toString() + re-parse, the same path a real export/import Uri
        // round-trip takes - not just object identity within one process.
        val reparsed = JSONObject(BackupBundle.encodePrefValue(7).toString())
        assertEquals(7, BackupBundle.decodePrefValue(reparsed))
    }
}
