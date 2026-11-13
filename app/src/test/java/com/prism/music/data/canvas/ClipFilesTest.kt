package com.prism.music.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Canvas's kept loops (4.2): recent pruned by use to a budget, saved kept until removed. */
class ClipFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private var budget = 3_000L
    private val files by lazy { ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { budget }) }

    private fun put(id: String, size: Int, saved: Boolean = false, height: Int = 720, start: Long = 10_000, end: Long = 20_000, capped: Boolean = false): ClipFiles.Kept {
        val t = files.temp(saved)
        t.writeBytes(ByteArray(size))
        return files.keep(t, id, height, 136, start, end, capped, saved)
    }

    @Test fun namesAreSafeAndReadBack() {
        val k = put("fcnDmrtj6Sk", 10)
        assertEquals("fcnDmrtj6Sk.720.136.10000-20000.mp4", k.file.name)
        assertEquals(k, ClipFiles.parse(k.file, false))
        assertEquals("ab-_c", ClipFiles.safeId("a/b..-_\\c"))
        val c = put("a_b-c", 10, capped = true)
        assertEquals("a_b-c.720.136.10000-20000.c.mp4", c.file.name)
        assertTrue(ClipFiles.parse(c.file, false)!!.capped)
    }

    @Test fun findCoversTheAskedSeconds() {
        put("vid", 10, start = 10_000, end = 20_000)
        assertNotNull(files.find("vid", 10_000, 20_000))
        assertNotNull("inside", files.find("vid", 12_000, 18_000))
        assertNull("starts before", files.find("vid", 9_000, 18_000))
        assertNull("runs past", files.find("vid", 12_000, 21_000))
        assertNull("other video", files.find("vid2", 12_000, 18_000))
        assertNull("too low", files.find("vid", 12_000, 18_000, minHeight = 1080))
        put("cap", 10, start = 0, end = 20_000, capped = true)
        assertNotNull("a capped fetch answers past its end (else the same fragments are fetched again)", files.find("cap", 5_000, 30_000))
    }

    @Test fun findPrefersHigherThenSaved() {
        put("v", 10, height = 720)
        put("v", 10, height = 1080, saved = false)
        put("v", 10, height = 1080, saved = true)
        val best = files.find("v", 10_000, 20_000)!!
        assertEquals(1080, best.height)
        assertTrue(best.saved)
        assertNull(files.find("v", 10_000, 20_000, minHeight = 1440))
        assertEquals(3, files.of("v").size)
    }

    @Test fun pruneDropsLeastRecentlyUsed() {
        val a = put("aaaaaaaaaaa", 1_000)
        val b = put("bbbbbbbbbbb", 1_000)
        val c = put("ccccccccccc", 1_000)
        val now = System.currentTimeMillis()
        a.file.setLastModified(now - 30_000)
        b.file.setLastModified(now - 20_000)
        c.file.setLastModified(now - 10_000)
        // a is used again: b is now the oldest.
        assertNotNull(files.find("aaaaaaaaaaa", 10_000, 20_000))
        put("ddddddddddd", 1_000)
        assertTrue(files.recentBytes <= budget)
        assertTrue(a.file.exists())
        assertFalse("least recently used goes", b.file.exists())
        assertTrue(c.file.exists())
    }

    @Test fun theLoopJustKeptIsNeverPruned() {
        budget = 500
        val old = put("oldoldold01", 400)
        val big = put("bigbigbig01", 1_000)
        assertTrue("kept though over the budget: it's about to play", big.file.exists())
        assertFalse(old.file.exists())
    }

    @Test fun savedSurvivesPruneAndClear() {
        val s = put("saved00000a", 5_000, saved = true)
        put("recent0000a", 1_000)
        budget = 0
        files.prune()
        assertTrue(s.file.exists())
        assertEquals(0L, files.recentBytes)
        put("recent0000b", 1_000)
        files.clearRecent()
        assertTrue(s.file.exists())
        assertEquals(5_000L, files.savedBytes)
        assertEquals(0L, files.recentBytes)
    }

    @Test fun moveBetweenFoldersAndDelete() {
        val r = put("movemove001", 1_000)
        val s = files.save(r)
        assertTrue(s.saved)
        assertFalse(r.file.exists())
        assertTrue(s.file.exists())
        assertEquals(1_000L, files.savedBytes)
        files.unsave("movemove001")
        assertEquals(0L, files.savedBytes)
        assertEquals(1_000L, files.recentBytes)
        put("movemove001", 500, saved = true, start = 0, end = 5_000)
        files.delete("movemove001")
        assertTrue(files.of("movemove001").isEmpty())
    }
}
