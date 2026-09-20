#!/data/data/com.termux/files/usr/bin/bash
# Typing-CPU measurement for perf.md §7/§9.3: start the app in a tmux pty
# (100x30), settle, measure an idle CPU window, then the same-length window
# while sending N keys at IVAL seconds apart. Net CPU = typing - idle,
# in /proc/<pid>/stat ticks (fields 14+15, user+sys; 100 Hz on this device).
#
#   scripts/perf_typing.sh <label> <command-string> [nkeys] [interval-s] [settle-s]
#
# Notes: the answer is the median of several runs, a fresh session each time;
# never run anything else while measuring (§6.2b). Settle 30 s by default:
# jolt can burn a full core for ~20 s at startup with the current extension
# set (§11.4), and a short settle measures that burn as typing.
set -u
LABEL=$1
CMD=$2
NKEYS=${3:-300}
IVAL=${4:-0.02}
SETTLE=${5:-30}
SESS="pt_$LABEL"
DUR=$(awk -v n="$NKEYS" -v i="$IVAL" 'BEGIN{printf "%.2f", n*i}')

tmux kill-session -t "$SESS" 2>/dev/null || true
tmux new-session -d -s "$SESS" -x 100 -y 30 -c "$PWD" "exec $CMD"
sleep 2
PID=$(tmux list-panes -t "$SESS" -F '#{pane_pid}')
sleep "$SETTLE"
ticks() { awk '{print $14+$15}' "/proc/$PID/stat"; }
u0=$(ticks); sleep "$DUR"; u1=$(ticks)
idle=$((u1-u0))
u2=$(ticks)
for _ in $(seq "$NKEYS"); do
  tmux send-keys -t "$SESS" "a" >/dev/null
  sleep "$IVAL"
done
u3=$(ticks)
typ=$((u3-u2))
printf '%-6s keys=%-4s idle=%-4s typing=%-5s net=%-5s\n' "$LABEL" "$NKEYS" "$idle" "$typ" "$((typ-idle))"
tmux kill-session -t "$SESS" 2>/dev/null || true
