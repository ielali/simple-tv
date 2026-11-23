#!/usr/bin/env bash
# Simple TV admin over adb. Safe by design: packages are disabled, never uninstalled, and the stock
# Google TV home is never touched (disabling it can loop Android 14 Google TV into recovery).
#
#   TV_HOST=192.168.1.50 tools/tv-admin.sh <command>
#
# Commands
#   status              model, Simple TV build, guard state, stock home health
#   install <apk>       install/upgrade Simple TV, then guard-on
#   guard-on            grant self-repair permission, enable the home guard, open Simple TV
#   guard-off           disable the home guard and stop it re-enabling itself
#   stock               guard-off, then show the normal Google TV home
#   debloat <list>      disable every package in a list file (see tools/debloat/)
#   restore <list>      re-enable every package in a list file
#   packages            list installed packages (system and user) to build a debloat list
#
# Idea credit: tv-vibehack (MIT), github.com/toomramcharan/tv-vibehack.
set -euo pipefail

APP=com.ielali.simpletv
GUARD="$APP/$APP.guard.HomeGuardService"
STOCK_HOME=com.google.android.apps.tv.launcherx
CONFIG_PORT="${CONFIG_PORT:-8080}"
ADB="${ADB:-adb}"

# Never disabled by this script, whatever a list says.
PROTECTED_PACKAGES=(
  "$STOCK_HOME"
  com.google.android.tvlauncher
  com.google.android.tungsten.setupwraith
  com.android.tv.settings
  com.android.systemui
  com.android.providers.settings
  com.google.android.gms
  com.google.android.gsf
  com.android.vending
  com.google.android.youtube.tv
  android
  "$APP"
)

die() { echo "error: $*" >&2; exit 1; }

serial() {
  [ -n "${TV_HOST:-}" ] || die "set TV_HOST to the TV's IP address, e.g. TV_HOST=192.168.1.50 $0 status"
  echo "${TV_HOST}:${TV_PORT:-5555}"
}

tv() { "$ADB" -s "$(serial)" shell "$@" | tr -d '\r'; }

connect() { "$ADB" connect "$(serial)" >/dev/null 2>&1 || true; }

# pkg/.Cls and pkg/pkg.Cls name the same component (mirrors AccessibilityServiceList.kt).
normalise() {
  local e="$1"
  [[ "$e" == */* ]] || { echo "$e"; return; }
  local pkg="${e%%/*}" cls="${e#*/}"
  [[ "$cls" == .* ]] && cls="$pkg$cls"
  echo "$pkg/$cls"
}

services_without_guard() {
  local value="$1" out=() entry
  [ "$value" = "null" ] && value=""
  IFS=':' read -r -a entries <<< "$value"
  for entry in "${entries[@]}"; do
    [ -n "$entry" ] || continue
    [ "$(normalise "$entry")" = "$(normalise "$GUARD")" ] && continue
    out+=("$entry")
  done
  (IFS=':'; echo "${out[*]-}")
}

has_guard() {
  local entry value="$1"
  IFS=':' read -r -a entries <<< "$value"
  for entry in "${entries[@]}"; do
    [ "$(normalise "$entry")" = "$(normalise "$GUARD")" ] && return 0
  done
  return 1
}

is_protected() {
  local p
  for p in "${PROTECTED_PACKAGES[@]}"; do [ "$1" = "$p" ] && return 0; done
  return 1
}

read_list() {
  [ -f "$1" ] || die "list not found: $1"
  grep -vE '^\s*(#|$)' "$1" | sed -E 's/[[:space:]]+#.*$//; s/[[:space:]]+$//'
}

check_list() {
  local pkg bad=0
  while read -r pkg; do
    if is_protected "$pkg"; then echo "refusing: $pkg is protected and must stay enabled" >&2; bad=1; fi
  done < <(read_list "$1")
  [ "$bad" = 0 ] || die "remove protected packages from $1; nothing was changed"
}

# Search captured output, never `cmd | grep -q`: with pipefail an early grep exit makes the pipe fail.
contains_line() { grep -qxF -- "$2" <<< "$1"; }
contains_text() { grep -qF -- "$2" <<< "$1"; }

heal_stock_home() {
  local disabled; disabled="$(tv pm list packages -d)"
  if contains_line "$disabled" "package:$STOCK_HOME"; then
    echo "WARNING: stock Google TV home was disabled. Re-enabling it to prevent a boot loop."
    tv pm enable "$STOCK_HOME" >/dev/null
  fi
}

cmd_status() {
  connect
  echo "Device:        $(tv getprop ro.product.manufacturer) $(tv getprop ro.product.model), Android $(tv getprop ro.build.version.release)"
  local pkginfo; pkginfo="$(tv dumpsys package "$APP")"
  echo "Simple TV:     $(sed -n 's/^ *versionName=//p' <<< "$pkginfo" | sed -n 1p)"
  if has_guard "$(tv settings get secure enabled_accessibility_services)"; then
    echo "Home guard:    enabled"
  else
    echo "Home guard:    not enabled (run: $0 guard-on)"
  fi
  if contains_text "$pkginfo" "android.permission.WRITE_SECURE_SETTINGS: granted=true"; then
    echo "Self-repair:   allowed"
  else
    echo "Self-repair:   not granted"
  fi
  heal_stock_home
  echo "Stock home:    enabled"
  local windows; windows="$(tv dumpsys window)"
  echo "Focused:       $(grep -E 'mCurrentFocus' <<< "$windows" | sed -n '1s/^ *//p')"
}

cmd_guard_on() {
  connect
  heal_stock_home
  tv pm grant "$APP" android.permission.WRITE_SECURE_SETTINGS
  local current rest
  current="$(tv settings get secure enabled_accessibility_services)"
  rest="$(services_without_guard "$current")"
  tv settings put secure enabled_accessibility_services "${rest:+$rest:}$GUARD"
  tv settings put secure accessibility_enabled 1
  curl -fsS -m 5 -X PUT -H 'Content-Type: application/json' \
    --data '{"guardEnabled":true}' "http://${TV_HOST}:${CONFIG_PORT}/api/settings" >/dev/null 2>&1 || true
  tv am start -n "$APP/.MainActivity" >/dev/null
  echo "Home guard enabled. Simple TV is in front."
}

cmd_guard_off() {
  connect
  # Tell the app first so it does not re-enable the guard at the next boot; revoking the permission
  # makes sure even if the config server is unreachable.
  curl -fsS -m 5 -X PUT -H 'Content-Type: application/json' \
    --data '{"guardEnabled":false}' "http://${TV_HOST}:${CONFIG_PORT}/api/settings" >/dev/null 2>&1 || true
  tv pm revoke "$APP" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  local current rest
  current="$(tv settings get secure enabled_accessibility_services)"
  rest="$(services_without_guard "$current")"
  if [ -n "$rest" ]; then
    tv settings put secure enabled_accessibility_services "$rest"
  else
    tv settings delete secure enabled_accessibility_services >/dev/null
    tv settings put secure accessibility_enabled 0
  fi
  heal_stock_home
  echo "Home guard disabled."
}

cmd_stock() {
  cmd_guard_off
  tv input keyevent KEYCODE_HOME
  echo "Normal Google TV home is showing."
}

cmd_install() {
  local apk="${1:?usage: $0 install <simple-tv.apk>}"
  [ -f "$apk" ] || die "APK not found: $apk"
  connect
  "$ADB" -s "$(serial)" install -r "$apk"
  cmd_guard_on
}

cmd_debloat() {
  local list="${1:?usage: $0 debloat <list>}" pkg
  check_list "$list"
  connect
  while read -r pkg; do
    local result; result="$(tv pm disable-user --user 0 "$pkg" 2>&1 || true)"
    if contains_text "$result" "new state: disabled"; then
      echo "disabled  $pkg"
    else
      echo "skipped   $pkg (not installed or not allowed)"
    fi
  done < <(read_list "$list")
  heal_stock_home
}

cmd_restore() {
  local list="${1:?usage: $0 restore <list>}" pkg
  connect
  while read -r pkg; do
    tv pm enable "$pkg" >/dev/null 2>&1 && echo "enabled   $pkg" || echo "skipped   $pkg"
  done < <(read_list "$list")
  heal_stock_home
}

cmd_packages() {
  connect
  echo "# System packages"; tv pm list packages -s | sed 's/^package://' | sort
  echo; echo "# User-installed packages"; tv pm list packages -3 | sed 's/^package://' | sort
}

case "${1:-}" in
  status) cmd_status ;;
  install) shift; cmd_install "$@" ;;
  guard-on) cmd_guard_on ;;
  guard-off) cmd_guard_off ;;
  stock) cmd_stock ;;
  debloat) shift; cmd_debloat "$@" ;;
  restore) shift; cmd_restore "$@" ;;
  packages) cmd_packages ;;
  *) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
