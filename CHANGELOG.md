# Lichtpult Remote 1.2.0

## Neu
- Master-Controller wie in DMXControl: Abschnitte Grand, Gruppen, Parameter, Speed, Position, Farbe; Regler und Tasten (Flash, Stomp, Select, Fix …) aus den Daten von DMXControl, aktive Tasten leuchten
- Speed-Master mit Tipp-Fläche (Learn), Sync, ×2, ½; Position-Master mit XY-Feld; Farb-Master mit Farbfeld, Farbton-Regler und Farbchips
- Einstellung "Master kompakt anzeigen" unter System → Steuerung
- Neue Cuelists anlegen und kopieren (Programmer → Cues: Neu)
- Programmer → Cues: Reihenfolge ändern (Pfeile) und Cues löschen
- 3D-Bühne: StageView wahlweise als Draufsicht oder Frontansicht (System → Steuerung), bei Frontansicht Tiefe je Gerät
- Programmer speichern: neu "In Cuelist" – als neuen Cue an eine Cuelist anhängen oder neue Cuelist mit erstem Cue anlegen

## Behoben
- Speichern in Cues und Presets übernahm den Programmer-Inhalt nicht (Cues/Presets blieben leer); jetzt mit Auswahl des Programmer-Inhalts und Erfolgskontrolle
- Cue und Cuelist umbenennen griff nicht; jetzt mit Erfolgskontrolle
- Preset aufrufen wurde abgelehnt; die App probiert nun die Aufruf-Varianten und merkt sich die passende
- Presets: Eigenschafts-Presets wirken auf die zuletzt gewählte Gerätegruppe; verständliche Meldungen statt "InvokePreset: abgelehnt"

## Geändert
- Einheitliche Fader in der ganzen App (Executoren, Master, Bedienfeld, Programmer, Effekte, Cuelist-Intensität, Audio, 3D-Einrichtung)

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
- Effektauswahl als Raster mit Symbolen, gruppiert nach Effect 1D und Effect 2D
- Effektparameter (Amplitude, Phase, Frequenz …) mit den Werten aus DMXControl als Regler; Effektparameter fächern (z. B. Phase 0 > 180)
- Angelegte Effekte bleiben über Neuverbindungen und App-Neustarts sichtbar; Effekte aus DMXControl werden als "Effekt aktiv" gekennzeichnet
- Geräteliste mit DMX-Adressen (Suche nach Name, Nummer, Modell, Adresse); Adresse und einfache Geräteparameter bearbeitbar
- Takt als Eingänge in der Eingangszuweisung: "Takt: Tap", "Takt: Beat", "Takt: Impuls", "Takt: BPM"
- Cuelists: kompakte Zeilenansicht (umschaltbar, wird gespeichert) und Suche – für Projekte mit vielen Cuelists
- Menüpunkt "Neu synchronisieren"
- Cues und Cuelists umbenennen
- Geräteliste nach Gerätegruppe filtern
- Leiste unten frei anordnen (unter Mehr)
- Cuelist-Karte mit allen Befehlen aus dem DMXControl-Editor: Go, Go Back, Go Next, Pause, Stopp; Go To und Load je Cue in der Cue-Liste

## Geändert
- "Softdesk" heißt in der App jetzt "Bedienfeld", um Verwechslungen mit dem Softdesk von DMXControl zu vermeiden
- Kompakte Suchfelder, Einstellung "Cuelists kompakt" unter System → Steuerung
- Programmer zeigt nur bedienbare Eigenschaften (keine Rohwerte mehr)

## Behoben
- Go To sprang nicht zum gewählten Cue. Die App ermittelt jetzt beim ersten Sprung, welche Zählweise DMXControl erwartet.
- Wird nach dem Verbinden ein Projekt geladen oder gewechselt, baut die App die Verbindung automatisch neu auf. Vorher reagierten u. a. Executoren nicht.
- Programmer-Inhalt aktualisiert sich sofort nach eigenen Änderungen
- Effekte bleiben nach "Programmer leeren" nicht mehr als Geist in DMXControl stehen
- Bühne: Farbwahl auch bei Auswahl einzelner Geräte, Live-Farben korrigiert
- DMX-Monitor zeigt Werte (Kanäle 1-basiert)

## Bekannte Einschränkungen
- Effekte, die nicht in der App angelegt wurden, lassen sich nicht bearbeiten (die Schnittstelle liefert keine Effekt-IDs).
- 3D-Bühne: Pan/Tilt-Nullstellung und Zoom-Winkel sind Näherungen, Gobos nur angedeutet.

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
