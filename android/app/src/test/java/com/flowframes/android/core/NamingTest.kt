package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Date

class NamingTest {

    private val date = Date(1_700_000_000_000L) // 2023-11-14 22:13 UTC

    @Test
    fun `default pattern expansion`() {
        val name = Naming.build("{name}_{factor}x_{fps}fps", "My Video.mkv", 2, 59.94, "h264", date)
        assertEquals("My Video_2x_59.94fps.mp4", name)
    }

    @Test
    fun `empty pattern falls back to the default`() {
        assertEquals("clip_4x_30fps.mp4", Naming.build("", "clip.mp4", 4, 30.0, "hevc", date))
    }

    @Test
    fun `trailing zeros are trimmed from fps`() {
        assertEquals("a_2x_60fps.mp4", Naming.build("{name}_{factor}x_{fps}fps", "a.mp4", 2, 60.0, "h264", date))
    }

    @Test
    fun `codec and date tokens`() {
        val name = Naming.build("{name}_{codec}_{date}", "x.mp4", 2, 30.0, "HEVC", date)
        assert(name.startsWith("x_hevc_"))
        assert(name.endsWith(".mp4"))
    }

    @Test
    fun `illegal characters are replaced`() {
        val name = Naming.build("a:b/c?{name}", "clip.mp4", 2, 30.0, "h264", date)
        assertFalse(name.contains(":"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains("?"))
    }

    @Test
    fun `collision appends a counter`() {
        val existing = setOf("out.mp4", "out_2.mp4")
        assertEquals("out_3.mp4", Naming.deduplicate("out.mp4") { it in existing })
        assertEquals("new.mp4", Naming.deduplicate("new.mp4") { it in existing })
    }
}
