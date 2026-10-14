package de.sebi.dmxc3remote.net

import LumosProtobuf.UmbraServiceTypes.InformClientExistsRequest
import LumosProtobufCommon.DMXCNetServiceGrpcKt.DMXCNetServiceCoroutineStub
import io.grpc.okhttp.OkHttpChannelBuilder

data class AdmissionResult(val netId: String?, val clientName: String?, val umbraName: String?)

/**
 * Meldet den Client bei einem Umbra-Server an (DMXCNetService.InformClientExists), ohne einzuloggen.
 * Der Client erscheint dann in der Netzwerkverwaltung der DMXC3-GUI; wird er dort dem Netzwerk
 * zugewiesen, liefert ein erneuter Aufruf die Ziel-NetID (SetNetworkPropertyRequest.targetNetworkId).
 */
suspend fun informClientExists(host: String, port: Int, identity: ClientIdentity): AdmissionResult {
    val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
    try {
        val r = DMXCNetServiceCoroutineStub(channel).dl(3).informClientExists(
            InformClientExistsRequest.newBuilder()
                .setRequestId(System.currentTimeMillis().toString())
                .setInfo(UmbraSession.buildProgramInfo(identity, ""))
                .build()
        )
        var netId: String? = null
        var name: String? = null
        r.requestsList.filter { it.clientRuntimeId.isBlank() || it.clientRuntimeId == identity.runtimeId }.forEach { req ->
            if (req.targetNetworkId.isNotBlank()) netId = req.targetNetworkId
            if (req.targetClientName.isNotBlank()) name = req.targetClientName
        }
        val umbra = if (r.hasUmbra() && r.umbra.hasClientInfo()) r.umbra.clientInfo.clientname else null
        return AdmissionResult(netId, name, umbra)
    } finally {
        channel.shutdownNow()
    }
}
