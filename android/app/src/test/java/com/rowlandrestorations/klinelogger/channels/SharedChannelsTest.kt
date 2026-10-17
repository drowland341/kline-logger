package com.rowlandrestorations.klinelogger.channels

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every channel list in the repo's shared/channels folder must load in this app (the iOS and Windows apps read them too). */
class SharedChannelsTest {
    private val dir = File("../../shared/channels")      // unit tests run from android/app

    private fun files(): List<File> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun everySharedChannelListLoads() {
        assertTrue("no channel lists in ${dir.absolutePath}", files().isNotEmpty())
        for (f in files()) {
            val text = f.readText()
            val doc = JSONObject(text)
            assertTrue("${f.name}: manufacturer missing", doc.optString("manufacturer").isNotBlank())
            assertTrue("${f.name}: defaults_revision missing", doc.optInt("defaults_revision", 0) > 0)
            assertTrue("${f.name}: defaults_note missing", doc.optString("defaults_note").isNotBlank())
            val pids = parsePidDefs(text)
            assertTrue("${f.name}: no channels", pids.isNotEmpty())
            assertEquals("${f.name}: duplicate PIDs", pids.size, pids.map { it.pid }.toSet().size)
            for (p in pids) p.decode(byteArrayOf(0x01, 0x23))     // every formula compiles and runs
        }
    }

    @Test
    fun bundledDefaultsExist() {
        assertTrue(File(dir, DEFAULT_CHANNELS).isFile)
    }
}
