package de.sebi.dmxc3remote.net

/** Werte aus LumosProtobuf/NetConstants.cs (DMXControl3-Network-Interface v3.3.x). */
object UmbraConstants {
    const val MULTICAST_ADDRESS = "225.68.67.3"
    const val DISCOVERY_PORT = 17474
    const val UMBRA_PORT = 17475
    const val MAX_MESSAGE_SIZE = 50 * 1024 * 1024
}

data class UmbraTarget(
    val host: String,
    val port: Int,
    val netId: String,
    val label: String = "$host:$port"
)
