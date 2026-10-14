package `fun`.kirari.hanako.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 基于 JDK 标准库 `java.net.ServerSocket` 的轻量 Mock HTTP 服务器。
 *
 * 不引入任何新的测试依赖（Android 单元测试的编译类路径不含 `com.sun.net.httpserver`），
 * 用于在 JVM 单元测试中真实走一遍 OkHttp + SSE 网络栈，
 * 以便校验最终请求 URL、请求方法、认证头、请求体以及流式响应解析。
 */
internal class RecordedRequest(
    val method: String,
    val path: String,
    private val headerMap: Map<String, String>,
    val body: String
) {
    fun header(name: String): String? = headerMap[name.lowercase()]
}

/** 供 Mock 服务器向客户端写出响应。 */
internal class MockExchange(
    private val output: OutputStream,
    private val socket: Socket
) {
    private var sseStarted = false

    /** 写出 SSE 响应头并返回底层输出流，便于逐条推送事件。 */
    fun beginSse(): OutputStream {
        if (!sseStarted) {
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            output.flush()
            sseStarted = true
        }
        return output
    }

    /** 写出一个完整的 SSE 响应（Content-Type: text/event-stream），随后由连接关闭结束。 */
    fun respondSse(vararg chunks: String) {
        val out = beginSse()
        chunks.forEach { chunk ->
            out.write(chunk.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /** 写出一个 JSON 响应（可指定状态码）。 */
    fun respondJson(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8)
        )
        output.write(bytes)
        output.flush()
    }

    fun close() {
        runCatching { output.flush() }
        runCatching { socket.close() }
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Status"
    }
}

internal class MockHttpServer(
    private val handler: (RecordedRequest, MockExchange) -> Unit
) {
    private val serverSocket: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    fun start() {
        thread(isDaemon = true, name = "mock-http-accept") {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
                executor.execute { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val request = readRequest(input) ?: return
                requests += request
                val exchange = MockExchange(output, s)
                runCatching { handler(request, exchange) }
                runCatching { output.flush() }
            }
        } catch (_: Throwable) {
            // 客户端取消或连接中断属于预期场景，忽略。
        }
    }

    private fun readRequest(input: InputStream): RecordedRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }

        val headerMap = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headerMap[name] = headerMap[name]?.let { "$it, $value" } ?: value
        }

        val contentLength = headerMap["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val result = input.read(buffer, read, contentLength - read)
                if (result == -1) break
                read += result
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return RecordedRequest(method = method, path = path, headerMap = headerMap, body = body)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val builder = StringBuilder()
        var byte = input.read()
        if (byte == -1) return null
        while (byte != -1 && byte != '\n'.code) {
            if (byte != '\r'.code) builder.append(byte.toChar())
            byte = input.read()
        }
        return builder.toString()
    }

    fun stop() {
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }
}

/** 构造一条 Anthropic Messages 的 SSE 文本增量事件。 */
internal fun anthropicTextDelta(text: String, index: Int = 0): String =
    "event: content_block_delta\n" +
        "data: {\"type\":\"content_block_delta\",\"index\":$index,\"delta\":{\"type\":\"text_delta\",\"text\":\"$text\"}}\n\n"

/** 构造一条 OpenAI 兼容的 SSE 文本增量事件。 */
internal fun openAiTextDelta(text: String): String =
    "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"

/** 构造一条 Anthropic Messages 的 message_stop 正常结束事件。 */
internal fun anthropicMessageStop(): String =
    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

/** 构造一条 Anthropic Messages 的 content_block_start(tool_use) 事件。 */
internal fun anthropicToolUseStart(
    id: String,
    name: String,
    index: Int = 0,
    inputJson: String = "{}"
): String =
    "event: content_block_start\n" +
        "data: {\"type\":\"content_block_start\",\"index\":$index," +
        "\"content_block\":{\"type\":\"tool_use\",\"id\":\"$id\",\"name\":\"$name\",\"input\":$inputJson}}\n\n"

/** 构造一条 Anthropic Messages 的 input_json_delta 事件（自动转义 partial_json）。 */
internal fun anthropicInputJsonDelta(partialJson: String, index: Int = 0): String {
    val escaped = partialJson.replace("\\", "\\\\").replace("\"", "\\\"")
    return "event: content_block_delta\n" +
        "data: {\"type\":\"content_block_delta\",\"index\":$index," +
        "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"$escaped\"}}\n\n"
}

/**
 * 构造一条 Anthropic 错误事件。
 * [withEventField] 为 true 时附带 SSE `event: error` 行；为 false 时仅通过 JSON `type: "error"` 表示错误。
 */
internal fun anthropicErrorEvent(errorJson: String, withEventField: Boolean = true): String =
    (if (withEventField) "event: error\n" else "") +
        "data: {\"type\":\"error\",\"error\":$errorJson}\n\n"