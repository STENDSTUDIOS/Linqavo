package io.relay.app.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import io.relay.app.core.BoundedJson
import io.relay.app.net.Beacon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import android.os.SystemClock

internal data class WifiPath(val network: Network, val address: InetAddress)

internal class ClientNetwork(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    // A local hotspot can lack Internet validation and can coexist with
    // cellular. Look for an on-link Wi-Fi route, including non-default Wi-Fi.
    @Suppress("DEPRECATION")
    fun wifi(host: String): WifiPath? {
        val peer = InetAddress.getByName(host)
        if (peer !is Inet4Address || peer.isAnyLocalAddress || peer.isLoopbackAddress ||
            peer.isMulticastAddress || host == "255.255.255.255"
        ) return null
        val preferred = manager.activeNetwork
        return manager.allNetworks.sortedByDescending { it == preferred }.firstNotNullOfOrNull { network ->
            if (manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } != true
            ) return@firstNotNullOfOrNull null
            manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && it.prefixLength > 0 && it.address != peer &&
                    sameIPv4Subnet(it.address, peer, it.prefixLength)
            }?.let { WifiPath(network, it.address) }
        }
    }

    fun vpn(address: String): Network? = manager.activeNetwork?.takeIf { network ->
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            manager.getLinkProperties(network)?.linkAddresses?.any { it.address.hostAddress == address } == true
    }

    fun vpnAlreadyActive(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}

internal fun sameIPv4Subnet(local: InetAddress, peer: InetAddress, prefix: Int): Boolean {
    if (local !is Inet4Address || peer !is Inet4Address || prefix !in 1..32) return false
    val difference = ByteBuffer.wrap(local.address).int xor ByteBuffer.wrap(peer.address).int
    return difference ushr (32 - prefix) == 0
}

internal data class ClientOffer(val host: String, val port: Int, val name: String, val code: String)

internal fun discoverServers(): List<ClientOffer> = DatagramSocket(null).use { socket ->
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(Beacon.PORT))
    socket.soTimeout = 300
    val servers = linkedMapOf<Pair<String, Int>, ClientOffer>()
    val until = SystemClock.elapsedRealtime() + 3_000
    while (SystemClock.elapsedRealtime() < until && servers.size < 16) {
        val buffer = ByteArray(1025)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            continue
        }
        if (packet.length > 1024) continue
        parseOffer(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress.orEmpty())
            ?.let { servers[it.host to it.port] = it }
    }
    servers.values.toList()
}

internal fun parseOffer(text: String, source: String): ClientOffer? = runCatching {
    if (!BoundedJson.shallow(text)) return null
    val obj = Json.parseToJsonElement(text).jsonObject
    fun value(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
    val port = obj["pairingPort"]?.jsonPrimitive?.intOrNull ?: return null
    val code = value("code")
    if (obj["v"]?.jsonPrimitive?.intOrNull !in Beacon.KNOWN_VERSIONS ||
        value("state") != Beacon.STATE_SHARING || value("mode") != "wireguard" ||
        !numericIPv4(source) || value("host") != source || port !in 1..65535 ||
        !code.matches(Regex("[0-9]{2}"))
    ) return null
    ClientOffer(source, port, displayName(value("name")), code)
}.getOrNull()

internal fun bindTransport(network: Network, fd: Int) {
    ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
}
