# DMXC3 Remote (Android)

Android-Client für DMXControl 3.3.x über das offizielle gRPC-Netzwerkinterface (Umbra-Broker).
Kotlin, Jetpack Compose, grpc-java (okhttp, lite) + grpc-kotlin.

## Funktionsumfang 0.1.0
- Discovery: UmbraUdpBroadcast auf Multicast 225.68.67.3, UDP 17474
- Manuelle Verbindung: IP, Port (Standard 17475), NetID
- Session: Login → ReportReadyToWork → Ping-Stream → UserClient.Bind
- Executoren: Live-Liste, Seiten-Tabs, Fader (relativ, gedrosselt 25 ms), Tasten 1–4 (Press/Release)
- Grand Master: Anzeige, BLACK / FULL
- Auto-Reconnect mit Backoff, Display bleibt an

## Build
1. Protos laden (Version = installierte DMXControl-Version):
   - Windows: `powershell -ExecutionPolicy Bypass -File setup-protos.ps1 -Version v3.3.2`
   - Linux:   `./setup-protos.sh v3.3.2`
2. Android Studio (JDK 17) öffnen oder `./gradlew assembleDebug`
3. APK: `app/build/outputs/apk/debug/app-debug.apk`

Die .proto-Dateien werden nicht mitgeliefert: das Repo DMXControl/DMXControl3-Network-Interface enthält keine Lizenzdatei.

## Windows-Host
- Eingehend TCP 17475 (Umbra, HTTP/2 ohne TLS) in der Windows-Firewall freigeben
- Gerät und PC im selben L2-Segment, sonst kommt der Multicast nicht an → manuell verbinden
- WLAN-AP: Multicast/IGMP-Snooping darf 225.68.67.3 nicht blocken (Client-Isolation aus)

## Protokoll-Referenzen
- `csharp/src/UmbraConnectionClient.cs`, `NetConstants.cs`, `UmbraDiscoveryClient.cs` im Network-Interface-Repo
- `src/grpc/grpcclient.ts`, `src/utils.ts` in DMXControl/companion-module-dmxcontrolprojects-dmxcontrol3
