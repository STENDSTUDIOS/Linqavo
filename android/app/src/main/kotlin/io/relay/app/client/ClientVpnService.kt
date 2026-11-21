package io.relay.app.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.core.WgConfig
import io.relay.app.service.LocalNetworkAccess
import io.relay.app.service.LifecycleTrace
import io.relay.app.service.RelayRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

internal enum class ClientStage(val label: Int) {
    Idle(R.string.client_disconnected), Connecting(R.string.client_connecting),
    Approval(R.string.client_waiting_approval), Connected(R.string.client_connected),
    Reconnecting(R.string.status_reconnecting), Offline(R.string.client_mode_offline),
    Disabled(R.string.client_access_disabled), Revoked(R.string.client_access_revoked),
}

internal data class ClientStatus(
    val stage: ClientStage = ClientStage.Idle,
    val target: ClientTarget? = null,
    val address: String? = null,
    val keyFingerprint: String? = null,
    val received: Long = 0,
    val sent: Long = 0,
    val download: Long = 0,
    val upload: Long = 0,
    val seconds: Long = 0,
    val controlAvailable: Boolean = false,
    val detail: Int? = null,
)

class ClientVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pairingSocket = AtomicReference<Socket?>()
    private var session: Job? = null
    private var notificationStage: ClientStage? = null
    @Volatile private var permissionRevoked = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            end()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        val target = runCatching {
            Json.decodeFromString<ClientTarget>(intent?.getStringExtra(TARGET).orEmpty())
        }.getOrNull()
        if (target == null || !target.valid() || !RelayRole.acquire(RelayRole.Role.Client)) {
            // An explicit foreground start still owes Android a notification.
            if (intent?.action == START) foreground(ClientStage.Offline)
            stopSelf()
            return START_NOT_STICKY
        }
        session = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                foreground(ClientStage.Connecting)
                withContext(Dispatchers.IO) { connect(target) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.value = ClientStatus(ClientStage.Offline, target, detail = R.string.client_tunnel_error)
            } catch (_: LinkageError) {
                status.value = ClientStatus(ClientStage.Offline, target, detail = R.string.client_tunnel_error)
            } finally {
                if (permissionRevoked) {
                    status.value = ClientStatus(ClientStage.Offline, target, detail = R.string.client_vpn_revoked)
                } else if (!isActive) {
                    status.value = ClientStatus(target = target)
                }
                RelayRole.release(RelayRole.Role.Client)
                ServiceCompat.stopForeground(this@ClientVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun connect(target: ClientTarget) {
        status.value = ClientStatus(ClientStage.Connecting, target)
        val consentMissing = prepare(this) != null
        if (consentMissing || !LocalNetworkAccess.granted(this)) {
            status.value = status.value.copy(stage = ClientStage.Offline, detail =
                if (consentMissing) R.string.client_vpn_denied else R.string.client_local_network)
            return
        }
        val network = ClientNetwork(this)
        val wifi = network.wifi(target.host)
        if (wifi == null) {
            status.value = status.value.copy(stage = ClientStage.Offline, detail = R.string.client_wifi_required)
            return
        }
        val bridge = ClientBridge()
        val identities = ClientIdentity(this)
        val identity = identities.keyPair(target)
        val result = coroutineScope {
            wifi.network.socketFactory.createSocket().use { socket ->
                pairingSocket.set(socket)
                val watchdog = launch { delay(75_000); runCatching { socket.close() } }
                try {
                    currentCoroutineContext().ensureActive()
                    ClientEnrollment.fetch(target, socket, identity.publicKey.toBase64(), Build.MODEL) {
                        status.value = status.value.copy(
                            stage = ClientStage.Approval, keyFingerprint = sha256(identity.publicKey.bytes),
                        )
                        updateNotice(ClientStage.Approval)
                    }
                } finally {
                    watchdog.cancel()
                    pairingSocket.compareAndSet(socket, null)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (result is EnrollmentResult.Rejected) {
            val (stage, detail) = when (result.error) {
                EnrollmentError.IdentityChanged -> ClientStage.Offline to R.string.client_identity_changed
                EnrollmentError.Denied -> ClientStage.Offline to R.string.client_pairing_denied
                EnrollmentError.Capacity -> ClientStage.Offline to R.string.client_capacity
                EnrollmentError.Disabled -> ClientStage.Disabled to R.string.client_disabled_help
                EnrollmentError.Revoked -> {
                    identities.revoke(target)
                    ClientStage.Revoked to R.string.client_revoked_help
                }
                EnrollmentError.Invalid -> ClientStage.Offline to R.string.client_invalid
                EnrollmentError.Unavailable -> ClientStage.Offline to R.string.client_unavailable
            }
            status.value = ClientStatus(stage, target, detail = detail)
            return
        }
        val lease = (result as EnrollmentResult.Granted).lease
        runCatching { ClientIdentity.remember(this, target.copy(serverKey = null)) }
        status.value = ClientStatus(ClientStage.Connecting, target, address = lease.address)
        val tun = Builder()
            .setSession(getString(R.string.client_mode_title))
            .setMtu(1420)
            .addAddress(lease.address, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(WgConfig.DEFAULT_DNS)
            .setUnderlyingNetworks(arrayOf(wifi.network))
            .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(true) }
            .establish() ?: error("VPN unavailable")
        // The builder intentionally leaves IPv6 unconfigured. Android blocks
        // that family when no address, route, DNS server or allowFamily is set.
        tun.use {
            supervise(bridge, network, target, lease, tun, lease.ipc(identity.privateKey.toBase64(), target.host),
                identities, LifecycleTrace.id(identity.publicKey.toBase64()))
        }
    }

    private suspend fun supervise(
        bridge: ClientBridge,
        network: ClientNetwork,
        target: ClientTarget,
        lease: ClientLease,
        tun: ParcelFileDescriptor,
        ipc: String,
        identities: ClientIdentity,
        peerId: String,
    ) = coroutineScope {
        var engine: ClientEngine? = null
        var path: WifiPath? = null
        var event = PresenceSignal()
        var baseRx = 0L
        var baseTx = 0L
        var previousRx = 0L
        var previousTx = 0L
        var previousReplies = 0L
        val start = SystemClock.elapsedRealtime()
        var previousSample = start
        var lastData = 0L
        var retryAt = start
        var noPathSince = start
        var lastProbe = 0L
        var disabled = false
        var probe: Job? = null
        var hadForwarding = false
        var lastTrace = 0L
        val sessionId = LifecycleTrace.id(lease.serverKey)

        fun stopEngine() {
            val worker = engine ?: return
            engine = null
            probe?.cancel()
            worker.close()
            baseRx += worker.received()
            baseTx += worker.sent()
            previousReplies = 0
        }
        try {
            while (true) {
                ensureActive()
                val now = SystemClock.elapsedRealtime()
                val signal = event.get()
                when (signal) {
                    "server-stopped", "enrollment-revoked" -> {
                        stopEngine()
                        val revoked = signal == "enrollment-revoked"
                        val detail = if (revoked) {
                            if (runCatching { identities.revoke(target) }.isSuccess) R.string.client_revoked_help
                            else R.string.client_tunnel_error
                        } else R.string.client_server_stopped
                        val stage = if (revoked) ClientStage.Revoked else ClientStage.Offline
                        status.value = status.value.copy(stage = stage, controlAvailable = false, detail = detail,
                            received = baseRx, sent = baseTx, download = 0, upload = 0)
                        LifecycleTrace.event("client", sessionId, peerId, "event=ui state=$stage reason=$signal")
                        updateNotice(stage)
                        awaitCancellation()
                    }
                    "client-disabled" -> if (!disabled) {
                        disabled = true
                        stopEngine()
                        lastData = 0
                        retryAt = now + 5_000
                    }
                }
                val permitted = LocalNetworkAccess.granted(this@ClientVpnService)
                val current = if (permitted) runCatching { network.wifi(target.host) }.getOrNull() else null
                if (current != path) {
                    stopEngine()
                    path = current
                    setUnderlyingNetworks(current?.let { arrayOf(it.network) } ?: emptyArray())
                    retryAt = now
                    lastData = 0
                    noPathSince = now
                }
                // If both forwarding and control are absent, discard obsolete
                // WG receiver state. Keep the system TUN installed meanwhile.
                if (engine != null && (engine?.running() == false ||
                        (now >= retryAt && !fresh(now, lastData) && signal != "back"))) {
                    stopEngine()
                    lastData = 0
                    retryAt = now + 2_000
                }
                if (engine == null && current != null && now >= retryAt) {
                    event = PresenceSignal()
                    val listener = event
                    engine = runCatching {
                        bridge.start(tun, ipc, lease.address,
                            protect = { fd ->
                                LocalNetworkAccess.granted(this@ClientVpnService) && protect(fd) &&
                                    runCatching { bindTransport(current.network, fd) }.isSuccess
                            },
                            bindPresence = { fd ->
                                network.vpn(lease.address)?.let { vpn ->
                                    runCatching { bindTransport(vpn, fd) }.isSuccess
                                } == true
                            },
                            event = {
                                listener.accept(it)
                                LifecycleTrace.event("client", sessionId, peerId, "event=control state=$it")
                            },
                        )
                    }.getOrNull()
                    retryAt = now + if (engine == null) 10_000 else 35_000
                    lastProbe = 0
                }
                val workerRx = engine?.received() ?: 0
                val rx = baseRx + workerRx
                val tx = baseTx + (engine?.sent() ?: 0)
                val replies = engine?.replies() ?: 0
                if (replies > previousReplies) lastData = now
                previousReplies = replies
                val forwarding = fresh(now, lastData)
                val control = engine != null && event.get() == "back"
                if (forwarding || control) disabled = false
                if (engine != null && probe?.isActive != true &&
                    (!forwarding && !fresh(now, lastProbe, 8_000) ||
                        !fresh(now, lastData, 20_000) && !fresh(now, lastProbe, 20_000))
                ) {
                    lastProbe = now
                    probe = launch(Dispatchers.IO) {
                        val answered = probeDns(network, lease.address)
                        LifecycleTrace.event("client", sessionId, peerId, "event=dns result=$answered")
                    }
                }
                if (forwarding) hadForwarding = true
                val stage = when {
                    disabled -> ClientStage.Disabled
                    forwarding -> ClientStage.Connected
                    !permitted || now - maxOf(noPathSince, lastData) >= 35_000 -> ClientStage.Offline
                    current == null || hadForwarding -> ClientStage.Reconnecting
                    else -> ClientStage.Connecting
                }
                val detail = when {
                    !permitted -> R.string.client_local_network
                    disabled -> R.string.client_disabled_help
                    current == null -> R.string.client_wifi_required
                    !forwarding && control -> R.string.client_no_forwarding
                    stage == ClientStage.Offline -> R.string.client_unavailable
                    else -> null
                }
                val elapsed = maxOf(1, now - previousSample)
                if (LifecycleTrace.enabled && (now - lastTrace >= 5_000 || status.value.stage != stage ||
                        status.value.controlAvailable != control)) {
                    LifecycleTrace.event("client", sessionId, peerId, "event=ui state=$stage control=$control " +
                        "forwarding=$forwarding hsAgeSec=${engine?.handshakeAge() ?: -1} " +
                        "rxDelta=${rx - previousRx} txDelta=${tx - previousTx}")
                    lastTrace = now
                }
                status.value = ClientStatus(stage, target, lease.address, received = rx, sent = tx,
                    download = (rx - previousRx) * 1000 / elapsed, upload = (tx - previousTx) * 1000 / elapsed,
                    seconds = (now - start) / 1000, controlAvailable = control, detail = detail)
                previousRx = rx
                previousTx = tx
                previousSample = now
                updateNotice(stage)
                delay(2_000)
            }
        } finally {
            stopEngine()
            LifecycleTrace.event("client", sessionId, peerId, "event=worker-stopped revoked=$permissionRevoked")
        }
    }

    private fun probeDns(network: ClientNetwork, address: String): Boolean =
        runCatching {
            val vpn = network.vpn(address) ?: return false
            DatagramSocket(null).use { socket ->
                vpn.bindSocket(socket)
                socket.bind(InetSocketAddress(address, 0))
                socket.connect(InetSocketAddress(WgConfig.DEFAULT_DNS, 53))
                socket.soTimeout = 3_000
                val query = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)
                val id = random.nextInt(65536)
                query[0] = (id ushr 8).toByte()
                query[1] = id.toByte()
                socket.send(DatagramPacket(query, query.size))
                val response = DatagramPacket(ByteArray(512), 512)
                socket.receive(response)
                response.length >= query.size && response.data[0] == query[0] && response.data[1] == query[1] &&
                    response.data[2].toInt() and 0x80 != 0 &&
                    response.data.copyOfRange(12, query.size).contentEquals(query.copyOfRange(12, query.size))
            }
        }.getOrDefault(false)

    private fun end() {
        runCatching { pairingSocket.getAndSet(null)?.close() }
        session?.cancel()
        if (session == null) stopSelf()
    }

    override fun onRevoke() {
        permissionRevoked = true
        scope.launch { end() }
    }

    override fun onDestroy() {
        end()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun foreground(stage: ClientStage) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.client_mode_title),
            NotificationManager.IMPORTANCE_LOW))
        notificationStage = stage
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notice(stage),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun updateNotice(stage: ClientStage) {
        scope.launch {
            if (notificationStage != stage) {
                notificationStage = stage
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notice(stage))
            }
        }
    }

    private fun notice(stage: ClientStage): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 81, Intent(this, MainActivity::class.java).setAction(OPEN), flags)
        val stop = PendingIntent.getService(this, 82, Intent(this, ClientVpnService::class.java).setAction(STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.client_mode_title))
            .setContentText(getString(stage.label))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.client_mode_disconnect), stop)
            .build()
    }

    companion object {
        private const val START = "io.relay.fork.client.START"
        private const val STOP = "io.relay.fork.client.STOP"
        internal const val OPEN = "io.relay.fork.client.OPEN"
        private const val TARGET = "target"
        private const val CHANNEL = "relay-client-vpn"
        private const val NOTIFICATION_ID = 78
        private val random = SecureRandom()
        private val status = MutableStateFlow(ClientStatus())
        internal val state = status.asStateFlow()

        internal fun start(context: Context, target: ClientTarget) {
            context.startForegroundService(Intent(context, ClientVpnService::class.java).setAction(START)
                .putExtra(TARGET, Json.encodeToString(ClientTarget.serializer(), target)))
        }

        internal fun disconnect(context: Context) {
            context.startService(Intent(context, ClientVpnService::class.java).setAction(STOP))
        }
    }
}

internal fun fresh(now: Long, proof: Long, age: Long = 25_000): Boolean =
    proof > 0 && now - proof < age

internal class PresenceSignal {
    private val value = AtomicReference("")
    fun get(): String = value.get()
    fun accept(next: String) {
        value.updateAndGet { before ->
            if (before == "server-stopped" || before == "enrollment-revoked" || before == "client-disabled") before
            else next
        }
    }
}
