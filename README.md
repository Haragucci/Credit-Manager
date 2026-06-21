# CreditManager

CreditManager ist eine clientseitige Fabric-Mod für Minecraft **1.21.11**, mit der du Forderungen, Schulden, Zahlungen, Item-Verrechnungen und Paylogs übersichtlich verwaltest. Sie wurde für die Nutzung auf **OPSUCHT.net** entwickelt und funktioniert über ein grafisches Menü oder über Client-Befehle.

**Version:** `1.0.2-beta` · **Java:** `21` · **Minecraft:** `1.21.11`

## Funktionen

- Forderungen und Schulden mit Betrag, Fälligkeitsdatum, Bezeichnung und Notiz anlegen
- Teilzahlungen, vollständige Zahlungen, Stornierungen und Löschbestätigungen verwalten
- Item-Zahlungen aus der Hand mit Verrechnungswert und gespeicherten Item-Daten erfassen
- Details zu Geld- und Item-Zahlungen inklusive Vanilla-Item-Tooltip anzeigen
- Automatisch erkannte Transaktionen als Paylogs speichern
- Paylogs nach Spieler, Betrag, Datum und Uhrzeit durchsuchen
  - Teiltreffer wie `20`, `20.06` oder `12:20`
  - Betragsfilter wie `>=100`, `<500` und Bereiche wie `100-500`
  - fehlertolerante Spielernamenssuche
- Statistiken zu offenen Forderungen, Schulden und Saldo mit Zeitraumfilter und Balkendiagramm
- Sichere Statistik-Zurücksetzung mit Backup

## GUI

Beim ersten Öffnen kann zwischen zwei Oberflächen gewählt werden:

- **Modern GUI:** dunkle, themenfähige Oberfläche mit Navigation, Scrollbars, Suchfeldern, Animationen, Toasts und Einstellungen
- **Classic GUI:** kompakte Inventar-/Slot-Oberfläche mit einer passenden dunklen CreditManager-Farbpalette

Die Auswahl lässt sich später in den Einstellungen ändern oder zurücksetzen.

### Modern GUI

Die Modern GUI bietet:

- Übersicht, Forderungen, Schulden, Paylogs, Info und Einstellungen
- scrollbar-sichere Karten, Buttons und Click-Flächen
- animierte Sidebar, Hover-Zustände und AN/AUS-Switches
- Detail- und Zahlungsansichten für Deals
- moderne Item-Inspektion, wenn sie aus der Modern GUI geöffnet wird
- Statistikansicht mit Zeitraumwahl, Saldo, Skala, Legende und Balkendiagramm
- maximal drei nicht-blockierende Toast-Benachrichtigungen gleichzeitig

Das rote **X** und **ESC** schließen die Modern GUI vollständig. Die Inventar-Taste schließt sie ebenfalls, solange kein Textfeld aktiv ist.

### Schriftart und Themes

In den Modern-GUI-Einstellungen kannst du wählen zwischen:

- der mitgelieferten CreditManager-Schriftart
- der normalen Minecraft-/aktiven Resource-Pack-Schrift

Diese Einstellung betrifft ausschließlich die CreditManager Modern GUI. Item-Namen, Item-Tooltips, Chat, Inventar und andere Vanilla-GUIs behalten immer ihre normale Schrift. Zusätzlich stehen Dark-, Light- und Custom-Themes mit eigener Haupt- und Akzentfarbe zur Verfügung.

### Einstellungen

Folgende Funktionen lassen sich direkt im GUI konfigurieren:

- automatische Paylog-Erkennung
- Prüfung von Overlay-/Actionbar-Nachrichten
- Paylog-Benachrichtigungen im Chat
- GUI-Modus, Schriftart und Theme
- Custom-Farben
- Standardzeitraum und Reset der Statistiken

## Öffnen und Befehle

Das Hauptmenü öffnest du mit:

```text
/cm
```

Weitere verfügbare Befehlsnamen sind `/CreditManager`, `/OpCreditmanager` und `/OpCM`.

Die vollständige Hilfe steht im Spiel bereit:

```text
/cm befehle
```

### Schulden

```text
/cm schulden eintragen <spieler> <betrag> [datum] [bezeichnung] [notiz]
/cm schulden liste
/cm schulden zahlen <deal> <betrag>
/cm schulden itemzahlung <deal> <wert>
/cm schulden löschen <deal>
/cm schulden bestätigen <deal>
```

### Forderungen

```text
/cm forderung eintragen <spieler> <betrag> [datum] [bezeichnung] [notiz]
/cm forderung liste
/cm forderung empfangen <deal> <betrag>
/cm forderung itemzahlung <deal> <wert>
/cm forderung löschen <deal>
/cm forderung bestätigen <deal>
```

### Übersicht und Paylogs

```text
/cm übersicht
/cm übersicht schulden
/cm übersicht forderung
/cm info <deal-name oder spieler>
/cm paylogs [spieler|ich] [3t|2w|1m|TT.MM.JJJJ]
```

Bei Item-Zahlungen muss das gewünschte Item in der Haupthand liegen. Datumseingaben verwenden das Format `TT.MM.JJJJ`. Beträge unterstützen Kurzformate wie `500k`, `2m`, `2mio` und `1.5mrd`.

## Installation

1. Installiere den [Fabric Loader](https://fabricmc.net/use/installer/).
2. Installiere die passende [Fabric API](https://modrinth.com/mod/fabric-api).
3. Lade die CreditManager-`.jar` herunter, zum Beispiel über [Modrinth](https://modrinth.com/project/op-credit-manager).
4. Lege die Datei in den Minecraft-Ordner `mods`.
5. Starte Minecraft mit dem Fabric-Profil.

## Entwicklung

```bash
git clone https://github.com/Haragucci/Credit-Manager.git
cd Credit-Manager
./gradlew runClient
```

Unter Windows:

```bat
.\gradlew.bat runClient
```

Build erstellen:

```bash
./gradlew build
```

Die fertigen Artefakte liegen danach in `build/libs/`.

## Screenshots

### Hauptmenü

![Hauptmenü](docs/images/main-menu-preview.png)

### Deal-Ansicht

![Deal-Ansicht](docs/images/deal-screen-preview.png)

### Paylogs

![Paylogs](docs/images/paylogs-preview.png)

## Kontakt und Status

```text
Ingame: 05Haragucci
Discord: haragucci
Status: Beta
```

## Hinweis

CreditManager ist ein privates, inoffizielles Projekt und steht in keiner offiziellen Verbindung zu OPSUCHT.net, Mojang, Microsoft oder Fabric.

## Lizenz

Siehe [LICENSE](LICENSE).
