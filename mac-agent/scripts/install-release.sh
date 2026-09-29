#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
binary="$HOME/Library/Application Support/TraceMate/bin/tracemate-agent"
plist="$HOME/Library/LaunchAgents/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist"
label="gui/$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER"

cd "$root"
swift build -c release
mkdir -p "$(dirname "$binary")" "$(dirname "$plist")"
install -m 700 ".build/release/tracemate-agent" "$binary"
install -m 600 "deployment/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist" "$plist"
launchctl bootout "$label" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$plist"
launchctl kickstart -k "$label"
"$binary" health
