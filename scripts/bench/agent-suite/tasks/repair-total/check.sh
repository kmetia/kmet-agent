#!/bin/sh
set -eu
bb -cp src -e '(require (quote [calc :as calc])) (assert (= 15 (calc/total [1 2 3 4 5]))) (assert (= 0 (calc/total []))) (assert (= 3 (calc/mean [1 2 3 4 5])))'
