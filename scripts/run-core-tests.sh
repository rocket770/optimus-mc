#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .test-classes

suites=()
sources=(src/main/java/dev/optimus/client/ClientSettings.java
  src/main/java/dev/optimus/client/CombatTrace.java)
while IFS= read -r -d '' source; do sources+=("$source"); done < <(find src/main/java/dev/optimus/core -name '*.java' -print0)
while IFS= read -r suite || [[ -n "$suite" ]]; do
  suite="${suite%$'\r'}"
  [[ -z "$suite" || "$suite" == \#* ]] && continue
  suites+=("$suite")
  sources+=("src/test/java/${suite//.//}.java")
done < scripts/core-test-suites.txt

javac -source 8 -target 8 -d .test-classes "${sources[@]}"
for suite in "${suites[@]}"; do
  java -cp '.test-classes:src/test/resources' "$suite"
done