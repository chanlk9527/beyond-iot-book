#!/bin/bash
ROOT="$(cd "$(dirname "$0")" && pwd)"
"$ROOT/cluster.sh" stop "$@"
result=$?
if [ "$result" -ne 0 ]; then read -r -p '停止失败，按回车关闭窗口。'; fi
exit "$result"
