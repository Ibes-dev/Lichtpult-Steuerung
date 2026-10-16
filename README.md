# DMXC3 Remote (Android)

Android-Client für DMXControl 3.3.x über das offizielle gRPC-Netzwerkinterface (Umbra-Broker).
Kotlin, Jetpack Compose, grpc-java (okhttp, lite) + grpc-kotlin.

## Funktionsumfang 0.2.0
- Verbindung: Discovery (Multicast 225.68.67.3:17474), manuell, Freigabe-Anfrage (InformClientExists), Auto-Reconnect
- Top-Bar: Blackout, Freeze, Projekt speichern (Dirty-Anzeige), Beat-LED
- Executoren: Fader, Tasten 1–4, Seiten (lokal oder mit DMXC-UserContext synchronisiert)
- Cuelists: GO / Back / Pause / Stop, Intensität, aktueller/nächster Cue, Gruppenfilter, Alle STOP
- Master: alle Master; Intensität per Fader, FULL/BLACK; Speed-Master LEARN/SYNC/×2/÷2
- Macros: Tasten (Press/Release), Fader
- Programmer: Gerätegruppen wählen, Dimmer/Double-Werte (Bereich aus Property-Typ), RGB + Farbpalette, Pan/Tilt-XY-Pad, Bool-Schalter, Clear; Presets (gezielt auf Gruppe oder global)
- Medien: Manual Beat (Tap, Sync, Faktor, BPM), Timecode (Play/Pause/Stop, Laufzeit), Audio-Player (Play/Pause/Stop, Lautstärke)
- Konsole: Kommandozeile mit Verlauf, Benutzer-Meldungen
- System: Projektstatus, Hauptschalter, DMX-Monitor (512 Kanäle je Universum), DMX-Interfaces an/aus, Plugins an/aus, RDM-Liste + Discovery, Stromkreise mit Last, Modulstatus
- Jedes Modul läuft unabhängig: Fehler eines Bereichs trennen die Sitzung nicht

## Neu in 0.4.0
- Verbindungsprofile, automatisches Verbinden beim Start, sofortiger Neuversuch bei Netzwechsel
- Softdesk Export/Import (JSON), MIDI-Controller (USB/Bluetooth) mit MIDI-Learn und LED-Rückmeldung
- Externe Go-Tasten (Fußtaster, Presenter, Medientasten, optional Lautstärke), Schnelleinstellungs-Kachel Blackout
- Cuelist-Details mit Cue-Liste, Fade-Fortschritt und Sprung zu Cue
- Programmer: Werte zurücklesen, Effekte setzen/entfernen, Fächern (ungeprüft)
- Bühnenansicht (StageView) mit Auswahl per Antippen
- Diagnose: Ping-Laufzeit, Status je Bereich, API-Tests (Beat-Einheit, Learn/Sync, Master-Fader), Aufrufprotokoll, Export
- Beat: Tap auf Finger-Down, Regression, Ausreißerfilter, Rundung, Nudges, Learn/Sync mit wählbarer Zeitbasis,
  Mikrofon-Tempoerkennung mit Hybrid-Modus, App-Metronom und Vibration pro Beat

## Build
1. Protos laden (Version = installierte DMXControl-Version):
   - Windows: `powershell -ExecutionPolicy Bypass -File setup-protos.ps1 -Version v3.3.2`
   - Linux:   `./setup-protos.sh v3.3.2`
2. `./gradlew testDebugUnitTest assembleDebug`

## CI (GitHub Actions)
- Jeder Push: Unit-Tests + Debug-APK, versionCode = Run-Nummer
- Signierte Release-APK, wenn diese Repository-Secrets gesetzt sind:
  `KEYSTORE_BASE64` (base64 der .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
  Keystore erzeugen: `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias dmxc3remote`
  Base64 (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard`

## Nicht enthalten (bewusst, Bearbeitung gehört in die DMXC3-GUI)
StageView, Settings, Parameter, Inputs/MacroBoards, ItemLists, Affinity, Attachables/Effekte, FannedValues,
Projekt laden/importieren, Geräte/Cues anlegen oder löschen.

## Windows-Host
- Eingehend TCP 17475 (Umbra, HTTP/2 ohne TLS) freigeben
- Gerät und PC im selben L2-Segment, sonst manuell verbinden
