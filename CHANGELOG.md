# Lichtpult Remote 1.4.0

## Neu
- **Designs:** Dunkel, Schwarz (OLED), Klassisch (Pult), Modern und Hell – unter System → Projekt → Design.
- **Bedienfeld im Vollbild:** ohne Leisten, Bildschirm bleibt an, Verbindungspunkt und Beenden oben rechts. Nach einer Unterbrechung (z. B. Display gesperrt) geht es nach dem automatischen Neuverbinden im Vollbild weiter.
- **Neue Bedienfeld-Elemente:** Drehregler, XY-Feld (X und Y als zwei Eingänge), Auswahl (Optionen wie Szene A/B/C), Cuelist-Monitor (Zustand, aktueller/nächster Cue, Go/Zurück/Stop), Cuelist-Auswahl (antippen = Go, lange drücken = Stop), Geräte-Auswahl (Gruppen oder Einzelgeräte für den Programmer), Uhr.
- **Mehr Gestaltung je Element:** eigene Farbe (#RRGGBB), Schriftgröße S–XXL, Beschriftung ausblenden, farbiger Rahmen. Das Raster bleibt.
- **Anlässe** unter System → Bedienfeld: festlegen, welche Bedienfelder z. B. bei „Konzert“ oder „Fasnet“ sichtbar sind; ausgeblendete bleiben in DMXControl angemeldet. Anlässe werden mit exportiert.
- **System → Hardware:** DMX-Interfaces zeigen Kommentar, Instanznummer und weitere Angaben (z. B. IP) – gleiche Interfaces sind so unterscheidbar. Den Kommentar kannst du in der App ändern.
- **3D-Bühne:** Gobos je Gobo-Nummer unterschiedlich dargestellt, mit Gobo-Rotation und -Indexierung.

## Geändert
- Die App öffnet beim Start den zuletzt genutzten Bereich.

## Behoben
- Bedienfeld: Werte aus der Zuweisung wurden falsch oder gar nicht gelesen (z. B. von Konstante, Binary Switcher oder Intensity des Programmer-Bausteins); Fader und Anzeigen folgen jetzt auch ohne Rule of Three.
- 3D-Bühne: Gobos von Moving Heads wurden nicht erkannt, wenn das Gerät auch Goborotation/Goboindex hat, oder bei mehreren Gobo-Rädern.
- Fehlermeldungen im Umbra-Log beim Verbinden („ProgrammerChanges … has faulted“) und beim Trennen („The client reset the request stream“): Die App meldet sich jetzt sauber ab.

# Lichtpult Remote 1.3.0

## Neu
- **Zuweisung** (neuer Menüpunkt, Eingangszuweisung von DMXControl): Bänke ein-/ausschalten, Verbindungs-Sets anlegen, umbenennen, löschen und ein-/ausschalten, Fehler-Badge wie in DMXControl.
- **Übersicht als echter Graph** an den Positionen aus DMXControl, mit Live-Werten, Zoom und Verschieben; Bausteine per langem Drücken verschieben.
- **Fokus-Ansicht** je Baustein mit Kabeln zu Vorgängern und Nachfolgern; Verbinden über Auswahlliste, Lösen, Bausteine sowie Ein-/Ausgänge hinzufügen und entfernen.
- **Baustein-Werte** als Textfelder wie in der Windows-Oberfläche, inklusive Festwerten der Eingänge (z. B. Fade Factor, Switch threshold, On/Off Value).
- **Programmer → Cues:** Cues verschieben, löschen und umbenennen; neue Cuelists anlegen und kopieren.
- **Programmer speichern:** neu "In Cuelist" (als neuen Cue anhängen oder neue Cuelist mit erstem Cue).
- **3D-Bühne:** StageView wahlweise als Draufsicht oder Frontansicht (System → Steuerung).
- **Diagnose:** Tiefentest und Änderungs-Mitschnitt für die Zuweisung.

## Behoben
- Master-Fader ließen sich in der App nicht bewegen (Anzeige ging, Ändern nicht).
- Speichern in Cues und Presets übernahm den Programmer-Inhalt nicht.
- Preset aufrufen wurde abgelehnt; Eigenschafts-Presets wirken jetzt auf die gewählte Gerätegruppe, verständliche Meldungen statt "abgelehnt".
- Cue und Cuelist umbenennen griff nicht.

# Lichtpult Remote 1.2.0

## Neu
- Master-Controller wie in DMXControl: Abschnitte Grand, Gruppen, Parameter, Speed, Position, Farbe; Regler und Tasten (Flash, Stomp, Select, Fix …) aus den Daten von DMXControl, aktive Tasten leuchten
- Speed-Master mit Tipp-Fläche (Learn), Sync, ×2, ½; Position-Master mit XY-Feld; Farb-Master mit Farbfeld, Farbton-Regler und Farbchips
- Einstellung "Master kompakt anzeigen" unter System → Steuerung

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
