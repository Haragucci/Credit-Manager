# CreditManager

CreditManager ist eine Minecraft Fabric Mod, mit der Spieler ihre Schulden, Forderungen und Zahlungen übersichtlich verwalten können.

Die Mod wurde speziell für die Nutzung auf **OPSUCHT.net** entwickelt und bietet ein eigenes GUI-System, Paylogs, Item-Zahlungen mit Verrechnungswert und einen Expertenmodus über Befehle.

## Features

- Schulden und Forderungen zwischen Spielern verwalten
- Übersichtliches Hauptmenü per GUI
- Detailansicht für einzelne Deals
- Zahlungen eintragen und verwalten
- Item-Zahlungen mit eigenem Verrechnungswert
- Paylogs mit gespeicherten Transaktionen
- Datum und Uhrzeit bei Zahlungen und Einträgen
- Filter für offene, teilweise bezahlte, bezahlte und stornierte Deals
- Expertenmodus mit Befehlen statt GUI

## Wofür ist CreditManager gedacht?

CreditManager hilft dir dabei, den Überblick über Ingame-Schulden und Forderungen zu behalten.

Beispiele:

- Ein Spieler leiht dir Geld
- Du leihst einem Spieler Geld
- Eine Schuld wird teilweise bezahlt
- Eine Zahlung wird mit Items verrechnet
- Du willst später nachvollziehen, wann welche Zahlung eingetragen wurde

Alle wichtigen Informationen können entweder bequem über das GUI oder schneller über Befehle verwaltet werden.

## GUI-Modus

Der normale Modus ist das grafische Menü.

Öffnen kannst du es mit:

```text
/cm
```

oder:

```text
/CreditManager
```

Im GUI kannst du deine Schulden, Forderungen, Zahlungen und Paylogs übersichtlich ansehen und verwalten.

## Expertenmodus

Für Spieler, die lieber schnell mit Befehlen arbeiten, gibt es den Expertenmodus.

Die Befehlsübersicht öffnest du mit:

```text
/cm befehle
```

oder:

```text
/CreditManager befehle
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
/cm forderung löschen <deal>
/cm forderung bestätigen <deal>
```

### Übersicht

```text
/cm übersicht
/cm übersicht schulden
/cm übersicht forderung
```

### Weitere Befehle

```text
/cm info <deal-name oder spieler>
/cm paylogs [spieler|ich] [3t|2w|1m|TT.MM.JJJJ]
/cm befehle
```

## Paylogs

CreditManager speichert alle Transaktionen von OPSUCHT in Paylogs.

Dadurch kannst du später nachvollziehen:

- wer gezahlt hat
- an wen gezahlt wurde
- wann gezahlt wurde
- welcher Betrag gezahlt wurde

## Item-Zahlungen

Neben normalen Geldzahlungen können auch Items als Zahlung eingetragen werden.

Dabei wird ein Verrechnungswert angegeben, damit Items wie eine normale Zahlung auf eine Schuld oder Forderung angerechnet werden können.

Beispiel:

```text
/cm schulden itemzahlung diamond-deal 50000
```

Dabei wird das Item aus deiner Hand als Zahlung mit einem Wert von `50000` eingetragen.

## Minecraft-Version

Diese Mod ist aktuell für folgende Version gebaut:

```text
Minecraft: 1.21.11
```

## Installation

1. Installiere den Fabric Loader.
2. Installiere die passende Fabric API.
3. Lade die CreditManager `.jar` herunter.
4. https://modrinth.com/project/op-credit-manager
5. Lege die `.jar` in deinen Minecraft-`mods`-Ordner.
6. Starte Minecraft mit dem Fabric-Profil.

## Development Setup

Projekt klonen:

```bash
git clone https://github.com/Haragucci/Credit-Manager.git
cd Credit-Manager
```

Client starten:

```bash
./gradlew runClient
```

Unter Windows:

```bat
gradlew runClient
```

Mod bauen:

```bash
./gradlew build
```

Die fertige `.jar` befindet sich danach im Ordner:

```text
build/libs/
```

## Screenshots

### Hauptmenü

![Hauptmenü](docs/images/main-menu-preview.png)

### Deal-Ansicht

![Deal-Ansicht](docs/images/deal-screen-preview.png)

### Paylogs

![Paylogs](docs/images/paylogs-preview.png)


## Kontakt

Bei Fragen, Bugs oder Vorschlägen kannst du mich erreichen:


```text
Ingame: 05Haragucci
Discord: haragucci__
```

## Status

```text
Beta
```

Die Mod ist grundsätzlich funktionsfähig, kann aber noch Fehler enthalten.

## Hinweis

Diese Mod ist ein eigenes Projekt und wurde für die Nutzung auf **OPSUCHT.net** entwickelt.

CreditManager ist nicht offiziell mit Mojang, Microsoft, Fabric oder OPSUCHT.net verbunden, sofern nicht anders angegeben.

## Lizenz

Siehe Datei `LICENSE`.
