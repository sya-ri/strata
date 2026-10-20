package dev.s7a.strata.gradle.release

import com.sun.net.httpserver.HttpExchange
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets

/**
 * Sends fixture response bytes and preserves the no-body semantics of successful empty writes.
 */
internal fun HttpExchange.respond(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(status, if (status == 204) -1L else bytes.size.toLong())
    if (status != 204) responseBody.write(bytes)
}

/**
 * Requires an API write to contain one JSON object without invoking the production request encoder.
 */
internal fun HttpExchange.requestObject(): Map<*, *> =
    JsonSlurper().parse(requestBody, StandardCharsets.UTF_8.name()) as? Map<*, *>
        ?: error("Bootstrap request must contain one JSON object.")
