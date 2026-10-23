# Lichtpult Remote (Android)

Inoffizielle Android-Fernsteuerung für **DMXControl 3** über dessen Netzwerkschnittstelle (gRPC/Umbra).
Kein Produkt des DMXControl Projects e.V. und nicht von ihm unterstützt.

> Nutzung auf eigene Verantwortung, ohne Gewähr. Die App schaltet Licht, löst Blackout aus und kann
> Bühnenansichten in DMXControl ändern oder löschen. Vor Auftritten testen.

## Installation
1. Neueste `LichtpultRemote-vX.Y.Z.apk` unter **Releases** herunterladen.
2. Auf dem Handy öffnen und die Installation aus dieser Quelle erlauben.
3. In DMXControl auf dem Windows-PC: eingehend **TCP 17475** in der Windows-Firewall freigeben.
4. App starten: Der PC erscheint unter "Im Netzwerk gefunden" (antippen) oder IP-Adresse manuell eingeben.

**Voraussetzungen:** Android 8.0+, DMXControl 3.3.x, Handy und PC im selben Netz. Für die automatische Suche
muss der Access Point Multicast durchlassen (225.68.67.3, UDP 17474).

## Funktionen
Siehe [CHANGELOG.md](CHANGELOG.md). Kurz: Executoren, Cuelists, Master, Macros, Programmer, Bühnenansicht,
frei konfigurierbare Bedienfelder (über die Eingangszuweisung), Takt/Tap, Mikrofon-Tempo und -Stimmung,
MIDI-Controller, externe Go-Tasten, Diagnose.

## Sicherheit und Datenschutz
- Die Verbindung zu DMXControl ist unverschlüsselt (Eigenschaft der Schnittstelle). Nur in vertrauenswürdigen Netzen nutzen.
- Daten gehen nur an den gewählten DMXControl-Rechner. Das Mikrofon wird nur bei eingeschalteter Erkennung
  genutzt und lokal ausgewertet; keine Aufnahmen werden gespeichert oder übertragen.
- App-Daten werden nicht in Android-Backups übernommen (Bedienfelder lassen sich über Export sichern).

## Bauen
```
./setup-protos.sh v3.3.2          # Windows: powershell -File setup-protos.ps1 -Version v3.3.2
./gradlew testDebugUnitTest assembleDebug
```
Der Anzeigename steht in `gradle.properties` (`appName`).

## Release erstellen
1. Einmalig Release-Schlüssel anlegen und **sicher aufbewahren** (ohne ihn keine Updates möglich):
   ```
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias lichtpult
   ```
2. Repository → Settings → Secrets and variables → Actions:
   `KEYSTORE_BASE64` (Base64 der .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   Base64 unter Windows: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard`
3. `versionName` in `app/build.gradle.kts` und `CHANGELOG.md` anpassen, dann taggen:
   ```
   git tag v1.0.0 && git push origin v1.0.0
   ```
   Der Workflow baut, prüft die Signatur und legt das GitHub-Release mit APK an.

Der Debug-Schlüssel `app/debug.keystore` ist absichtlich öffentlich (nur für Test-Builds). Release-APKs nie damit signieren.

## Lizenz
Eigener Quellcode: CC0 1.0. Drittanbieter-Komponenten und ihre Lizenzen: [NOTICE.md](NOTICE.md).
Die Protokolldefinitionen (DMXControl3-Network-Interface) werden beim Build geladen und nicht im Repository verteilt.
