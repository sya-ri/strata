package dev.s7a.strata.integration.docs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serves one generated demo directory under an explicit Pages prefix on an ephemeral loopback port.
 * The owner must close the server; requests cannot escape the directory or follow symbolic links.
 */
internal class WebDemoServer(
    private val root: Path,
    private val prefix: String,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /**
     * Absolute simulated Pages base URL, including its trailing slash.
     */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}$prefix"

    init {
        server.createContext("${prefix}demos/", ::respond)
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange) {
        exchange.use {
            val relative = exchange.requestURI.path.removePrefix("${prefix}demos/")
            val file = root.resolve(if (relative.endsWith('/') || relative.isEmpty()) "${relative}index.html" else relative).normalize()
            val valid = file.startsWith(root) && runCatching { ShowcasePaths.requireRegularFile(file, "served demo file") }.isSuccess
            if (valid.not()) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val type = contentTypes[file.fileName.toString().substringAfterLast('.')] ?: "application/octet-stream"
            exchange.responseHeaders.set("Content-Type", type)
            exchange.responseHeaders.set("Cache-Control", "no-store")
            val bytes = Files.readAllBytes(file)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private val contentTypes =
        mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "txt" to "text/plain; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "ttf" to "font/ttf",
        )
}
