#!/usr/bin/env bash
# XRay99 — 1-Command Bundled Xray-core & GeoData Updater
# Downloads the latest official XTLS/Xray-core Android binaries into app/src/main/jniLibs/<abi>/libxray.so
# so Android 10–15 devices can execute Xray-core directly from nativeLibraryDir (W^X compliant).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

REPO="${XRAY_REPO:-XTLS/Xray-core}"
echo "==> Fetching latest release metadata from github.com/$REPO ..."
RELEASE_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases/latest")"
TAG="$(echo "$RELEASE_JSON" | jq -r '.tag_name')"
echo "==> Latest Xray-core release: $TAG"

fetch_abi() {
  local asset_name="$1"
  local android_abi="$2"
  local url
  url="$(echo "$RELEASE_JSON" | jq -r --arg name "$asset_name" '.assets[] | select(.name == $name) | .browser_download_url')"
  if [[ -z "$url" || "$url" == "null" ]]; then
    echo "WARN: Asset $asset_name not found in $TAG, skipping $android_abi"
    return
  fi
  echo "==> Downloading $asset_name -> $android_abi/libxray.so ..."
  curl -fL --progress-bar "$url" -o "$TMP_DIR/$asset_name"
  mkdir -p "$TMP_DIR/extract_$android_abi" "$JNI_DIR/$android_abi" "$ASSETS_DIR"
  unzip -qo "$TMP_DIR/$asset_name" -d "$TMP_DIR/extract_$android_abi"
  cp "$TMP_DIR/extract_$android_abi/xray" "$JNI_DIR/$android_abi/libxray.so"
  chmod 755 "$JNI_DIR/$android_abi/libxray.so"

  if [[ -f "$TMP_DIR/extract_$android_abi/geoip.dat" ]]; then
    cp "$TMP_DIR/extract_$android_abi/geoip.dat" "$ASSETS_DIR/geoip.dat"
  fi
  if [[ -f "$TMP_DIR/extract_$android_abi/geosite.dat" ]]; then
    cp "$TMP_DIR/extract_$android_abi/geosite.dat" "$ASSETS_DIR/geosite.dat"
  fi
}

fetch_abi "Xray-android-arm64-v8a.zip" "arm64-v8a"
fetch_abi "Xray-android-arm32-v7a.zip" "armeabi-v7a"

echo "$TAG" > "$ASSETS_DIR/core_version.txt"
echo "==> Done! Bundled Xray-core $TAG into $JNI_DIR and GeoData into $ASSETS_DIR."
echo "==> Rebuild APK with: gradle assembleRelease"
