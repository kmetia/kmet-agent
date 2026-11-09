#!/bin/sh
set -eu
if grep -RIn --exclude-dir=.git 'old-name' .; then
  echo 'old-name still appears' >&2
  exit 1
fi
bb test
bb -cp src -e '(require (quote [app.core :as core])) (assert (= "a, b" (core/run ["a" "b"])))'
