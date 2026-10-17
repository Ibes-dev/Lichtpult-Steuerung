# Lichtpult Remote 1.0.0

Erstes öffentliches Release. Inoffizielle Android-Fernsteuerung für DMXControl 3 (kein Produkt des DMXControl Projects e.V.).

## Funktionen
- Verbindung: automatische Suche im Netz, manuell, gespeicherte Profile, automatisches Wiederverbinden
- Executoren mit Seiten, Cuelists (Go/Zurück/Pause/Stopp, Cue-Liste, Fortschritt, Sprung zu Cue), Master, Macros
- Programmer: Dimmer, RGB, Farbrad, Gobo, Strobe, Farbtemperatur, Pan/Tilt, Presets, Effekte, Rücklesen
- Bühnenansicht: Live-Farben, Auswahl per Antippen/Rahmen, Zoom, Highlight, Bearbeiten der Positionen
- Softdesk: frei konfigurierbare Tasten, Fader, LEDs und Anzeigen, verdrahtet über die Eingangszuweisung in DMXControl, Export/Import, MIDI-Controller
- Takt: Tap mit Ausreißerfilter, Korrekturen, Mikrofon-Tempo- und Stimmungserkennung (auch als Eingänge für DMXControl)
- Blackout/Freeze, Projekt speichern, DMX-Monitor, Hardware-Übersicht, Diagnose, externe Go-Tasten, Schnelleinstellungs-Kachel

## Voraussetzungen
- Android 8.0 oder neuer
- DMXControl 3.3.x, Rechner und Handy im selben Netz, TCP-Port 17475 in der Windows-Firewall freigegeben

## Bekannte Einschränkungen
- Einige Funktionen beruhen auf nicht dokumentierten Teilen der Schnittstelle (u. a. Beat-Lernen/Sync, Fächern, Highlight, Farbrad/Gobo-Auswahl) und können je nach DMXControl-Version abweichen.
- Verbindung unverschlüsselt (Eigenschaft der DMXControl-Schnittstelle); nur in vertrauenswürdigen Netzen nutzen.

Nutzung auf eigene Verantwortung, ohne Gewähr. Vor Auftritten testen.
