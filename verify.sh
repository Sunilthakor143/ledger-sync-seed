#!/usr/bin/env bash
# Compiles and runs the pipeline verification against fixtures/corpus-a.jsonl.
set -euo pipefail
cd "$(dirname "$0")"

GRADLE_CMD="gradle"
if ! command -v gradle &> /dev/null; then
    if [ -x "./gradlew" ]; then
        GRADLE_CMD="./gradlew"
    else
        echo "Warning: gradle command not found in PATH. Skipping Gradle unit test step."
        GRADLE_CMD=""
    fi
fi

if [ -n "$GRADLE_CMD" ]; then
    echo "==> Running Unit Tests via Gradle"
    $GRADLE_CMD test
fi

echo
echo "==> Running baseline SelfCheck verification"
rm -rf build/selfcheck && mkdir -p build/selfcheck
javac -d build/selfcheck $(find src/main/java -name '*.java')
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"

echo
echo "==> Phase 4 pipeline verification successfully completed!"
