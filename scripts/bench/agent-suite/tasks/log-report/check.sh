#!/bin/sh
set -eu
test -f report.txt
test "$(cat report.txt)" = "$(printf 'ok=14\ntop=/api/users')"
