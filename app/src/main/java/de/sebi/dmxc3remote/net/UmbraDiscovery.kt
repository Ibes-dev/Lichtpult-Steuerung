package de.sebi.dmxc3remote.net

import LumosProtobuf.UmbraServiceTypes.UmbraUdpBroadcast
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredUmbra(
    val host: String,
    val port: Int,
    val hostname: String,
    val clientName: String,
    val netId: String,
    val projectName: String,
    val seenAt: Long
) {
    val key get() = "$host:$port"
    fun toTarget() = UmbraTarget(host, port, netId, clientName.ifBlank { hostname.ifBlank { host } })
}

/**
 * Lauscht auf UmbraUdpBroadcast-Datagramme (Multicast 225.68.67.3:17474),
 * analog zu UmbraDiscoveryClient.cs. Ohne WifiManager.MulticastLock verwerfen
 * viele Android-Geräte Multicast-Pakete.
 */
fun umbraDiscovery(context: Context): Flow<DiscoveredUmbra> = flow {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    val lock = wifi?.createMulticastLock("dmxc3-discovery")?.apply {
        setReferenceCounted(false)
        acquire()
    }
    val socket = MulticastSocket(null).apply {
        reuseAddress = true
        soTimeout = 1000
        bind(InetSocketAddress(UmbraConstants.DISCOVERY_PORT))
    }
    try {
        val group = InetSocketAddress(InetAddress.getByName(UmbraConstants.MULTICAST_ADDRESS), 0)
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { nif ->
                nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                    nif.inetAddresses.toList().any { it is Inet4Address }
            }
            .forEach { nif -> runCatching { socket.joinGroup(group, nif) } }

        val buf = ByteArray(64 * 1024)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val bc = runCatching {
                UmbraUdpBroadcast.parseFrom(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
            }.getOrNull() ?: continue
            if (!bc.hasUmbraServer() || !bc.umbraServer.hasClientInfo()) continue
            val ci = bc.umbraServer.clientInfo
            val host = packet.address?.hostAddress ?: continue
            emit(
                DiscoveredUmbra(
                    host = host,
                    port = ci.umbraPort.takeIf { it > 0 } ?: UmbraConstants.UMBRA_PORT,
                    hostname = ci.hostname,
                    clientName = ci.clientname,
                    netId = ci.networkid,
                    projectName = ci.projectName,
                    seenAt = System.currentTimeMillis()
                )
            )
        }
    } finally {
        runCatching { socket.close() }
        runCatching { lock?.release() }
    }
}.flowOn(Dispatchers.IO)
