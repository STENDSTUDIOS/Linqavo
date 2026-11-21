package io.relay.app.net

import io.relay.app.core.WgParams
import io.relay.app.service.LocalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** Pinned platform-TLS enrollment with client-owned public keys; shared/enrollment-v2.md. */
class PairingServer(
    private val preferredPort: Int,
    private val gate: ClientGate,
    /**
     * The configuration to hand out, read at approval time rather than captured
     * at construction: sharing can rebind onto a new address mid-session, and a
     * captured host would send the laptop to where the phone used to be.
     *
     * Built for the client that asked, so the caller can also judge whether this
     * particular PC can be reached back at all.
     */
    private val configuration: (client: String) -> Configuration?,
    /** Overridable so a test can prove the idle close without waiting it out. */
    private val idleTimeoutMs: Int = IDLE_TIMEOUT_MS,
    private val enroll: (String, String) -> String? = { _, _ -> null },
    private val enrollmentError: (String) -> String = { "" },
    private val alreadyInstalled: (String) -> Boolean = { false },
) {
    /** Everything a client needs, matching the `wg` block in qr-payload.schema.json. */
    data class Configuration(val host: String, val port: Int, val wg: WgParams)

    val sessionId = java.util.UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val slots = java.util.concurrent.Semaphore(8)
    var fingerprint: String = ""
        private set

    /** The port actually bound, or -1 before [start] succeeds. */
    var boundPort: Int = -1
        private set

    /**
     * Binds and begins serving.
     *
     * @throws IOException if the port cannot be bound. The caller turns that
     * into a phone that is QR-only rather than a phone that cannot share: the
     * beacon then omits `pairingPort`, and the PC says to scan instead of
     * reporting a correct code as invalid.
     */
    @Throws(IOException::class)
    fun start() {
        if (socket != null) return
        val tls = EnrollmentTls()
        fingerprint = tls.fingerprint
        val server = (tls.context.serverSocketFactory.createServerSocket(preferredPort, 8) as javax.net.ssl.SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        }
        socket = server
        boundPort = server.localPort
        job = scope.launch {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@launch // closed, or the interface went away
                }
                // One connection per coroutine: approval blocks for up to a
                // minute, and a second laptop has to be able to queue behind it
                // rather than find a port that never answers.
                if (!slots.tryAcquire()) { client.close(); continue }
                clients.add(client)
                val handler = scope.launch { serve(client) }
                handler.invokeOnCompletion {
                    runCatching { client.close() }
                    clients.remove(client)
                    slots.release()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        boundPort = -1
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        scope.cancel()
    }

    private suspend fun serve(client: Socket) {
        val address = client.inetAddress?.hostAddress ?: return
        var deadline: Job? = null
        try {
            client.soTimeout = idleTimeoutMs
            (client as javax.net.ssl.SSLSocket).startHandshake()
            deadline = scope.launch { kotlinx.coroutines.delay(75_000); runCatching { client.close() } }
            val reader = client.getInputStream()
            val writer = client.getOutputStream().bufferedWriter()

            val line = try {
                readBoundedLine(reader, MAX_LINE)
            } catch (_: SocketTimeoutException) {
                // This port is reachable by anything on the network, so a socket
                // that opens and then says nothing is not held open for it.
                return
            } ?: return

            when (classify(line)) {
                Request.IGNORE -> return // not ours; say nothing at all

                // Answered rather than dropped: a newer PC has to learn it is
                // talking to an older phone, instead of waiting out a timeout and
                // reporting the phone as unreachable.
                Request.WRONG_VERSION -> {
                    writer.appendLine(error(ERR_VERSION)); writer.flush()
                    LocalLog.warn(
                        LocalLog.Area.PAIRING, "A PC asked in a newer pairing version",
                        "pc" to address,
                    )
                }

                Request.PAIR -> {
                    LocalLog.info(LocalLog.Area.PAIRING, "A PC asked to pair", "pc" to address)
                    // The PC's own name, so the prompt names a computer rather
                    // than only an address — which is what the contract has
                    // always said it shows, and what this phone used to drop.
                    val publicKey = requestKey(line) ?: return
                    val accessError = enrollmentError(publicKey)
                    if (accessError.isNotEmpty()) { writer.appendLine(error(accessError)); writer.flush(); return }
                    val identity = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(publicKey))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val allowed = alreadyInstalled(publicKey) || gate.authorize("$sessionId:$identity", requestName(line))
                    val lease = if (allowed) enroll(publicKey, requestName(line).orEmpty()) else null
                    val config = if (lease != null) configuration(address) else null
                    if (config == null || lease == null) {
                        val reason = if (!allowed) ERR_DENIED else enrollmentError(publicKey).ifEmpty {
                            if (lease == null) "ERR_PEER_BUSY" else "ERR_FORWARDING_UNAVAILABLE"
                        }
                        writer.appendLine(error(reason)); writer.flush()
                        // Warn, not error: the overwhelmingly common reason is
                        // that the person tapped Deny, which is the feature
                        // working. A reader still wants to find it quickly.
                        LocalLog.warn(
                            LocalLog.Area.PAIRING, "Refused a PC",
                            "pc" to address,
                            "reason" to if (!allowed) "not-allowed" else "no-configuration",
                        )
                        return
                    }
                    writer.appendLine(accepted(config.copy(wg = config.wg.copy(clientAddress = lease)))); writer.flush()
                    LocalLog.info(LocalLog.Area.PAIRING, "Sent the configuration", "pc" to address)
                }
            }
        } catch (_: IOException) {
            // A client that hangs up mid-exchange is ordinary, not an error
            // worth putting in front of someone.
        } finally {
            deadline?.cancel()
            runCatching { client.close() }
        }
    }

    internal enum class Request { PAIR, WRONG_VERSION, IGNORE }

    companion object {
        /** Where the phone offers configurations. Announced in the beacon. */
        const val DEFAULT_PORT = 47657

        /** How long a connection may stay silent before it is closed. */
        const val IDLE_TIMEOUT_MS = 10_000

        const val VERSION = 2
        const val MAX_LINE = 4096
        const val ERR_DENIED = "ERR_PAIRING_DENIED"
        const val ERR_VERSION = "ERR_PAIRING_VERSION"

        /**
         * What a line on this port means.
         *
         * Deliberately narrow in both directions, exactly as [Beacon.isProbe]
         * is. Serving anything that arrives hands key material to whatever
         * opened the socket; refusing too much leaves a laptop with no way in
         * while the phone plainly shows a code. The exact set either way is in
         * /shared/test-vectors.json → pairingExchange.
         *
         * Parsed with kotlinx.serialization rather than org.json so this is
         * reachable from a plain JVM test — org.json is an android.jar stub off
         * the device, and a rule this consequential being untestable until it
         * reaches hardware is how it goes wrong quietly.
         */
        /**
         * The name a pairing request carries, cleaned for display, or null.
         *
         * Attacker-controlled like every other name on this port: control and
         * bidi-override characters are removed so a name cannot reorder the
         * sentence it is shown in, and it is cut to 32 characters as the
         * contract allows. Blank means absent.
         */
        internal fun requestName(text: String): String? = try {
            val raw = Json.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive
                ?.takeIf { it.isString }?.content
            raw?.filter { ch ->
                !ch.isISOControl() && Character.getType(ch) != Character.FORMAT.toInt()
            }?.trim()?.take(MAX_NAME)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

        internal fun requestKey(text: String): String? = runCatching {
            if (text.length > MAX_LINE || !io.relay.app.core.BoundedJson.shallow(text)) return null
            val key = Json.parseToJsonElement(text).jsonObject["clientPublicKey"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
            val raw = java.util.Base64.getDecoder().decode(key)
            key.takeIf { raw.size == 32 && raw.any { it.toInt() != 0 } && java.util.Base64.getEncoder().encodeToString(raw) == key }
        }.getOrNull()

        internal fun readBoundedLine(reader: java.io.InputStream, limit: Int): String? {
            val bytes = ByteArray(limit)
            for (i in bytes.indices) {
                val ch = reader.read()
                if (ch == -1 || ch == 13 || ch == 0) return null
                if (ch == 10) return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, i)).toString()
                bytes[i] = ch.toByte()
            }
            return null
        }

        /** The longest name the contract lets a request carry. */
        const val MAX_NAME = 32

        internal fun classify(text: String): Request {
            if (text.length > MAX_LINE || !io.relay.app.core.BoundedJson.shallow(text)) return Request.IGNORE
            return try {
            val obj = Json.parseToJsonElement(text).jsonObject
            val version = obj["v"]?.jsonPrimitive?.intOrNull
            val pair = obj["pair"]?.jsonPrimitive
            val asks = pair != null &&
                (pair.booleanOrNull == true || (pair.intOrNull ?: 0) != 0)
            when {
                !asks || version == null -> Request.IGNORE
                version != VERSION -> Request.WRONG_VERSION
                requestKey(text) == null -> Request.IGNORE
                else -> Request.PAIR
            }
        } catch (_: Exception) {
            Request.IGNORE // not ours; something else found this port
        }
        }

        /**
         * The refusal, and the only thing a denied client ever learns.
         *
         * Built with kotlinx.serialization rather than org.json for the same
         * reason [classify] parses with it: org.json is an android.jar stub off
         * the device, and these two functions are exactly what
         * /shared/test-vectors.json pins. A response format that cannot be
         * asserted until it reaches hardware is one that drifts.
         */
        internal fun error(code: String): String = buildJsonObject {
            put("v", VERSION)
            put("error", code)
        }.toString()

        /**
         * The configuration, in the shape /shared/test-vectors.json pins. The
         * `wg` object is the one from qr-payload.schema.json, so both pairing
         * paths hand the client the same structure.
         */
        internal fun accepted(config: Configuration): String = buildJsonObject {
            put("v", VERSION)
            put("ok", 1)
            put("host", config.host)
            put("port", config.port)
            putJsonObject("wg") {
                put("serverPublicKey", config.wg.serverPublicKey)
                put("clientAddress", config.wg.clientAddress)
                put("allowedIps", config.wg.allowedIps)
                put("endpointPort", config.wg.endpointPort)
                put("dns", config.wg.dns)
            }
        }.toString()
    }
}
