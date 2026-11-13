package com.siddhantdalal.bikelink

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpResponse(val status: Int, val body: String) {
    companion object {
        /** For errors raised by the server itself. Its messages are fixed strings, so nothing needs escaping. */
        fun error(status: Int, message: String) = HttpResponse(status, """{"ok":false,"message":"$message"}""")
    }
}

/**
 * A deliberately small HTTP/1.1 server for commands from the iPhone's Shortcuts ("Get Contents of URL").
 *
 * One phone sends a few tiny requests per ride, so connections are handled one at a time and request sizes are
 * capped. That avoids a web-server library, and a misbehaving client can't use up memory or threads.
 *
 * The constructor binds the port, so a port that's already in use fails right away.
 */
class CommandServer(
    port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    private val onError: (Throwable) -> Unit = {},
) {
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    /** The port actually bound. Useful when constructed with port 0, as the tests do. */
    val localPort: Int get() = socket.localPort

    fun start() {
        thread(name = "bike-link-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (!socket.isClosed) onError(e) // a closed socket means stop() was called: the normal way out
                    break
                }
                client.use(::serve)
            }
        }
    }

    fun stop() = socket.close()

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val response = try {
            handler(readRequest(BufferedInputStream(client.getInputStream())))
        } catch (e: MalformedRequestException) {
            HttpResponse.error(400, e.message.orEmpty())
        } catch (e: IOException) {
            return // the client was too slow or hung up, so there's no one to answer
        } catch (e: RuntimeException) {
            onError(e)
            HttpResponse.error(500, "Something went wrong on the bike phone.")
        }
        try {
            writeResponse(client.getOutputStream(), response)
        } catch (e: IOException) {
            // The client hung up before reading the answer. Nothing else to do.
        }
    }

    private fun readRequest(input: InputStream): HttpRequest {
        val lines = readHead(input).split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw MalformedRequestException("Not an HTTP request.")
        }
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw MalformedRequestException("Malformed header.")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) {
            throw MalformedRequestException("Send the body with a Content-Length.")
        }
        val length = headers["content-length"]?.let {
            it.toIntOrNull() ?: throw MalformedRequestException("Bad Content-Length.")
        } ?: 0
        if (length !in 0..MAX_BODY_BYTES) throw MalformedRequestException("Request body is too large.")
        val body = ByteArray(length).also { input.readFully(it) }
        return HttpRequest(requestLine[0], requestLine[1].substringBefore('?'), headers, body.toString(Charsets.UTF_8))
    }

    /** Reads up to the blank line that ends the headers, and returns them without that final CRLF CRLF. */
    private fun readHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            if (head.length >= MAX_HEAD_BYTES) throw MalformedRequestException("Request headers are too large.")
            val byte = input.read()
            if (byte == -1) throw EOFException()
            head.append(byte.toChar()) // HTTP headers are ISO-8859-1: one byte is one char
        }
        return head.removeSuffix("\r\n\r\n").toString()
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException()
            offset += read
        }
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${response.status} ${reasonPhrase(response.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun reasonPhrase(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        422 -> "Unprocessable Content"
        500 -> "Internal Server Error"
        else -> ""
    }

    private class MalformedRequestException(message: String) : IOException(message)

    private companion object {
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_HEAD_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 16 * 1024
    }
}
