#!/usr/bin/env bash
#
# Build the debug APK and install it on a connected device, verifying by SHA-256
# what was on the device before and what is on it after.
#
# Motivation: neither Gradle's UP-TO-DATE nor adb's "Success" tells you what
# build the phone was actually running. versionName is static in this project,
# and lastUpdateTime only reflects the install you just did. The APK is copied
# to /data/app verbatim, so hashing it is the one reliable check.
#
# Usage:
#   tools/install-debug.sh [-s SERIAL] [--no-build] [--launch]
#
# Device selection: -s SERIAL, else $ANDROID_SERIAL, else the only connected
# device. Wireless adb works too (the OTG port is often occupied by the dongle).

set -euo pipefail

PKG="nl.jwdr.ooc"
APK="app/build/outputs/apk/debug/app-debug.apk"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SERIAL="${ANDROID_SERIAL:-}"
DO_BUILD=1
DO_LAUNCH=0
while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    --launch) DO_LAUNCH=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# --- locate adb ---------------------------------------------------------------
if command -v adb >/dev/null 2>&1; then
  ADB=adb
else
  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then ADB="$candidate"; break; fi
  done
fi
[ -n "${ADB:-}" ] || { echo "adb not found (set ANDROID_HOME or put adb on PATH)" >&2; exit 1; }

# --- pick the target device ---------------------------------------------------
if [ -z "$SERIAL" ]; then
  # bash 3.2 (macOS system bash) has no mapfile, so keep this newline-delimited.
  device_list="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  device_count="$(printf '%s' "$device_list" | grep -c . || true)"
  if [ "$device_count" -eq 0 ]; then
    echo "no device connected (adb devices shows none in state 'device')" >&2; exit 1
  elif [ "$device_count" -gt 1 ]; then
    echo "several devices connected; pick one with -s SERIAL:" >&2
    printf '%s\n' "$device_list" | sed 's/^/  /' >&2; exit 1
  fi
  SERIAL="$(printf '%s\n' "$device_list" | head -1)"
fi
DEVICE_NAME="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
echo "device: $SERIAL ($DEVICE_NAME)"

# --- build --------------------------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  echo "building :app:assembleDebug ..."
  ./gradlew :app:assembleDebug
fi
[ -f "$APK" ] || { echo "APK not found at $APK" >&2; exit 1; }

sha_local() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1 || sha256sum "$1" | cut -d' ' -f1; }

# Hash of the APK currently installed on the device; empty if not installed.
sha_device() {
  local paths base
  paths="$("$ADB" -s "$SERIAL" shell "pm path $PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://')" || true
  [ -n "$paths" ] || return 0
  if [ "$(printf '%s\n' "$paths" | wc -l)" -gt 1 ]; then
    echo "note: package has split APKs; hashing base.apk only" >&2
  fi
  base="$(printf '%s\n' "$paths" | grep 'base\.apk$' | head -1)"
  [ -n "$base" ] || return 0
  "$ADB" -s "$SERIAL" shell "sha256sum '$base'" | tr -d '\r' | cut -d' ' -f1
}

LOCAL_SHA="$(sha_local "$APK")"
BEFORE_SHA="$(sha_device)"
HEAD_DESC="$(git rev-parse --short HEAD 2>/dev/null || echo '?')"
git diff --quiet 2>/dev/null || HEAD_DESC="$HEAD_DESC-dirty"

echo "local APK:  $LOCAL_SHA  (source: $HEAD_DESC)"
if [ -z "$BEFORE_SHA" ]; then
  echo "on device:  not installed"
elif [ "$BEFORE_SHA" = "$LOCAL_SHA" ]; then
  echo "on device:  $BEFORE_SHA  -> already this exact build; install is a no-op"
else
  echo "on device:  $BEFORE_SHA  -> different build, will be replaced"
fi

# --- install ------------------------------------------------------------------
# --user 0: Secure Folder (user 150) rejects shell installs with an empty error.
echo "installing ..."
"$ADB" -s "$SERIAL" install -r --user 0 "$APK"

# --- verify -------------------------------------------------------------------
AFTER_SHA="$(sha_device)"
if [ "$AFTER_SHA" != "$LOCAL_SHA" ]; then
  echo "FAILED: device reports $AFTER_SHA after install, expected $LOCAL_SHA" >&2
  exit 1
fi
echo "verified:   device now runs $AFTER_SHA ($HEAD_DESC)"

if [ "$DO_LAUNCH" -eq 1 ]; then
  "$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "launched:   $PKG"
fi
