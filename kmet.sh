#!/bin/sh
# kmet — start kmet from this checkout on Babashka, in the caller's directory.
#
# Link it from ~/bin and run it from anywhere:
#
#   ln -s /path/to/kmet-agent/kmet.sh ~/bin/kmet
#   cd ~/src/some-project && kmet
#
# bb normally reads bb.edn from the current directory; --config points the run
# at this checkout's bb.edn while the process stays in the caller's directory,
# so kmet's tools, sessions, and project settings use the caller's cwd.
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

command -v bb >/dev/null 2>&1 || {
  echo "kmet: bb is not on PATH" >&2
  exit 1
}

exec bb --config "$checkout/bb.edn" start "$@"
