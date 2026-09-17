package se.whitchurch.nordict.debug

import android.app.Application
import android.util.Log
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentProtocol
import se.whitchurch.nordict.AgentResult
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A loopback agent server for the debug build: one line-delimited JSON
 * [AgentCommand] per line on a socket connection, exactly one [AgentResult]
 * per command back, in order (the same wire framing as the headless `:cli
 * repl`). The host reaches it through `adb reverse tcp:42837 tcp:42837`.
 *
 * Binding failure (another instance/tests already bound the port) is logged
 * and non-fatal, so normal app startup is never affected.
 */
class AgentServer(private val app: Application) {

    private val driver by lazy { AppDriver(app) }
    private var server: ServerSocket? = null
    private var running = false

    fun start() {
        server = try {
            ServerSocket(AgentProtocol.PORT, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            Log.w(TAG, "agent server: cannot bind ${AgentProtocol.PORT}: ${e.message}")
            return
        }
        running = true
        Thread({ acceptLoop() }, "agent-server").apply { isDaemon = true }.start()
        Log.i(TAG, "agent server listening on 127.0.0.1:${AgentProtocol.PORT}")
    }

    fun stop() {
        running = false
        server?.close()
        server = null
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = server!!.accept()
                Thread({ handle(socket) }, "agent-conn").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
            }
        }
    }

    // Gson can inject null for a missing `op` despite the non-null Kotlin type,
    // so the null check below is runtime-relevant.
    @Suppress("SENSELESS_COMPARISON")
    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                val result = try {
                    val command = AgentProtocol.gson.fromJson(line, AgentCommand::class.java)
                    if (command.op == AgentOps.QUIT) {
                        AgentResult(ok = true, op = AgentOps.QUIT, message = "bye")
                    } else if (command.op == null) {
                        AgentResult.error(null, "missing op")
                    } else {
                        driver.execute(command)
                    }
                } catch (e: Exception) {
                    AgentResult.error(null, "protocol error: ${e.message ?: e}")
                }

                writer.println(AgentProtocol.gson.toJson(result))
                writer.flush()

                if (result.op == AgentOps.QUIT) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection dropped: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: IOException) {
            }
        }
    }

    companion object {
        private const val TAG = "AgentServer"
    }
}