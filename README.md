# CreditManager

CreditManager ist eine clientseitige Fabric-Mod für Minecraft **1.21.11**. Sie dokumentiert Forderungen, Schulden, Geld- und Item-Zahlungen sowie erkannte Paylogs lokal auf deinem Rechner.

**Version:** `1.1.3-beta` · **Java:** `21` · **Minecraft:** `1.21.11`

**Runtime-Stack:** Fabric Loader `0.18.4` oder neuer · Fabric API `0.141.3+1.21.11` · Loom `1.16.3` · H2 `2.3.232`

## Bedienung

Öffne die GUI mit:

```text
/cm
/CreditManager
/OpCreditManager
```

Dort lassen sich Deals, Zahlungen, Paylogs, Statistiken und Einstellungen verwalten.

## Funktionen

- Forderungen und Schulden mit Betrag, Fälligkeit, Bezeichnung und Notiz
- Geld- und Item-Zahlungen mit Detail- und Item-Ansicht
- Automatische Paylog-Erkennung, Suche und 500er-Paginierung
- Statusfilter für aktive, offene, teilweise bezahlte, bezahlte, abgeschlossene, stornierte und alle Deals einschließlich archivierter Einträge
- Lokale Statistiken sowie Dark-, Light- und Custom-Themes

## Daten

Alle Daten liegen im persistenten Instanzordner unter `CreditManagerLogs/`. Bei LabyMod liegt dieser Ordner direkt unter der jeweiligen LabyMod-Instanz und damit außerhalb des austauschbaren `overlay/`-Ordners.

- Deals, Zahlungen, Ereignisse und Paylogs werden in der lokalen H2-Datenbank `creditmanager.mv.db` gespeichert.
- H2 `2.3.232` ist als Jar-in-Jar im Release-Artefakt eingebettet; es ist keine separate H2-Installation und keine externe Datenbankverbindung erforderlich.
- Alte JSON-Dateien werden beim Start automatisch und verlustfrei migriert; sie werden danach archiviert, nicht gelöscht.
- H2-Sicherungen werden als validierte ZIP-Archive mit Manifest im Unterordner `backups/` angelegt.
- Vollständig logisch validierte Healthy Backups werden zusätzlich in einen pro Instanz isolierten Mirror unter dem stabilen Benutzerprofil gespiegelt. Dieser Mirror liegt außerhalb von Datenroot, Instanz und LabyMod-Overlay und besitzt einen eigenen Katalog sowie eigene Retention.
- Die Recovery-Ansicht trennt den normal wiederherstellbaren Befehl **Gesundes Backup jetzt** klar vom forensischen, nicht automatisch wiederherstellbaren **Recovery-Snapshot**.
- Ein exklusiver Prozess-Lock verhindert, dass zwei Minecraft-Prozesse gleichzeitig dieselbe lokale Datenbank öffnen; eine zweite Instanz bleibt schreibgesperrt und erzeugt keine Ersatzdatenbank.
- Datenbank und `storage_identity.json` tragen dieselbe Storage-Identität. Fehlende oder widersprüchliche Identitäten sowie eine verschwundene Datenbank führen zu einem typisierten, nicht destruktiven Recovery-Zustand.
- Bestätigte Mutationen lösen asynchron zusammengefasste, revisionsgebundene Backup-Checkpoints aus. Der Schutzstatus unterscheidet `HEALTHY`, `LOCAL_ONLY`, `MIRROR_ONLY`, `DEGRADED` und `CRITICAL`; erst nach der begrenzten Grace Policy sperrt `CRITICAL` neue Domain-Änderungen. Bereits bestätigte DB-Commits bleiben gültig.
- Beim Beenden wird der Storage-Lease zuletzt freigegeben. Läuft eine kritische Recovery- oder Backup-Phase über das Zeitlimit hinaus, bleibt der Lease bis zum JVM-Ende gehalten.
- Kann die aktive Datenbank nicht gelesen werden oder ist sie unerwartet leer, sperrt CreditManager Schreibvorgänge und zeigt die Wiederherstellungsansicht statt einer leeren Normal-GUI.
- Eine Wiederherstellung legt die bisherige Datenbank zuerst unter `recovery/quarantine/` ab; sie wird nicht still gelöscht oder überschrieben.
- Item-Zahlungen sind reine Dokumentation und übertragen keine Items an einen Server.

## Vorschau

### Hauptmenü

![CreditManager-Hauptmenü](docs/images/main-menu-preview.png)

### Deal-Ansicht

![CreditManager-Deal-Ansicht](docs/images/deal-screen-preview.png)

### Paylogs

![CreditManager-Paylogs](docs/images/paylogs-preview.png)

## Installation

1. [Fabric Loader](https://fabricmc.net/use/installer/) `0.18.4` oder neuer und [Fabric API](https://modrinth.com/mod/fabric-api) für Minecraft 1.21.11 installieren.
2. Die CreditManager-`.jar` in den Ordner `mods` legen.
3. Minecraft mit Fabric starten.

## Entwicklung und Release

Der vollständige automatisierte Release-Lauf ist:

```powershell
.\gradlew.bat clean test verifyAmountParser check build remapJar verifyReleaseJar verifyNoDevCommandsInRelease verifyReadmeVersion
.\gradlew.bat performanceTest --project-prop=creditmanager.performanceTests=true
```

Die Veröffentlichung darf erst nach dem manuellen Fabric-/OPSUCHT-/Recovery-/GUI-Test erfolgen. Die vollständige Checkliste und die SHA-256-Prüfung stehen in [docs/RELEASE_VALIDATION.md](docs/RELEASE_VALIDATION.md).

## Hinweis und Lizenz

CreditManager ist ein privates, inoffizielles Projekt und steht in keiner offiziellen Verbindung zu OPSUCHT.net, Mojang, Microsoft oder Fabric.

Copyright © 2026 Haragucci / Tilljan. Alle Rechte vorbehalten. Details stehen in [LICENSE](LICENSE).
