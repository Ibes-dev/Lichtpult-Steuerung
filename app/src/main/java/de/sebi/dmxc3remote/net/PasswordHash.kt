package de.sebi.dmxc3remote.net

import java.security.MessageDigest
import java.util.Base64

/**
 * DMXControl-3-Passwort-Hash: 37 x SHA-256 über die UTF-8-Bytes, Ergebnis Base64.
 * Identisch zu hashPasswordDMXC() im offiziellen Companion-Modul
 * (36 Schleifendurchläufe + finaler Hash).
 */
object PasswordHash {
    fun dmxc(password: String): String {
        var data = password.toByteArray(Charsets.UTF_8)
        repeat(37) { data = MessageDigest.getInstance("SHA-256").digest(data) }
        return Base64.getEncoder().encodeToString(data)
    }
}
