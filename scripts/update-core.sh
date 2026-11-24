#!/usr/bin/env bash
# V2Vex — 1-Command Bundled Dual-Core (Xray-core + Sing-box), hev-socks5-tunnel & GeoData Updater
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets"
LIBS_DIR="$ROOT_DIR/app/libs"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# 1. XTLS/Xray-core + GeoIP/GeoSite
REPO="${XRAY_REPO:-XTLS/Xray-core}"
echo "==> Fetching latest release metadata from github.com/$REPO ..."
RELEASE_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases/latest")"
TAG="$(echo "$RELEASE_JSON" | jq -r '.tag_name')"
echo "==> Latest Xray-core release: $TAG"

fetch_xray_abi() {
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
  mkdir -p "$TMP_DIR/extract_xray_$android_abi" "$JNI_DIR/$android_abi" "$ASSETS_DIR"
  unzip -qo "$TMP_DIR/$asset_name" -d "$TMP_DIR/extract_xray_$android_abi"
  cp "$TMP_DIR/extract_xray_$android_abi/xray" "$JNI_DIR/$android_abi/libxray.so"
  chmod 755 "$JNI_DIR/$android_abi/libxray.so"

  if [[ -f "$TMP_DIR/extract_xray_$android_abi/geoip.dat" ]]; then
    cp "$TMP_DIR/extract_xray_$android_abi/geoip.dat" "$ASSETS_DIR/geoip.dat"
  fi
  if [[ -f "$TMP_DIR/extract_xray_$android_abi/geosite.dat" ]]; then
    cp "$TMP_DIR/extract_xray_$android_abi/geosite.dat" "$ASSETS_DIR/geosite.dat"
  fi
}

fetch_xray_abi "Xray-android-arm64-v8a.zip" "arm64-v8a"
fetch_xray_abi "Xray-android-arm32-v7a.zip" "armeabi-v7a"

# 2. SagerNet/sing-box (Bundled Android W^X-compliant libsingbox.so)
SB_REPO="${SINGBOX_REPO:-SagerNet/sing-box}"
echo "==> Fetching latest release metadata from github.com/$SB_REPO ..."
SB_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$SB_REPO/releases/latest")"
SB_TAG="$(echo "$SB_JSON" | jq -r '.tag_name')"
SB_VER="${SB_TAG#v}"
echo "==> Latest Sing-box release: $SB_TAG"

fetch_singbox_abi() {
  local asset_name="$1"
  local android_abi="$2"
  local url
  url="$(echo "$SB_JSON" | jq -r --arg name "$asset_name" '.assets[] | select(.name == $name) | .browser_download_url')"
  if [[ -z "$url" || "$url" == "null" ]]; then
    echo "WARN: Sing-box asset $asset_name not found in $SB_TAG, skipping $android_abi"
    return
  fi
  echo "==> Downloading $asset_name -> $android_abi/libsingbox.so ..."
  curl -fL --progress-bar "$url" -o "$TMP_DIR/$asset_name"
  mkdir -p "$TMP_DIR/extract_sb_$android_abi" "$JNI_DIR/$android_abi"
  tar -xzf "$TMP_DIR/$asset_name" -C "$TMP_DIR/extract_sb_$android_abi"
  local sb_bin
  sb_bin="$(find "$TMP_DIR/extract_sb_$android_abi" -type f -name 'sing-box' | head -n 1)"
  if [[ -n "$sb_bin" && -f "$sb_bin" ]]; then
    cp "$sb_bin" "$JNI_DIR/$android_abi/libsingbox.so"
    chmod 755 "$JNI_DIR/$android_abi/libsingbox.so"
    echo "==> Bundled Sing-box $SB_TAG -> $android_abi/libsingbox.so"
  else
    echo "WARN: sing-box binary not found inside $asset_name"
  fi
}

fetch_singbox_abi "sing-box-${SB_VER}-android-arm64.tar.gz" "arm64-v8a"
fetch_singbox_abi "sing-box-${SB_VER}-android-arm.tar.gz" "armeabi-v7a"

# 3. heiher/hev-socks5-tunnel native C L3 TUN AAR
echo "==> Fetching latest hev-socks5-tunnel.aar ..."
HEV_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/heiher/hev-socks5-tunnel/releases/latest")"
HEV_TAG="$(echo "$HEV_JSON" | jq -r '.tag_name')"
HEV_URL="$(echo "$HEV_JSON" | jq -r '.assets[] | select(.name == "hev-socks5-tunnel.aar") | .browser_download_url')"
mkdir -p "$LIBS_DIR"
curl -fL --progress-bar "$HEV_URL" -o "$LIBS_DIR/hev-socks5-tunnel.aar"
echo "==> Bundled hev-socks5-tunnel $HEV_TAG -> $LIBS_DIR/hev-socks5-tunnel.aar"

echo "$TAG" > "$ASSETS_DIR/core_version.txt"
echo "$SB_TAG" > "$ASSETS_DIR/singbox_version.txt"
echo "==> Done! Xray-core $TAG + Sing-box $SB_TAG + hev-socks5-tunnel $HEV_TAG ready."
