package io.slychat.messenger.core.http

import io.slychat.messenger.core.crypto.tls.SSLConfigurator
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection


fun slurpInputStreamReader(reader: Reader, suggestedBufferSize: Int = 0): String {
    val bufferSize = if (suggestedBufferSize > 0) suggestedBufferSize else 1024

    val buffer = CharArray(bufferSize)
    val builder = StringBuilder()
    while (true) {
        val readChars = reader.read(buffer, 0, buffer.size)
        if (readChars <= 0)
            break
        builder.append(buffer, 0, readChars)
    }

    return builder.toString()
}

private fun lowercaseHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
    //headers actually have a null key containing the http response line
    headers.mapKeys { e ->
        @Suppress("UNNECESSARY_SAFE_CALL")
        e.key?.toLowerCase()
    }

private fun slurpStreamAndClose(inputStream: InputStream, suggestedBufferSize: Int): String =
    inputStream.use {
        val reader = BufferedReader(InputStreamReader(it, "utf-8"))
        slurpInputStreamReader(reader, suggestedBufferSize)
    }

private fun readStreamResponse(connection: HttpURLConnection, headers: Map<String, List<String>>): String {
    val contentLength = headers["content-length"]?.first()?.toInt() ?: 1024

    val data = try {
        slurpStreamAndClose(connection.inputStream, contentLength)
    }
    catch (e: java.io.IOException) {
        if (connection.errorStream != null)
            slurpStreamAndClose(connection.errorStream, contentLength)
        else
            ""
    }

    return data
}

//platform is Os.Type.displayName
data class ClientInfo(val slyVersion: String, val platform: String, val platformVersion: String)

class JavaHttpClient(
    private val config: HttpClientConfig = HttpClientConfig(3000, 3000),
    private val sslConfigurator: SSLConfigurator? = null,
    private val clientInfo: ClientInfo? = null
) : HttpClient {
    private fun getHttpConnection(url: String): HttpURLConnection = getHttpConnection(URL(url))

    private fun getHttpConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection)
            sslConfigurator?.configure(connection)

        addInfoHeaders(connection)

        return connection
    }

    private fun addInfoHeaders(connection: HttpURLConnection) {
        val info = clientInfo ?: return

        connection.apply {
            setRequestProperty("X-Sly-Version", info.slyVersion)
            setRequestProperty("X-Sly-Platform", "${info.platform}/${info.platformVersion}")
        }
    }

    override fun get(url: String, headers: List<Pair<String, String>>): HttpResponse {
        val connection = getHttpConnection(url)
        connection.doInput = true
        connection.requestMethod = "GET"
        connection.useCaches = false

        connection.connectTimeout = config.connectTimeoutMs
        connection.readTimeout = config.readTimeoutMs

        for (header in headers)
            connection.setRequestProperty(header.first, header.second)

        connection.connect()

        val headers = lowercaseHeaders(connection.headerFields ?: mapOf())
        val data = readStreamResponse(connection, headers)

        return HttpResponse(connection.responseCode, headers, data)
    }

    override fun postJSON(url: String, body: ByteArray, headers: List<Pair<String, String>>): HttpResponse {
        val newHeaders = headers.toMutableList()
        newHeaders.add("Content-Type" to "application/json")

        return post(url, body, newHeaders)
    }

    override fun post(url: String, body: ByteArray, headers: List<Pair<String, String>>): HttpResponse {
        val connection = getHttpConnection(url)
        connection.requestMethod = "POST"
        connection.doInput = true
        connection.doOutput = true

        for (header in headers)
            connection.setRequestProperty(header.first, header.second)

        connection.setFixedLengthStreamingMode(body.size)

        connection.connect()

        connection.outputStream.use { it.write(body) }

        val headers = lowercaseHeaders(connection.headerFields ?: mapOf())
        val data = readStreamResponse(connection, headers)

        return HttpResponse(connection.responseCode, headers, data)
    }
}
