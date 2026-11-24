package com.finance.tracker.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebOriginTest {
    @Test
    fun `accepts private IPv4 HTTP origins with an explicit port`() {
        for (origin in listOf(
            "http://10.0.0.1:8080",
            "http://172.16.0.1:8080",
            "http://172.31.255.254:8080",
            "http://192.168.1.111:8080",
        )) {
            assertEquals(origin, validateWebOrigin(origin))
        }
    }

    @Test
    fun `keeps localhost HTTP and HTTPS origins`() {
        assertEquals("http://127.0.0.1:5186", validateWebOrigin("http://127.0.0.1:5186"))
        assertEquals("http://localhost:5186", validateWebOrigin("http://localhost:5186"))
        assertEquals("https://finance.example.com", validateWebOrigin("https://finance.example.com/"))
    }

    @Test
    fun `rejects public HTTP and non-private IPv4 literals`() {
        for (origin in listOf(
            "http://8.8.8.8:8080",
            "http://172.15.0.1:8080",
            "http://172.32.0.1:8080",
            "http://192.169.1.1:8080",
            "http://10.0.0.999:8080",
            "http://010.0.0.1:8080",
            "http://finance.example.com:8080",
            "http://127.0.0.1:0",
            "http://localhost:0",
            "https://finance.example.com:0",
            "http://127.0.0.1:08080",
            "https://finance.example.com:080",
        )) {
            assertFailsWith<IllegalArgumentException>(origin) { validateWebOrigin(origin) }
        }
    }

    @Test
    fun `rejects private HTTP origins without a port or with extra URL parts`() {
        for (origin in listOf(
            "http://192.168.1.111",
            "http://192.168.1.111:0",
            "http://192.168.1.111:8080/path",
            "http://192.168.1.111:8080?q=1",
            "http://192.168.1.111:8080#fragment",
            "http://user:password@192.168.1.111:8080",
        )) {
            assertFailsWith<IllegalArgumentException>(origin) { validateWebOrigin(origin) }
        }
    }
}
