#!/bin/sh
set -eu

binary="$HOME/Library/Application Support/TraceMate/bin/tracemate-agent"
label="gui/$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER"

launchctl print "$label"
"$binary" health
"$binary" jobs
