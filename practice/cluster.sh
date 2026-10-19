#!/bin/bash
# macOS three-service lifecycle; local configuration is parsed as data.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
for prefix in /opt/homebrew /usr/local; do
  if [ -x "$prefix/opt/openjdk@21/bin/java" ]; then
    export JAVA_HOME="$prefix/opt/openjdk@21"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done
export PATH="$HOME/.docker/bin:$PATH"
exec python3 "$ROOT/scripts/cluster.py" "$@"
