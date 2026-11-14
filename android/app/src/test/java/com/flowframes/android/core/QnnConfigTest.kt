package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QnnConfigTest {

    @Test
    fun defaultsToHtpByType() {
        val options = QnnConfig().providerOptions()
        assertEquals("htp", options["backend_type"])
        assertEquals("1", options["enable_htp_fp16_precision"])
        assertFalse(options.containsKey("backend_path"))
    }

    @Test
    fun explicitLibraryPathReplacesTheBackendType() {
        val options = QnnConfig("/data/app/lib/arm64/libQnnHtp.so").providerOptions()
        assertEquals("/data/app/lib/arm64/libQnnHtp.so", options["backend_path"])
        assertFalse(options.containsKey("backend_type"))
    }
}
