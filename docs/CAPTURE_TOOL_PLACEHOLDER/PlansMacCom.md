# Android-Mac Communication Options

> **Deprecated/stale.** This is a historical architecture exploration. The implemented design is Ethernet-bound Android SSH/SCP, a loopback-only Mac-agent RPC service reached through the installed CLI, and mDNS identity discovery. See [Architecture](../architecture.md) and [Mac agent](../mac-agent.md).

## 1. SSH

### Ziel

Android steuert den Mac per SSH und führt CAPTURE_TOOL_PLACEHOLDER-Kommandos aus.

### Aktueller Stand

- SSH-Verbindung zwischen Android und Mac funktioniert.
- CAPTURE_TOOL_PLACEHOLDER kann manuell per Terminal gestartet werden.
- Ziel ist die vollständige Automatisierung über die Android-App.

### Bekannte Probleme

#### Netzwerk

- Kommunikation funktioniert nur, wenn Android den Mac im selben Netzwerk erreichen kann.
- Im Fahrzeugnetz wurde Client-Isolation bzw. fehlende Kommunikation zwischen WLAN-Clients beobachtet.

#### Offene Fragen

- Ist die Client-Isolation durch Broadcom-Firmware oder die Bridge-Konfiguration verursacht?
- Können WLAN-Clients dauerhaft miteinander kommunizieren?

### Bereits untersucht

- Linux Bridge (Hairpin Mode)
- Bridge-Konfiguration
- Routing
- Firewall (nftables)
- ARP/Ping-Verhalten
- Broadcom Access Point als mögliche Ursache

### Noch zu untersuchen

- Broadcom AP Isolation (`wl ap_isolate`)
- Paketmitschnitt (tcpdump)
- Bridge-/ebtables-Regeln
- Proprietäre Broadcom-Dienste
- Dauerhafte Lösung ohne manuelle Eingriffe

---

## 2. HTTP

### Grundidee

Android kommuniziert nicht über direkte Shell-Kommandos, sondern über eine HTTP-API auf dem Mac. Der HTTP-Server startet intern die CAPTURE_TOOL_PLACEHOLDER-CLI.

Beispiel-Endpunkte:

| Methode | Endpunkt |
|---|---|
| `POST` | `/capture/start` |
| `POST` | `/capture/stop` |
| `GET` | `/health` |
| `GET` | `/devices` |

### Vorteile

- Klare, versionierbare API
- Android-Seite kennt keine CAPTURE_TOOL_PLACEHOLDER-internen Kommandos
- CAPTURE_TOOL_PLACEHOLDER-Änderungen betreffen ausschließlich den Mac-seitigen Server
- Einfach testbar mit curl oder Postman
- Gute Erweiterbarkeit

### Implementierung

1. HTTP-Server auf dem Mac (z. B. Flask)
2. CAPTURE_TOOL_PLACEHOLDER-Kommandos intern ausführen
3. JSON-Antworten liefern
4. Android ruft REST-Endpunkte auf

---

## 3. Netzwerk-Szenarien

### Fall A — Mac über Fahrzeug-WLAN

```text
Android ──┐
          ├── Fahrzeug-Hotspot ── Mac
MobileDevicePlaceholder  ──┘
```

**Vorteile**
- Einfachste Architektur
- Kein zusätzliches Routing erforderlich

**Risiken**
- Client-Isolation kann Kommunikation verhindern
- DHCP-IP des Mac kann wechseln

---

### Fall B — Mac über Ethernet

```text
Android ──── Fahrzeug-WLAN
Mac     ──── Ethernet
```

**Vorteile**
- Stabile, kabelgebundene Verbindung

**Voraussetzungen**
- Routing oder Bridging zwischen WLAN und Ethernet-Segment

**Risiken**
- Unterschiedliche Subnetze erfordern explizite Routing-Konfiguration
- mDNS/Bonjour funktioniert nicht automatisch über Router-Grenzen

---

## 4. Bekannte Fehlerquellen

- WLAN-Clients dürfen nicht miteinander kommunizieren (Client-Isolation)
- DHCP-IP des Mac ändert sich zwischen Sitzungen
- HTTP-Server auf dem Mac läuft nicht oder ist nicht erreichbar
- CAPTURE_TOOL_PLACEHOLDER-Prozess hängt oder terminiert unerwartet
- Firewall blockiert eingehende Verbindungen
- Netzwerkwechsel während eines laufenden Captures
- Gleichzeitige Start-Anfragen führen zu Konflikten
- CAPTURE_TOOL_PLACEHOLDER-CLI-Änderungen in einer neuen CAPTURE_TOOL_PLACEHOLDER-Version

---

## 5. Bonjour (Service Discovery)

### Zweck

Automatische Erkennung des Mac im lokalen Netzwerk ohne feste IP-Konfiguration.

Der Mac veröffentlicht einen mDNS-Dienst:

```text
_capture_tool_placeholder_controller._tcp.local
```

Android findet den Dienst und erhält automatisch IP-Adresse und Port.

### Vorteile

- Keine statische IP-Konfiguration notwendig
- Funktioniert bei dynamischer DHCP-Zuweisung

### Einschränkungen

- Nur für Service-Discovery, kein Ersatz für das eigentliche Kommunikationsprotokoll
- Setzt funktionierendes mDNS im Netzwerk voraus
- Funktioniert nicht ohne Weiteres über Router- oder Subnetzgrenzen

---

## 6. BLE

### Idee

Android steuert den Mac ausschließlich über Bluetooth Low Energy, unabhängig von der WLAN-Infrastruktur.

### Vorteile

- Keine gemeinsame WLAN-Infrastruktur notwendig
- Keine IP-Adresse erforderlich
- Unabhängig von Client-Isolation
- Funktioniert auch ohne Fahrzeug-Hotspot

### Einschränkungen

**macOS**
- Eigener BLE-GATT-Server erforderlich (Swift oder Python)
- Hintergrundbetrieb muss explizit berücksichtigt werden

**Android**
- BLE-Verbindungsmanagement inklusive Wiederverbindungslogik
- Geringere Datenrate im Vergleich zu WLAN

**Allgemein**
- Deutlich höherer Entwicklungsaufwand
- Debugging aufwendiger
- Eigene Protokolldefinition erforderlich

---

## 7. Vergleich HTTP vs. BLE

| Kriterium | HTTP | BLE |
|---|---|---|
| Implementierungsaufwand | Niedrig | Hoch |
| Performance | Hoch | Niedrig |
| Wartbarkeit | Hoch | Mittel |
| Benötigt IP-Adresse | Ja | Nein |
| Benötigt gemeinsames Netzwerk | Ja | Nein |
| Debugging | Einfach | Aufwendiger |
| Erweiterbarkeit | Sehr gut | Mittel |
