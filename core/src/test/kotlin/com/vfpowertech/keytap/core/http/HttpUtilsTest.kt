package com.vfpowertech.keytap.core.http

import org.junit.Test
import kotlin.test.assertEquals

class HttpUtilsTest {
    @Test
    fun `toQueryString should encode both its keys and values`() {
        assertEquals("k+k=v+v", toQueryString(listOf("k k" to "v v")))
    }

    @Test
    fun `toQueryString should not have a trailing &`() {
        assertEquals("k=v", toQueryString(listOf("k" to "v")))
    }

    @Test
    fun `toQueryString should encode unicode characters to UTF8`() {
        assertEquals("k=%e5%ad%94%e6%98%8e", toQueryString(listOf("k" to "孔明")).toLowerCase())
    }
}