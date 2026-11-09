#!/bin/sh
# kmetj — start kmet from this checkout on Jolt, in the caller's directory.
#
# Link it from ~/bin and run it from anywhere:
#
#   ln -s /path/to/kmet-agent/kmetj.sh ~/bin/kmetj
#   cd ~/src/some-project && kmetj
#
# jolt resolves deps.edn/bb.edn against JOLT_PWD and derives System user.dir
# from the same value, so running `jolt start` here would make kmet work in this
# checkout. Point JOLT_PWD at the checkout for resolution, then restore user.dir
# to the caller's directory before the project's own `start` task mounts kmet.
set -eu

# Follow symlinks (readlink -f is not POSIX and older macOS lacks it).
target=$0
while [ -L "$target" ]; do
  link=$(readlink "$target")
  case $link in
    /*) target=$link ;;
    *) target=$(CDPATH= cd -- "$(dirname -- "$target")" && pwd)/$link ;;
  esac
done
checkout=$(CDPATH= cd -- "$(dirname -- "$target")" && pwd)
caller=$(pwd)

command -v jolt >/dev/null 2>&1 || {
  echo "kmetj: jolt is not on PATH" >&2
  exit 1
}

# The injected task sets user.dir from the environment and runs the project's
# `start` task, so the entry point stays the one `jolt start` uses. Keeping the
# caller's path in KMETJ_CWD avoids embedding it in the EDN.
exec env \
  JOLT_PWD="$checkout" \
  KMETJ_CWD="$caller" \
  jolt -Sdeps '{:tasks {kmetj-start {:private true :task (do (System/setProperty "user.dir" (System/getenv "KMETJ_CWD")) (run (quote start)))}}}' \
  kmetj-start "$@"
