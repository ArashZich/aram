#!/usr/bin/env bash
# Builds every release artifact into dist/: signed APK, AAB, checksums.
set -euo pipefail
cd "$(dirname "$0")"
VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
rm -rf dist && mkdir -p dist

# The bundle and the per-CPU APKs cannot come from one build (AGP).
./gradlew clean bundleRelease
cp app/build/outputs/bundle/release/app-release.aab "dist/Aram-$VER.aab"
./gradlew assembleRelease -PabiSplits
for abi in universal arm64-v8a armeabi-v7a x86_64 x86; do
    cp "app/build/outputs/apk/release/app-$abi-release.apk" "dist/Aram-$VER-$abi.apk"
done

(cd dist && sha256sum * > SHA256SUMS.txt)
ls -la dist
