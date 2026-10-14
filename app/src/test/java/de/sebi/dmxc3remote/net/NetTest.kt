package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.CommonTypes.TranslateableData
import org.junit.Assert.assertEquals
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
}
