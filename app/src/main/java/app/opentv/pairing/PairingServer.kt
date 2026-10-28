/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.content.Context
import android.util.Log
import app.opentv.R
import app.opentv.config.ConfigUpdater
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lets someone fill in their provider details from a phone or a computer instead of a TV remote.
 *
 * ## Why the TV is the server
 *
 * There is no cloud service behind this. The TV runs a small web server on the local network
 * for as long as the pairing screen is open. Details go browser → TV over the user's own wifi
 * and never leave the building. It works with the internet unplugged.
 *
 * ## Access
 *
 * The page is open to anyone on the local network, with no token and no confirmation code:
 * the address is simply `http://<tv-ip>:<port>/`. The remaining protections are:
 *
 * - The server only runs while the pairing screen is open.
 * - Requests from non-private addresses are refused outright.
 * - After [SESSION_TIMEOUT_MINUTES], or on success, the socket closes.
 *
 * ## Ports
 *
 * The first free port of [PORTS] is used: 9090, then 1234, 8080 and 8888.
 */
class PairingServer(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext: Context = context.applicationContext

    data class Session(
        /** What the QR code encodes, e.g. `http://192.168.0.14:9090/` */
        val url: String,
        val host: String,
        val port: Int,
    ) {
        /** The address to read out if the QR will not scan. */
        val shortUrl: String get() = "$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State

        /**
         * The browser submitted something. [draft] is a provider typed in the form (null when only
         * a conf.json address was given) and [confUrl] is the conf.json address, already saved.
         */
        data class Received(val draft: Source?, val confUrl: String?) : State

        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        val host = lanAddress()
        if (host == null) {
            _state.value = State.Failed(appContext.getString(R.string.pairing_err_no_network))
            return
        }

        val socket = openFirstFreePort()
        if (socket == null) {
            _state.value = State.Failed(appContext.getString(R.string.pairing_err_no_port))
            return
        }

        serverSocket = socket
        running = true

        val session = Session(
            url = "http://$host:${socket.localPort}/",
            host = host,
            port = socket.localPort,
        )
        _state.value = State.Listening(session)

        // A plain thread rather than a coroutine: ServerSocket.accept() blocks
        // uninterruptibly, and the only reliable way to stop it is to close the socket.
        thread(name = "opentv-pairing", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break // socket closed, which is how we stop
                }
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "Pairing request failed", it) }
                runCatching { client.close() }
            }
        }

        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES))
            if (running && _state.value is State.Listening) {
                stop()
                _state.value = State.Failed(appContext.getString(R.string.pairing_err_timeout))
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    private fun openFirstFreePort(): ServerSocket? {
        for (port in PORTS) {
            val candidate = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Port $port unavailable", e)
                null
            }
            if (candidate != null) return candidate
        }
        return null
    }

    // ---- Request handling ------------------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS

        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }

        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val (method, path, _) = PairingHttp.parseRequestLine(requestLine) ?: return

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (name, value) -> headers[name] = value }
        }

        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0)
            .coerceIn(0, MAX_BODY_BYTES)
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var total = 0
            while (total < contentLength) {
                val read = reader.read(buffer, total, contentLength - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, total)
        } else {
            ""
        }

        val french = PairingHttp.prefersFrench(headers["accept-language"])
        val output = client.getOutputStream()

        when {
            method == "GET" && (path == "/" || path.isEmpty()) -> output.write(
                PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(null, french)),
            )

            method == "POST" && path.startsWith("/submit") -> handleSubmit(output, body, french)

            method == "GET" -> output.write(
                PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."),
            )

            else -> output.write(
                PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."),
            )
        }
    }

    private fun handleSubmit(output: java.io.OutputStream, body: String, french: Boolean) {
        val form = PairingHttp.parseFormEncoded(body)

        fun showError(message: String) {
            output.write(
                PairingHttp.httpResponse(
                    "200 OK",
                    "text/html",
                    PairingPage.form(message, french),
                ),
            )
        }

        val rawConf = form["conf"].orEmpty().trim()
        val confUrl = PairingHttp.cleanHttpUrl(rawConf)
        if (rawConf.isNotEmpty() && confUrl == null) {
            showError(
                if (french) "L'adresse du fichier conf.json doit commencer par http:// ou https://."
                else "The conf.json address must start with http:// or https://.",
            )
            return
        }

        val kind = if (form["kind"] == "m3u") SourceKind.M3U else SourceKind.XTREAM
        val url = form["url"].orEmpty().trim()
        if (url.isEmpty() && confUrl == null) {
            showError(
                if (french) "Renseignez l'adresse du serveur ou l'URL d'un fichier conf.json."
                else "Please fill in the server address or a conf.json URL.",
            )
            return
        }

        val draft = if (url.isEmpty()) {
            null
        } else {
            Source(
                name = form["name"].orEmpty().trim().ifBlank {
                    if (kind == SourceKind.XTREAM) "My provider" else "My playlist"
                },
                kind = kind,
                url = url,
                username = form["username"]?.trim()?.takeIf { it.isNotEmpty() },
                password = form["password"]?.takeIf { it.isNotEmpty() },
                epgUrl = form["epg"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        // Saved here, before the UI takes over, so the address survives even if the app is
        // closed before the provider has finished loading.
        if (confUrl != null) ConfigUpdater.saveConfUrl(appContext, confUrl)

        output.write(
            PairingHttp.httpResponse(
                "200 OK",
                "text/html",
                PairingPage.message(
                    if (french) "Envoyé à votre TV" else "Sent to your TV",
                    if (french) "Vous pouvez poser votre téléphone — la suite se passe sur la télé."
                    else "You can put your phone down — the rest happens on the telly.",
                    french,
                ),
            ),
        )
        output.flush()

        _state.value = State.Received(draft, confUrl)
        // Nothing more to receive. Shut the door immediately rather than leaving a socket
        // open on the user's network for the rest of the timeout.
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---- Finding our own address -----------------------------------------------------------

    /**
     * The device's address on the LAN.
     *
     * Enumerating interfaces rather than asking ConnectivityManager, because TV boxes are
     * frequently on ethernet, sometimes on both, and the wifi-shaped API returns nothing
     * useful when the box is wired.
     */
    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(':') == true) continue // IPv4 only
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }.onFailure { Log.w(TAG, "Could not enumerate interfaces", it) }

        // Prefer a real private range over a 169.254 self-assigned address, which means
        // DHCP failed and nothing will be able to reach us anyway.
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    private companion object {
        const val TAG = "PairingServer"
        const val SESSION_TIMEOUT_MINUTES = 5L
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val MAX_BODY_BYTES = 16 * 1024

        /** Tried in this order; the first one that is free wins. */
        val PORTS = intArrayOf(9090, 1234, 8080, 8888)
    }
}
