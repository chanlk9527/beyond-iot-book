#!/bin/bash
# macOS entry point; configuration is parsed as data by manage.py.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
for prefix in /opt/homebrew /usr/local; do
  if [ -x "$prefix/opt/openjdk@21/bin/java" ]; then
    export JAVA_HOME="$prefix/opt/openjdk@21"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done
for prefix in /opt/homebrew /usr/local; do
  if [ -x "$prefix/opt/node@24/bin/node" ]; then
    export PATH="$prefix/opt/node@24/bin:$PATH"
    break
  fi
done
exec python3 "$ROOT/scripts/manage.py" "$@"
