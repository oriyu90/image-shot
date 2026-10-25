#!/bin/sh
# NFR-08 サイズ実測（07 計画書 PR-17・2026-10-25 確定）
# 使い方: benchmark/run_nfr08_sizes.sh <bundletool.jar>
# 検証済み版: bundletool 1.18.3
#   https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
#   SHA-256: a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
# 前提: 端末が adb 接続済み（get-device-spec に使用） / 署名鍵は debug keystore
set -e
JAR="${1:?使い方: run_nfr08_sizes.sh <bundletool.jar>}"
cd "$(dirname "$0")/.."
SPEC=benchmark/fixtures/device_tb710fu.json
WORK=$(mktemp -d)
AAB=app/build/outputs/bundle/release/app-release.aab

./gradlew :app:bundleRelease --offline --console=plain
java -jar "$JAR" get-device-spec --output="$SPEC"
java -jar "$JAR" build-apks --bundle=$AAB --output="$WORK/all.apks"
java -jar "$JAR" build-apks --bundle=$AAB --device-spec=$SPEC --output="$WORK/dev.apks"
java -jar "$JAR" build-apks --bundle=$AAB --mode=universal --output="$WORK/universal.apks"

echo "== all dims (SDK,ABI,SCREEN_DENSITY,LANGUAGE) =="
java -jar "$JAR" get-size total --apks="$WORK/all.apks" --dimensions=SDK,ABI,SCREEN_DENSITY,LANGUAGE
echo "== device-spec =="
java -jar "$JAR" get-size total --apks="$WORK/dev.apks"
echo "== universal =="
java -jar "$JAR" get-size total --apks="$WORK/universal.apks"
echo "WORKDIR: $WORK (未コミット・測定後に削除可)"
