#!/bin/bash
ROOT="$(cd "$(dirname "$0")" && pwd)"
"$ROOT/cluster.sh" "$@"
result=$?
if [ "$result" -ne 0 ]; then read -r -p '启动失败，按回车关闭窗口。'; fi
exit "$result"
