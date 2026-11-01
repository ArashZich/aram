#!/usr/bin/env bash
# Builds every release artifact into dist/: signed APK, AAB, checksums.
set -euo pipefail
cd "$(dirname "$0")"
VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
rm -rf dist && mkdir -p dist

./gradlew clean assembleRelease bundleRelease
cp app/build/outputs/apk/release/app-release.apk "dist/Hush-$VER.apk"
cp app/build/outputs/bundle/release/app-release.aab "dist/Hush-$VER.aab"

(cd dist && sha256sum * > SHA256SUMS.txt)
ls -la dist
