# Entwicklung und Release-Prüfung

## Voraussetzungen

- Java 21
- Keine lokale Entwicklungs- oder Testdaten-Erweiterung im Quellbaum
- Ein sauberer oder bewusst geprüfter Git-Arbeitsstand

## Automatisierte Prüfung

Vor jedem Release aus dem Projektverzeichnis ausführen:

```powershell
.\gradlew.bat clean test verifyAmountParser check verifyReleaseJar verifyNoDevCommandsInRelease
```

Der Lauf muss erfolgreich enden. Er prüft insbesondere:

- JUnit-Tests für Persistenz, Recovery, Migration, Paylogs und Parser
- den Betrag-Parser
- README-Version und Access Widener
- das remappte Release-JAR
- eingebettetes H2
- fehlende Entwicklungs- und Testartefakte im Release-JAR

Das Release-Artefakt liegt anschließend unter `build/libs/`. Die veröffentlichte Datei ist das normale JAR, nicht das `-sources.jar`.

## Manueller Fabric-Smoke-Test

Den folgenden Ablauf mit Minecraft Fabric 1.21.11 und einem leeren Spielordner durchführen:

1. Mod starten und `/cm` öffnen.
2. Einen Deal erstellen sowie eine Geld- und eine Item-Zahlung erfassen.
3. Einen OPSUCHT-Paylog im Chat erkennen lassen und mit einem Deal manuell verknüpfen.
4. Automatisches Paylog-Linking mit exakter Zahlung, Teilzahlung und Überzahlung prüfen.
5. Paylogs, History und Statistiken durchsuchen.
6. Settings ändern, Minecraft neu starten und die gespeicherten Werte prüfen.
7. Eine Kopie der Datenbank oder Config absichtlich beschädigen und kontrollieren, dass die Recovery-Ansicht erscheint und keine Daten still überschrieben werden.
8. Eine Wiederherstellung aus einem vorhandenen Backup ausführen und die ursprünglichen Daten prüfen.

## Freigabekriterien

Ein öffentliches Release ist nur bereit, wenn:

- alle automatisierten Prüfungen grün sind;
- der manuelle Smoke-Test vollständig erfolgreich war;
- keine offenen `ERROR`-Health-Records bestehen;
- die Release-Datei H2 enthält und keine Entwicklungs- oder Test-Kommandos enthält;
- Recovery oder Migration keine Daten ohne validiertes Backup löschen oder überschreiben.
