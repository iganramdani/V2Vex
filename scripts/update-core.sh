#!/usr/bin/env bash
# V2Vex — Reproducible Dual-Core (Xray-core + Sing-box), hev-socks5-tunnel & GeoData Updater
# By default, installs exact pinned versions + SHA-256 verified artifacts from scripts/core-versions.env.
# Pass `--latest` to query upstream GitHub releases/latest (for manual/weekly update checks).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSIONS_FILE="$ROOT_DIR/scripts/core-versions.env"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets"
LIBS_DIR="$ROOT_DIR/app/libs"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

USE_LATEST=false
if [[ "${1:-}" == "--latest" ]]; then
  USE_LATEST=true
fi

if [[ -f "$VERSIONS_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$VERSIONS_FILE"
fi

verify_sha256() {
  local file="$1"
  local expected="${2:-}"
  if [[ -z "$expected" ]]; then
    return 0
  fi
  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: SHA-256 mismatch for $(basename "$file")!" >&2
    echo "  Expected: $expected" >&2
    echo "  Actual:   $actual" >&2
    exit 1
  fi
  echo "==> SHA-256 verified OK: $(basename "$file")"
}

# 1. XTLS/Xray-core + GeoIP/GeoSite
REPO="${XRAY_REPO:-XTLS/Xray-core}"
if [[ "$USE_LATEST" == "true" || -z "${XRAY_VERSION:-}" ]]; then
  echo "==> Fetching latest release metadata from github.com/$REPO ..."
  RELEASE_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases/latest")"
  TAG="$(echo "$RELEASE_JSON" | jq -r '.tag_name')"
  XRAY_ARM64_SHA256=""
  XRAY_ARMV7_SHA256=""
else
  TAG="$XRAY_VERSION"
  echo "==> Using pinned Xray-core release: $TAG"
fi

fetch_xray_abi() {
  local asset_name="$1"
  local android_abi="$2"
  local expected_sha="${3:-}"
  local url="https://github.com/$REPO/releases/download/$TAG/$asset_name"

  if [[ "$USE_LATEST" == "true" ]]; then
    url="$(echo "$RELEASE_JSON" | jq -r --arg name "$asset_name" '.assets[] | select(.name == $name) | .browser_download_url')"
    if [[ -z "$url" || "$url" == "null" ]]; then
      echo "WARN: Asset $asset_name not found in $TAG, skipping $android_abi"
      return
    fi
  fi

  echo "==> Downloading $asset_name -> $android_abi/libxray.so ..."
  if ! curl -fsSL "$url" -o "$TMP_DIR/$asset_name"; then
    echo "WARN: Asset $asset_name unavailable in $TAG, skipping $android_abi"
    return
  fi
  verify_sha256 "$TMP_DIR/$asset_name" "$expected_sha"

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

fetch_xray_abi "${XRAY_ARM64_ASSET:-Xray-android-arm64-v8a.zip}" "arm64-v8a" "${XRAY_ARM64_SHA256:-}"
if [[ -n "${XRAY_ARMV7_SHA256:-}" || "$USE_LATEST" == "true" ]]; then
  fetch_xray_abi "${XRAY_ARMV7_ASSET:-Xray-android-arm32-v7a.zip}" "armeabi-v7a" "${XRAY_ARMV7_SHA256:-}"
else
  echo "==> Skipping Xray armeabi-v7a (not published in $TAG; Sing-box provides armeabi-v7a core)"
fi

# 2. SagerNet/sing-box (Bundled Android W^X-compliant libsingbox.so)
SB_REPO="${SINGBOX_REPO:-SagerNet/sing-box}"
if [[ "$USE_LATEST" == "true" || -z "${SINGBOX_VERSION:-}" ]]; then
  echo "==> Fetching latest release metadata from github.com/$SB_REPO ..."
  SB_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$SB_REPO/releases/latest")"
  SB_TAG="$(echo "$SB_JSON" | jq -r '.tag_name')"
  SINGBOX_ARM64_SHA256=""
  SINGBOX_ARMV7_SHA256=""
else
  SB_TAG="$SINGBOX_VERSION"
  echo "==> Using pinned Sing-box release: $SB_TAG"
fi
SB_VER="${SB_TAG#v}"

fetch_singbox_abi() {
  local asset_name="$1"
  local android_abi="$2"
  local expected_sha="${3:-}"
  local url="https://github.com/$SB_REPO/releases/download/$SB_TAG/$asset_name"

  if [[ "$USE_LATEST" == "true" ]]; then
    url="$(echo "$SB_JSON" | jq -r --arg name "$asset_name" '.assets[] | select(.name == $name) | .browser_download_url')"
    if [[ -z "$url" || "$url" == "null" ]]; then
      echo "WARN: Sing-box asset $asset_name not found in $SB_TAG, skipping $android_abi"
      return
    fi
  fi

  echo "==> Downloading $asset_name -> $android_abi/libsingbox.so ..."
  curl -fsSL "$url" -o "$TMP_DIR/$asset_name"
  verify_sha256 "$TMP_DIR/$asset_name" "$expected_sha"

  mkdir -p "$TMP_DIR/extract_sb_$android_abi" "$JNI_DIR/$android_abi"
  tar -xzf "$TMP_DIR/$asset_name" -C "$TMP_DIR/extract_sb_$android_abi"
  local sb_bin
  sb_bin="$(find "$TMP_DIR/extract_sb_$android_abi" -type f -name 'sing-box' | head -n 1)"
  if [[ -n "$sb_bin" && -f "$sb_bin" ]]; then
    cp "$sb_bin" "$JNI_DIR/$android_abi/libsingbox.so"
    chmod 755 "$JNI_DIR/$android_abi/libsingbox.so"
    echo "==> Bundled Sing-box $SB_TAG -> $android_abi/libsingbox.so"
  else
    echo "ERROR: sing-box binary not found inside $asset_name" >&2
    exit 1
  fi
}

fetch_singbox_abi "sing-box-${SB_VER}-android-arm64.tar.gz" "arm64-v8a" "${SINGBOX_ARM64_SHA256:-}"
fetch_singbox_abi "sing-box-${SB_VER}-android-arm.tar.gz" "armeabi-v7a" "${SINGBOX_ARMV7_SHA256:-}"

# 3. heiher/hev-socks5-tunnel native C L3 TUN AAR
HEV_REPO_EFF="${HEV_REPO:-heiher/hev-socks5-tunnel}"
if [[ "$USE_LATEST" == "true" || -z "${HEV_VERSION:-}" ]]; then
  echo "==> Fetching latest hev-socks5-tunnel.aar ..."
  HEV_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$HEV_REPO_EFF/releases/latest")"
  HEV_TAG="$(echo "$HEV_JSON" | jq -r '.tag_name')"
  HEV_URL="$(echo "$HEV_JSON" | jq -r '.assets[] | select(.name == "hev-socks5-tunnel.aar") | .browser_download_url')"
  HEV_AAR_SHA256=""
else
  HEV_TAG="$HEV_VERSION"
  HEV_URL="https://github.com/$HEV_REPO_EFF/releases/download/$HEV_TAG/hev-socks5-tunnel.aar"
  echo "==> Using pinned hev-socks5-tunnel release: $HEV_TAG"
fi

mkdir -p "$LIBS_DIR" "$ASSETS_DIR"
curl -fsSL "$HEV_URL" -o "$LIBS_DIR/hev-socks5-tunnel.aar"
verify_sha256 "$LIBS_DIR/hev-socks5-tunnel.aar" "${HEV_AAR_SHA256:-}"
echo "==> Bundled hev-socks5-tunnel $HEV_TAG -> $LIBS_DIR/hev-socks5-tunnel.aar"

echo "$TAG" > "$ASSETS_DIR/core_version.txt"
echo "$SB_TAG" > "$ASSETS_DIR/singbox_version.txt"
echo "==> Done! Xray-core $TAG + Sing-box $SB_TAG + hev-socks5-tunnel $HEV_TAG ready."
