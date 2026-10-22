# Lichtpult Remote 1.1.0

## Neu
- 3D-Bühne: Scheinwerfer an den Positionen einer wählbaren StageView, Höhe, Ausrichtung, Neigung, Montage und Abstrahlwinkel je Gerät einstellbar; Lichtkegel und Lichtflecken in Live-Farbe und -Helligkeit, Bewegung über Pan/Tilt, Zoom, Gobo-Andeutung, Strobe; drehen und zoomen per Geste
- Programmer als Arbeitsbereich mit Kopfzeile (Herkunft, Live/Blind, Speichern, Rückgängig, Leeren) und vier Bereichen:
  - Werte: Eigenschaften der Gruppe, wichtige Funktionen zuerst; Fächern mit Verlauf (Operator), Start/Ende, Gruppenverhalten und Gruppengröße; Effekte mit einstellbaren Parametern
  - Inhalt: alles, was im Programmer steht, mit Farbfeld und Wert; antippen öffnet die Gruppe, einzeln entfernbar
  - Presets: aufrufen, zum Bearbeiten laden, Programmer übernehmen (Ersetzen, Zusammenführen, Anhängen, Abziehen), neues Preset anlegen
  - Cues: Cue zum Bearbeiten laden, Programmer als neuen Cue anhängen, Cue ersetzen oder zusammenführen; Laden wahlweise blind
  - Speichern zurück in den geladenen Cue bzw. das Preset, mit Rückfrage bei Abhängigkeiten wie in DMXControl
- Programmer kompakt: Kopfzeile mit Symbolen (Blind, Rückgängig, Leeren, Speichern), schlanke Reiter, Eigenschaften als 48-dp-Zeilen mit Regler und Wert; Farbe, Position und Auswahllisten klappen bei Bedarf auf; Fächern, Effekte und Entfernen im ⋮-Menü
- Effektauswahl als Raster mit Symbolen (Kurvenformen, Figuren, Filter), gruppiert nach Effect 1D, Effect 2D und Filter
- Geräteliste mit DMX-Adressen (Suche nach Name, Nummer, Modell, Adresse); Adresse und einfache Geräteparameter bearbeitbar
- Takt als Eingänge in der Eingangszuweisung: "Takt: Tap", "Takt: Beat", "Takt: Impuls", "Takt: BPM"
- Cuelists: kompakte Zeilenansicht (umschaltbar, wird gespeichert) und Suche – für Projekte mit vielen Cuelists
- Menüpunkt "Neu synchronisieren"
- Cuelist-Karte mit allen Befehlen aus dem DMXControl-Editor: Go, Go Back, Go Next, Pause, Stopp; Go To und Load je Cue in der Cue-Liste

## Geändert
- "Softdesk" heißt in der App jetzt "Bedienfeld", um Verwechslungen mit dem Softdesk von DMXControl zu vermeiden

## Behoben
- Go To sprang nicht zum gewählten Cue. Die App ermittelt jetzt beim ersten Sprung, welche Zählweise DMXControl erwartet.
- Wird nach dem Verbinden ein Projekt geladen oder gewechselt, baut die App die Verbindung automatisch neu auf. Vorher reagierten u. a. Executoren nicht.

# Lichtpult Remote 1.0.0

Erstes öffentliches Release. Inoffizielle Android-Fernsteuerung für DMXControl 3 (kein Produkt des DMXControl Projects e.V.).

## Funktionen
- Verbindung: automatische Suche im Netz, manuell, gespeicherte Profile, automatisches Wiederverbinden
- Executoren mit Seiten, Cuelists (Go/Zurück/Pause/Stopp, Cue-Liste, Fortschritt, Sprung zu Cue), Master, Macros
- Programmer: Dimmer, RGB, Farbrad, Gobo, Strobe, Farbtemperatur, Pan/Tilt, Presets, Effekte, Rücklesen
- Bühnenansicht: Live-Farben, Auswahl per Antippen/Rahmen, Zoom, Highlight, Bearbeiten der Positionen
- Bedienfeld: frei konfigurierbare Tasten, Fader, LEDs und Anzeigen, verdrahtet über die Eingangszuweisung in DMXControl, Export/Import, MIDI-Controller
- Takt: Tap mit Ausreißerfilter, Korrekturen, Mikrofon-Tempo- und Stimmungserkennung (auch als Eingänge für DMXControl)
- Blackout/Freeze, Projekt speichern, DMX-Monitor, Hardware-Übersicht, Diagnose, externe Go-Tasten, Schnelleinstellungs-Kachel

## Voraussetzungen
- Android 8.0 oder neuer
- DMXControl 3.3.x, Rechner und Handy im selben Netz, TCP-Port 17475 in der Windows-Firewall freigegeben

## Bekannte Einschränkungen
- Einige Funktionen beruhen auf nicht dokumentierten Teilen der Schnittstelle (u. a. Beat-Lernen/Sync, Fächern, Highlight, Farbrad/Gobo-Auswahl) und können je nach DMXControl-Version abweichen.
- Verbindung unverschlüsselt (Eigenschaft der DMXControl-Schnittstelle); nur in vertrauenswürdigen Netzen nutzen.

Nutzung auf eigene Verantwortung, ohne Gewähr. Vor Auftritten testen.
