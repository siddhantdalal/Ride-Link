package com.siddhantdalal.bikelink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

class CommandServerTest {

    private lateinit var server: CommandServer
    private var lastRequest: HttpRequest? = null

    @Before
    fun startServer() {
        server = CommandServer(0, { request ->
            lastRequest = request
            HttpResponse(200, """{"ok":true,"message":"héllo"}""")
        })
        server.start()
    }

    @After
    fun stopServer() = server.stop()

    /** Sends raw bytes and returns the full raw response. */
    private fun exchange(raw: String): String =
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun `reads method, path without query, headers and body`() {
        val body = """{"to":"Phoenix Mall"}"""
        val response = exchange(
            "POST /navigate?x=1 HTTP/1.1\r\nX-Bike-Code: ABCD-EFGH\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body",
        )
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.endsWith("""{"ok":true,"message":"héllo"}"""))
        val request = lastRequest!!
        assertEquals("POST", request.method)
        assertEquals("/navigate", request.path)
        assertEquals("ABCD-EFGH", request.header("x-bike-code"))
        assertEquals(body, request.body)
    }

    @Test
    fun `content length counts utf-8 bytes`() {
        val response = exchange("GET /status HTTP/1.1\r\n\r\n")
        // The body is 29 characters, but "é" takes 2 bytes in UTF-8, so it's 30 bytes.
        assertTrue(response.contains("Content-Length: 30\r\n"))
    }

    @Test
    fun `garbage gets a 400 and the server keeps serving`() {
        assertTrue(exchange("hello there\r\n\r\n").startsWith("HTTP/1.1 400 "))
        assertTrue(exchange("GET /status HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200 "))
    }

    @Test
    fun `oversized body is rejected`() {
        assertTrue(exchange("POST /navigate HTTP/1.1\r\nContent-Length: 999999\r\n\r\n").startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun `handler crash becomes a 500 and the server keeps serving`() {
        server.stop()
        var calls = 0
        server = CommandServer(0, {
            calls++
            if (calls == 1) error("boom") else HttpResponse(200, "{}")
        })
        server.start()
        assertTrue(exchange("GET /status HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500 "))
        assertTrue(exchange("GET /status HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200 "))
    }
}
