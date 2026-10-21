package com.hybridengine.terminal.api

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONObject

/**
 * VoidApiServer: In-process Embedded OS API Gateway.
 * Listens on a Unix Domain Socket at $PREFIX/var/run/void_api.sock and
 * localhost HTTP fallback (127.0.0.1:45671) for JSON-RPC requests.
 */
class VoidApiServer(private val context: Context) {

    private var isRunning = false
    private var tcpServer: ServerSocket? = null
    private var localServer: LocalServerSocket? = null
    private val httpFallbackPort = 45671

    private val runDir: File
        get() = File(context.filesDir, "usr/var/run")

    private val socketFile: File
        get() = File(runDir, "void_api.sock")

    fun start() {
        if (isRunning) return
        isRunning = true
        runDir.mkdirs()

        // 1. Start LocalSocket (Unix Domain Socket) listener
        Thread({
            runLocalSocketServer()
        }, "VoidApi-UnixSocket").apply { isDaemon = true; start() }

        // 2. Start TCP HTTP fallback listener
        Thread({
            runTcpFallbackServer()
        }, "VoidApi-TcpFallback").apply { isDaemon = true; start() }

        Log.i(TAG, "VoidApiServer started on ${socketFile.absolutePath} and 127.0.0.1:$httpFallbackPort")
    }

    fun stop() {
        isRunning = false
        try {
            localServer?.close()
        } catch (_: Exception) {}
        localServer = null

        try {
            tcpServer?.close()
        } catch (_: Exception) {}
        tcpServer = null

        if (socketFile.exists()) {
            socketFile.delete()
        }
        VoidApiEndpoints.releaseAllWakeLocks()
        Log.i(TAG, "VoidApiServer stopped")
    }

    private fun runLocalSocketServer() {
        try {
            // Remove existing socket file if present
            if (socketFile.exists()) socketFile.delete()

            // Note: Android LocalServerSocket uses abstract namespace if no prefix or filesystem socket
            val server = LocalServerSocket("com.hybridengine.terminal.void_api")
            localServer = server

            while (isRunning) {
                try {
                    val client = server.accept()
                    Thread({
                        handleLocalClient(client)
                    }, "VoidApi-Client").apply { isDaemon = true; start() }
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "LocalSocket accept exception: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LocalServerSocket initialization failed: ${e.message}")
        }
    }

    private fun handleLocalClient(client: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
            val writer = PrintWriter(client.outputStream, true)

            var line: String?
            while (isRunning && reader.readLine().also { line = it } != null) {
                val reqStr = line?.trim() ?: continue
                if (reqStr.isEmpty()) continue

                val response = dispatchRequest(reqStr)
                writer.println(response.toString())
            }
        } catch (e: Exception) {
            if (isRunning) Log.w(TAG, "Client socket handler error: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun runTcpFallbackServer() {
        try {
            val server = ServerSocket(httpFallbackPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
            tcpServer = server

            while (isRunning) {
                try {
                    val socket = server.accept()
                    Thread({
                        handleTcpClient(socket)
                    }, "VoidApi-TcpClient").apply { isDaemon = true; start() }
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "TCP accept exception: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP ServerSocket initialization failed on port $httpFallbackPort: ${e.message}")
        }
    }

    private fun handleTcpClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = PrintWriter(socket.outputStream, true)

            val firstLine = reader.readLine() ?: return
            var payload = firstLine

            // If HTTP POST, read headers and extract body
            if (firstLine.startsWith("POST") || firstLine.startsWith("GET")) {
                var contentLength = 0
                var headerLine: String?
                while (reader.readLine().also { headerLine = it } != null && headerLine!!.isNotEmpty()) {
                    if (headerLine!!.lowercase().startsWith("content-length:")) {
                        contentLength = headerLine!!.substring(15).trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    reader.read(bodyChars, 0, contentLength)
                    payload = String(bodyChars)
                }
            }

            val response = dispatchRequest(payload)
            writer.println("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + response.toString())
        } catch (e: Exception) {
            if (isRunning) Log.w(TAG, "TCP client error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun dispatchRequest(rawJson: String): JSONObject {
        return try {
            val req = JSONObject(rawJson)
            val method = req.optString("method", "")
            val id = req.opt("id")
            val params = req.optJSONObject("params") ?: JSONObject()

            val result = VoidApiEndpoints.handleMethod(method, params, context)
            JSONObject().apply {
                if (id != null) put("id", id)
                put("result", result)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", e.message ?: "Invalid JSON-RPC format")
            }
        }
    }

    companion object {
        private const val TAG = "VoidApiServer"

        @Volatile
        private var instance: VoidApiServer? = null

        fun getInstance(context: Context): VoidApiServer {
            return instance ?: synchronized(this) {
                instance ?: VoidApiServer(context.applicationContext).also { instance = it }
            }
        }

        fun start(context: Context) {
            getInstance(context).start()
        }

        fun stop() {
            instance?.stop()
            instance = null
        }
    }
}
