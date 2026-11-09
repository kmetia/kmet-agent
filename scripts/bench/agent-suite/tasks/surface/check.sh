#!/bin/sh
set -eu
test -s "$KMET_BENCH_STDOUT"
grep -qx 'READY' "$KMET_BENCH_STDOUT"
