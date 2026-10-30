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

    // ---------------------------------------------------------------- .NET-Werte (aus dem Mitschnitt der Windows-Oberfläche)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Position (28, 227), wie sie die Windows-Oberfläche gesendet hat. */
    private val capturedPoint = "0001000000ffffffff01000000000000000c020000005153797374656d2e44726177696e672c2056657273696f6e3d342e302e302e302c2043756c747572653d6e65757472616c2c205075626c69634b6579546f6b656e3d6230336635663766313164353061336105010000001453797374656d2e44726177696e672e506f696e74020000000178017900000808020000001c000000e30000000b"

    /** "On Value" = true eines Binary Switchers, wie gesendet. */
    private val capturedMulti = "0001000000ffffffff01000000000000000c020000003f4c756d6f734c49422c2056657273696f6e3d332e332e332e302c2043756c747572653d6e65757472616c2c205075626c69634b6579546f6b656e3d6e756c6c05010000002d6f72672e646d78632e6c756d6f732e4b65726e656c2e496e7075742e76322e4d756c74695479706556616c756504000000294162737472616374426f78656456616c75652b3c56616c75653e6b5f5f4261636b696e674669656c642b4162737472616374426f78656456616c75652b70726f746f62756653657269616c697a65644f626a656374294162737472616374426f78656456616c75652b66616c6c6261636b50726f746f627566547970654151214162737472616374426f78656456616c75652b66616c6c6261636b5479706541510207010102020000000801010a0a0a0b"

    @Test
    fun pointBuildMatchesWindowsGui() {
        val built = DotNetValue.buildPoint(28, 227)
        assertTrue(built.fallbackNet.toByteArray().contentEquals(hex(capturedPoint)))
        assertEquals(28 to 227, DotNetValue.parsePoint(built))
        assertEquals(-500 to 77, DotNetValue.parsePoint(DotNetValue.buildPoint(-500, 77)))
    }

    @Test
    fun multiTypeValueParseAndBuild() {
        val tpl = ObjectData.newBuilder()
            .setFallbackTypeAQ("org.dmxc.lumos.Kernel.Input.v2.MultiTypeValue, LumosLIB, Version=3.3.3.0, Culture=neutral, PublicKeyToken=null")
            .setFallbackNet(com.google.protobuf.ByteString.copyFrom(hex(capturedMulti))).build()
        assertEquals(true, DotNetValue.parseMulti(tpl))
        // Neu bauen mit true muss exakt die gesendeten Bytes ergeben
        assertTrue(DotNetValue.buildMulti(tpl, true)!!.fallbackNet.toByteArray().contentEquals(hex(capturedMulti)))
        assertEquals(false, DotNetValue.parseMulti(DotNetValue.buildMulti(tpl, false)))
        assertEquals(0.75, DotNetValue.parseMulti(DotNetValue.buildMulti(tpl, 0.75)))
        assertEquals(42, DotNetValue.parseMulti(DotNetValue.buildMulti(tpl, 42)))
    }
}
