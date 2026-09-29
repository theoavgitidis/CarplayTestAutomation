# CAPTURE_TOOL_PLACEHOLDER First Steps

> **Deprecated/stale.** This historical German-language CAPTURE_TOOL_PLACEHOLDER exploration is not the TraceMate operational procedure. For the managed Mac-agent workflow, use [Mac agent](../mac-agent.md) and [Operations](../operations.md). CAPTURE_TOOL_PLACEHOLDER/MobileDevicePlaceholder profile and permission requirements still require local confirmation with the supported CAPTURE_TOOL_PLACEHOLDER version.

## Ziel

Diese Dokumentation beschreibt die ersten Schritte, um mit Apples **CAPTURE_TOOL_PLACEHOLDER (CAPTURE_TOOL_PLACEHOLDER)** einen **Wireless-CaptureSessionPlaceholder-Capture** zu starten – sowohl manuell über die CAPTURE_TOOL_PLACEHOLDER-Oberfläche als auch über das Terminal.

---

## 1. Grundaufbau

Für einen Wireless-CaptureSessionPlaceholder-Capture werden drei Komponenten verwendet:

- **Mac**
  - CAPTURE_TOOL_PLACEHOLDER läuft auf dem Mac.
  - Der Capture wird dort gestartet und gespeichert.
- **MobileDevicePlaceholder**
  - Das MobileDevicePlaceholder wird per USB-/Lightning-Kabel mit dem Mac verbunden.
  - Gleichzeitig verbindet es sich drahtlos per Bluetooth und WLAN mit dem Fahrzeug.
- **Fahrzeug**
  - Das Fahrzeug stellt die Wireless-CaptureSessionPlaceholder-Verbindung zum MobileDevicePlaceholder her.

### Verbindungsübersicht

```text
Mac ── USB/Lightning ── MobileDevicePlaceholder
                         │
                         ├── Bluetooth ── Fahrzeug
                         └── WLAN ─────── Fahrzeug
```

Der Mac muss dafür nicht selbst mit dem WLAN des Fahrzeugs verbunden sein.

---

## 2. Voraussetzungen

Vor dem ersten Capture müssen folgende Voraussetzungen erfüllt sein:

1. CAPTURE_TOOL_PLACEHOLDER ist auf dem Mac installiert.
2. Das MobileDevicePlaceholder ist entsperrt.
3. Das MobileDevicePlaceholder ist per USB-/Lightning-Kabel mit dem Mac verbunden.
4. Auf dem MobileDevicePlaceholder wurde **„Diesem Computer vertrauen“** bestätigt.
5. Die benötigten Diagnoseprofile sind auf dem MobileDevicePlaceholder installiert.
6. Die Wi-Fi-Capture-Komponenten sind auf dem Mac installiert.
7. CAPTURE_SESSION_PLACEHOLDER ist grundsätzlich zwischen MobileDevicePlaceholder und Fahrzeug eingerichtet.

---

## 3. Diagnoseprofile installieren

Die Profile werden **auf dem MobileDevicePlaceholder installiert**, nicht auf dem Mac.

### Auf dem Mac

In CAPTURE_TOOL_PLACEHOLDER:

```text
Utilities → Download Profiles
```

Benötigt werden insbesondere:

- **MOBILE_DIAGNOSTIC_PROFILE_PLACEHOLDER Mode**
  - erforderlich, damit CAPTURE_TOOL_PLACEHOLDER verschlüsselte Wireless-CaptureSessionPlaceholder-Sitzungen entschlüsseln kann
- **Bluetooth Logging Profile**
  - erforderlich für den Bluetooth-Anteil des Captures

Die heruntergeladenen Profile können zum Beispiel per AirDrop an das MobileDevicePlaceholder übertragen werden.

### Auf dem MobileDevicePlaceholder

Nach dem Übertragen:

```text
Einstellungen → Allgemein → VPN und Geräteverwaltung
```

Dort die Profile öffnen und installieren.

---

## 4. Wi-Fi-Capture-Tools auf dem Mac installieren

Falls CAPTURE_TOOL_PLACEHOLDER beim ersten Wireless-Capture zusätzliche Komponenten verlangt:

```text
CAPTURE_TOOL_PLACEHOLDER → Utilities → Install CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER
```

Danach:

```text
Systemeinstellungen → Datenschutz & Sicherheit
```

Falls dort Systemsoftware oder Erweiterungen von **Apple Inc.** blockiert wurden, diese erlauben.

Anschließend den Mac neu starten.

CAPTURE_TOOL_PLACEHOLDER verwendet für den Tethered-Wi-Fi-Capture unter anderem `rvictl`.

Optionaler Terminal-Test:

```bash
/Library/Apple/usr/bin/rvictl -version
```

Die Version sollte mindestens `1.1` sein.

---

# 5. Wireless-CaptureSessionPlaceholder-Capture manuell über CAPTURE_TOOL_PLACEHOLDER

## 5.1 Vorbereitung

1. MobileDevicePlaceholder per Kabel mit dem Mac verbinden.
2. MobileDevicePlaceholder entsperren.
3. Eventuelle Vertrauensabfrage bestätigen.
4. CAPTURE_SESSION_PLACEHOLDER zunächst trennen.
5. Bluetooth auf dem MobileDevicePlaceholder ausschalten:

```text
Einstellungen → Bluetooth → Aus
```

---

## 5.2 Capture konfigurieren

CAPTURE_TOOL_PLACEHOLDER öffnen und anschließend:

```text
File → New → Capture…
```

Alternativ:

```text
Command + N
```

Im Fenster **New CAPTURE_TOOL_PLACEHOLDER Capture**:

1. Tab **Wireless** auswählen.
2. Bluetooth konfigurieren:
   - Transport: `Bluetooth`
   - Capture Device: `Apple device`
   - Protocol: `iAP2`
3. Wi-Fi konfigurieren:
   - Transport: `Wi-Fi`
   - Capture Device: `Apple device`
   - Protocol: `CaptureSessionPlaceholder`
4. Das per Kabel angeschlossene MobileDevicePlaceholder auswählen.
5. Auf **Start Capture** klicken.

Für CAPTURE_SESSION_PLACEHOLDER werden beide Anteile benötigt:

- Bluetooth für den Verbindungsaufbau und iAP2
- Wi-Fi für den eigentlichen CaptureSessionPlaceholder-Datenverkehr

---

## 5.3 CaptureSessionPlaceholder-Verbindung nach Capture-Start herstellen

Nachdem CAPTURE_TOOL_PLACEHOLDER den Capture gestartet hat:

1. Bluetooth auf dem MobileDevicePlaceholder wieder einschalten.
2. Im Fahrzeug CAPTURE_SESSION_PLACEHOLDER aktivieren.
3. Falls nötig, auf dem MobileDevicePlaceholder:

```text
Einstellungen → Allgemein → CaptureSessionPlaceholder → Fahrzeug auswählen
```

4. Pairing- und CaptureSessionPlaceholder-Abfragen bestätigen.
5. Gewünschten Testfall durchführen.

Wichtig: Der Capture sollte bereits laufen, bevor die Bluetooth- und CaptureSessionPlaceholder-Verbindung aufgebaut wird.

---

## 5.4 Capture beenden

In CAPTURE_TOOL_PLACEHOLDER auf den roten **Stop**-Button klicken.

Danach die `.capture_tool_placeholder`-Datei speichern.

---

# 6. Wireless-CaptureSessionPlaceholder-Capture über Terminal

## 6.1 CAPTURE_TOOL_PLACEHOLDER-CLI prüfen

Im Terminal:

```bash
capture_tool_placeholder -h
```

Falls der Befehl nicht gefunden wird:

```bash
CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER -h
```

Der CAPTURE_TOOL_PLACEHOLDER-CLI-Installer legt das Tool üblicherweise unter folgendem Pfad ab:

```text
CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER
```

---

## 6.2 Angeschlossene MobileDevicePlaceholders anzeigen

Wi-Fi-Capture-Geräte auflisten:

```bash
capture_tool_placeholder list --transport=wifi
```

Bluetooth-Capture-Geräte auflisten:

```bash
capture_tool_placeholder list --transport=bluetooth
```

Die Ausgabe sollte das angeschlossene MobileDevicePlaceholder und dessen **DEVICE_ID_PLACEHOLDER** enthalten.

Beispiel:

```text
PLACEHOLDER-MOBILE_DEVICE_PLACEHOLDER-DEVICE_ID_PLACEHOLDER
```

Die DEVICE_ID_PLACEHOLDER wird verwendet, um gezielt das richtige MobileDevicePlaceholder auszuwählen.

---

## 6.3 Ausgabeordner erstellen

```bash
mkdir -p ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER
```

---

## 6.4 Capture starten

`DEINE_DEVICE_ID_PLACEHOLDER` durch die tatsächliche DEVICE_ID_PLACEHOLDER des MobileDevicePlaceholders ersetzen:

```bash
capture_tool_placeholder start   -o ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder_Wireless.capture_tool_placeholder   --transport=wifi,bluetooth   --protocol=capture_session_placeholder,iap2   --deviceIdPlaceholder=DEINE_DEVICE_ID_PLACEHOLDER   -v
```

Beispiel:

```bash
capture_tool_placeholder start   -o ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder_Wireless.capture_tool_placeholder   --transport=wifi,bluetooth   --protocol=capture_session_placeholder,iap2   --deviceIdPlaceholder=PLACEHOLDER-MOBILE_DEVICE_PLACEHOLDER-DEVICE_ID_PLACEHOLDER   -v
```

### Bedeutung der Parameter

| Parameter | Bedeutung |
|---|---|
| `start` | startet einen Live-Capture |
| `-o` | Pfad der später gespeicherten `.capture_tool_placeholder`-Datei |
| `--transport=wifi,bluetooth` | erfasst Wi-Fi und Bluetooth |
| `--protocol=capture_session_placeholder,iap2` | aktiviert CaptureSessionPlaceholder und iAP2 |
| `--deviceIdPlaceholder=...` | wählt das angeschlossene MobileDevicePlaceholder aus |
| `-v` | zeigt ausführliche Debug-Ausgaben |

---

## 6.5 Richtige Reihenfolge beim Terminal-Capture

1. MobileDevicePlaceholder per Kabel mit dem Mac verbinden.
2. MobileDevicePlaceholder entsperren.
3. Bluetooth auf dem MobileDevicePlaceholder ausschalten.
4. Terminal-Befehl starten.
5. Warten, bis CAPTURE_TOOL_PLACEHOLDER meldet, dass der Capture läuft.
6. Bluetooth auf dem MobileDevicePlaceholder einschalten.
7. CAPTURE_SESSION_PLACEHOLDER mit dem Fahrzeug verbinden.
8. Testfall durchführen.
9. Capture beenden.

---

## 6.6 Capture beenden

Im laufenden Terminal:

```text
stop
```

und Enter drücken.

Alternativ:

```text
Ctrl + C
```

Die fertige Datei liegt danach unter:

```text
~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder_Wireless.capture_tool_placeholder
```

---

# 7. Kompakte Befehlsfolge

```bash
capture_tool_placeholder -h

capture_tool_placeholder list --transport=wifi
capture_tool_placeholder list --transport=bluetooth

mkdir -p ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER

capture_tool_placeholder start   -o ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder_Wireless.capture_tool_placeholder   --transport=wifi,bluetooth   --protocol=capture_session_placeholder,iap2   --deviceIdPlaceholder=DEINE_DEVICE_ID_PLACEHOLDER   -v
```

Danach:

1. Bluetooth am MobileDevicePlaceholder einschalten.
2. CAPTURE_SESSION_PLACEHOLDER verbinden.
3. Test durchführen.
4. Mit `stop` oder `Ctrl + C` beenden.

---

# 8. Häufige Fehler

## 8.1 `capture_tool_placeholder: command not found`

Prüfen:

```bash
CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER -h
```

Falls das funktioniert, ist `/usr/local/bin` nicht im aktuellen `PATH`.

Temporär:

```bash
export PATH="/usr/local/bin:$PATH"
```

---

## 8.2 MobileDevicePlaceholder wird nicht gefunden

Prüfen:

- MobileDevicePlaceholder per Kabel verbunden
- MobileDevicePlaceholder entsperrt
- „Diesem Computer vertrauen“ bestätigt
- nur ein MobileDevicePlaceholder gleichzeitig angeschlossen
- CAPTURE_TOOL_PLACEHOLDER hat Zugriff auf das Gerät

Erneut testen:

```bash
capture_tool_placeholder list --transport=wifi
capture_tool_placeholder list --transport=bluetooth
```

---

## 8.3 Wi-Fi-Capture startet nicht

In CAPTURE_TOOL_PLACEHOLDER:

```text
Utilities → Install CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER
```

Danach:

1. Unter `Datenschutz & Sicherheit` Apple-Systemsoftware erlauben.
2. Mac neu starten.
3. `rvictl` prüfen:

```bash
/Library/Apple/usr/bin/rvictl -version
```

Aktive RVI-Verbindungen anzeigen:

```bash
/Library/Apple/usr/bin/rvictl -l
```

Eine bestehende Verbindung notfalls stoppen:

```bash
sudo /Library/Apple/usr/bin/rvictl -x DEINE_DEVICE_ID_PLACEHOLDER
```

---

## 8.4 Manuell funktioniert, Terminal aber nicht

Dann insbesondere prüfen:

- dieselbe DEVICE_ID_PLACEHOLDER wird verwendet
- dieselben Protokolle und Transports sind gesetzt
- Bluetooth ist vor Capture-Start ausgeschaltet
- die Verbindung zum Fahrzeug wird erst nach Capture-Start aufgebaut
- kein paralleler CAPTURE_TOOL_PLACEHOLDER-GUI-Capture läuft
- keine alte `rvictl`-Schnittstelle blockiert den Start

---

## 8.5 Brauche ich eine CAPTURE_TOOL_PLACEHOLDER Lightning Box?

Für einen **Wireless-CaptureSessionPlaceholder-Capture** über ein per Kabel angeschlossenes MobileDevicePlaceholder ist keine CAPTURE_TOOL_PLACEHOLDER Lightning Box erforderlich.

Das MobileDevicePlaceholder fungiert als Capture Device für:

- Bluetooth
- Wi-Fi

---

# 9. Referenzen

Verwendete Dokumentation:

- **CAPTURE_TOOL_PLACEHOLDER Command Line User Guide**, Version 8.6.0
  - Installation der CLI
  - `capture_tool_placeholder start`
  - Transport- und Protokollparameter
  - `capture_tool_placeholder list`
  - Beenden mit `stop` oder `Ctrl + C`
- **CAPTURE_TOOL_PLACEHOLDER User Guide**, Version 8.8.0
  - Wireless-CaptureSessionPlaceholder-Konfiguration
  - Bluetooth- und Wi-Fi-Capture
  - MOBILE_DIAGNOSTIC_PROFILE_PLACEHOLDER Mode
  - Bluetooth Logging Profile
  - CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER
  - `rvictl`
