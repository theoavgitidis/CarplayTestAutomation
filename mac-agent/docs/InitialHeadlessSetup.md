# Initial Headless Mac Setup

> **Current operational guide:** Use [../../docs/operations.md](../../docs/operations.md) together with [../../docs/mac-agent.md](../../docs/mac-agent.md). This detailed setup checklist is retained for macOS operator decisions and was reconciled with source on 2026-09-23.

Use this guide once for each Mac and dedicated CAPTURE_TOOL_PLACEHOLDER macOS user. The setup requires
temporary local access to the Mac for macOS, CAPTURE_TOOL_PLACEHOLDER, and MobileDevicePlaceholder trust prompts.

The agent is a per-user LaunchAgent, not a system daemon. It works without a
monitor only while its dedicated user has an active graphical login session.

## 1. Create The CAPTURE_TOOL_PLACEHOLDER User

1. Open **System Settings > Users & Groups**.
2. Add a standard user dedicated to CAPTURE_TOOL_PLACEHOLDER, for example `tracemate`.
3. Set a strong password.
4. Log in as that user before completing the remaining setup.

Do not run the agent as `root` or with `sudo`. The CAPTURE_TOOL_PLACEHOLDER user owns the agent,
its captures, and the macOS permissions CAPTURE_TOOL_PLACEHOLDER requires.

## 2. Configure Automatic Login

1. Open **System Settings > Users & Groups**.
2. Set **Automatically log in as** to the dedicated CAPTURE_TOOL_PLACEHOLDER user.
3. Authenticate when macOS requests it.

Automatic login allows the LaunchAgent to start after an unattended reboot.
macOS normally disables this option while FileVault is enabled.

Choose one operating mode:

- **Unattended reboot:** Disable FileVault, then enable automatic login. This
  weakens protection if the Mac is stolen.
- **FileVault retained:** Keep FileVault enabled. After every full reboot or
  power-off, an operator must unlock the disk and log in locally once. The
  agent can then operate headlessly until the next reboot.

Disconnecting the monitor is supported. A monitorless Mac is different from a
Mac with no logged-in user.

## 3. Enable Restricted SSH Access

1. Open **System Settings > General > Sharing**.
2. Enable **Remote Login**.
3. Under **Allow access for**, select **Only these users**.
4. Add only the dedicated CAPTURE_TOOL_PLACEHOLDER user.
5. Configure the Mac Ethernet interface for DHCP. Connect it to Android
   tethering and confirm it receives an address. Its address can change when
   tethering restarts, so do not rely on the current value for normal use.

In the Android app, enter the SSH settings under **Settings > Mac LAN SSH**:

| Setting | Value |
| --- | --- |
| Mac LAN IP | Optional recovery/debug IPv4 address |
| SSH port | `22` unless changed on the Mac |
| SSH user | Dedicated CAPTURE_TOOL_PLACEHOLDER user, for example `tracemate` |
| SSH password | Password for that user |

After the TraceMate agent is installed, Android discovers the Mac over the
Ethernet tether using its Bonjour service `MAC_AGENT_DISCOVERY_FQDN_PLACEHOLDER` and verifies
the advertised identity endpoint before using SSH. The optional manual IP is
only a recovery fallback. Android uses the Ethernet-only SSH connection for
Mac-agent status and start commands. It does not expose the agent's loopback
RPC port to the network.

## 4. Install CAPTURE_TOOL_PLACEHOLDER And Prepare The MobileDevicePlaceholder

While logged in locally as the dedicated CAPTURE_TOOL_PLACEHOLDER user:

1. Install Apple CAPTURE_TOOL_PLACEHOLDER and its CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER. The default CAPTURE_TOOL_PLACEHOLDER executable
   must be available at `CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER`.
2. Connect the MobileDevicePlaceholder by USB.
3. Unlock the MobileDevicePlaceholder and accept **Trust This Computer**.
4. Enter the dedicated Mac user's password if requested by macOS.
5. Install all required diagnostic and logging profiles on the MobileDevicePlaceholder.
6. Resolve every macOS, CAPTURE_TOOL_PLACEHOLDER, and MobileDevicePlaceholder permission prompt.
7. Verify CAPTURE_TOOL_PLACEHOLDER can see the phone:

   ```bash
   CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER list --transport=wifi
   CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER list --transport=bluetooth
   ```

Do not defer permission prompts until the Mac is headless. They need local
interaction and may otherwise block capture startup.

## 5. Build And Install The Agent

Run these commands as the dedicated CAPTURE_TOOL_PLACEHOLDER user from the `mac-agent` directory:

```bash
swift build -c release
mkdir -p "$HOME/Library/Application Support/TraceMate/bin"
cp ".build/release/tracemate-agent" "$HOME/Library/Application Support/TraceMate/bin/tracemate-agent"
chmod 700 "$HOME/Library/Application Support/TraceMate/bin/tracemate-agent"
```

The installed executable path must remain exactly:

```text
~/Library/Application Support/TraceMate/bin/tracemate-agent
```

The supplied LaunchAgent and Android app both use that path. Reinstall the
binary at that location after upgrading it.

## 6. Install The LaunchAgent

Still as the dedicated CAPTURE_TOOL_PLACEHOLDER user, from the `mac-agent` directory:

```bash
mkdir -p "$HOME/Library/LaunchAgents"
cp deployment/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist "$HOME/Library/LaunchAgents/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist"
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist"
launchctl kickstart -k "gui/$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER"
```

Verify that launchd owns the service and that the CLI reaches its loopback RPC
server:

```bash
launchctl print "gui/$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER"
"$HOME/Library/Application Support/TraceMate/bin/tracemate-agent" health
"$HOME/Library/Application Support/TraceMate/bin/tracemate-agent" jobs
```

`health` must return JSON containing `"ok": true`.

The plist uses `RunAtLoad` and `KeepAlive`. It starts the service after the
dedicated user logs in and restarts it if it exits. Never start the service
with `tracemate-agent serve &` through SSH.

The same LaunchAgent publishes the Bonjour discovery service. Confirm it after
installation with:

```bash
dns-sd -B MAC_AGENT_DISCOVERY_SERVICE_PLACEHOLDER local.
```

## 7. Configure Power And Login Behavior

Keep the Mac connected to stable AC power. Disable automatic logout in
**System Settings > Lock Screen**. The display may sleep; the user session and
system must not sleep.

Set the AC power profile:

```bash
sudo pmset -c sleep 0
sudo pmset -c disksleep 0
sudo pmset -c displaysleep 10
pmset -g custom
```

Confirm the **AC Power** section includes:

```text
sleep              0
displaysleep       10
```

These values are expected and safe for this setup:

| Setting | Expected value | Meaning |
| --- | --- | --- |
| `sleep` | `0` | System sleep is disabled on AC power. |
| `displaysleep` | `10` | Display may turn off after ten minutes. |
| `ttyskeepawake` | `1` | Fine, but not relied on by the LaunchAgent. |
| `powernap` | `1` | Fine, but does not keep normal processes awake. |
| `womp` | `1` | Fine; permits wake-on-network where supported. |
| `tcpkeepalive` | `1` | Fine; helps retain TCP connectivity. |
| `power button sleep` | `1` | Do not press the power button during capture work. |

The current agent starts a `caffeinate -i -m` child while an CAPTURE_TOOL_PLACEHOLDER capture is active when `/usr/bin/caffeinate` is available. The AC `sleep 0` setting remains an operational choice for unattended readiness between captures; validate local power policy before deployment.

## 8. Verify From Android

1. Enable debug mode in TraceMate Android.
2. Enable Android Ethernet tethering and connect the Mac.
3. Keep Android connected to the headunit hotspot over Wi-Fi.
4. Open the debug section on the Home screen.
5. Run **Mac SSH Test** to verify Android can reach the Mac via Ethernet.
6. Under **Mac Agent**, tap **Check Agent**.

The app reports the agent's `health` JSON and persisted `jobs` JSON.

If the service is unavailable, tap **Start Agent**. This is an explicit user
action. Android first checks agent health; only if it fails does it invoke:

```bash
launchctl kickstart -k gui/$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER
```

It then fetches health and jobs again. This action does not start an CAPTURE_TOOL_PLACEHOLDER
capture and does not affect any headunit operation.

## 9. Reboot Test

Before leaving the Mac headless, test the complete recovery path:

```bash
sudo shutdown -r now
```

After the Mac restarts:

1. Confirm the dedicated CAPTURE_TOOL_PLACEHOLDER user has auto-logged in. This cannot happen
   unattended with FileVault enabled.
2. Connect Android Ethernet tethering.
3. Use **Check Agent** in Android.
4. If needed, use **Start Agent** and verify the health/jobs report.
5. Confirm the Mac remains usable after disconnecting its monitor and after
   display sleep.

## Ongoing Operation

The macOS user, SSH configuration, LaunchAgent installation, CAPTURE_TOOL_PLACEHOLDER permissions,
and Android Mac-LAN settings are one-time setup items. Before a capture
session, use **Check Agent**. Use **Start Agent** only if the status check
fails.

Repeat the LaunchAgent installation only when changing its plist, changing
the dedicated macOS user, or removing the service. Reinstall the agent binary
after replacing or upgrading the executable.
