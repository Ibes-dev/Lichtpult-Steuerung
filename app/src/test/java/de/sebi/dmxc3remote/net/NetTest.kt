package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.CommonTypes.TranslateableData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTest {
    /** Referenzwert aus hashPasswordDMXC() (Companion-Modul), mit Node.js und Python gegengerechnet. */
    @Test
    fun passwordHashMatchesReference() {
        assertEquals("L0snur0rqY2QShNrt7etxk6Rt6Ptp+1dgSUU02IuXYk=", PasswordHash.dmxc("DMXC3"))
    }

    @Test
    fun translateableDataFormatsParameters() {
        val t = TranslateableData.newBuilder()
            .setFormatString("Cue {0} von {1}")
            .addParameters(
                TranslateableData.TranslateableObject.newBuilder()
                    .setObjectData(ObjectData.newBuilder().setIntValue(3))
            )
            .addParameters(
                TranslateableData.TranslateableObject.newBuilder()
                    .setObjectData(ObjectData.newBuilder().setStringValue("Main"))
            )
            .build()
        assertEquals("Cue 3 von Main", t.text())
    }

    // ---------------------------------------------------------------- Eingangszuweisung
    private fun pid(node: String, port: String) = LumosProtobuf.Input.InputServiceTypes.GraphNodePortIDData.newBuilder()
        .setNodeId(LumosProtobuf.Input.InputServiceTypes.GraphNodeIDData.newBuilder().setGraphId("g").setNodeId(node)).setPortId(port).build()

    private fun port(node: String, port: String, input: Boolean, vararg to: LumosProtobuf.Input.InputServiceTypes.GraphNodePortIDData) =
        LumosProtobuf.Input.InputServiceTypes.PortMetadataData.newBuilder().setId(pid(node, port)).setIsInput(input).addAllConnectedPorts(to.toList()).build()

    @Test
    fun graphEdgesDedupedAndDirected() {
        // Eingang A.out -> Switcher S.in, S.out -> Cuelist C.go und C.stop; jede Verbindung steht bei beiden Enden
        val set = LumosProtobuf.Input.InputServiceTypes.ConnectionSetDescriptor.newBuilder()
            .addNodes(LumosProtobuf.Input.InputServiceTypes.NodeMetadataData.newBuilder()
                .addPorts(port("A", "out", false, pid("S", "in"))))
            .addNodes(LumosProtobuf.Input.InputServiceTypes.NodeMetadataData.newBuilder()
                .addPorts(port("S", "in", true, pid("A", "out")))
                .addPorts(port("S", "out", false, pid("C", "go"), pid("C", "stop"))))
            .addNodes(LumosProtobuf.Input.InputServiceTypes.NodeMetadataData.newBuilder()
                .addPorts(port("C", "go", true, pid("S", "out")))
                .addPorts(port("C", "stop", true, pid("S", "out"))))
            .build()
        val e = graphEdges(set)
        assertEquals(3, e.size)
        assertEquals(setOf("A/out>S/in", "S/out>C/go", "S/out>C/stop"), e.map { it.key }.toSet())
        // Richtung immer Ausgang -> Eingang
        e.forEach { assertTrue(it.to.portId in setOf("in", "go", "stop")) }
    }

    @Test
    fun portKeyFormat() {
        assertEquals("N1/p2", portKey(pid("N1", "p2")))
    }
}
