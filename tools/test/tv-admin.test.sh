#!/usr/bin/env bash
# Runs tools/tv-admin.sh against a fake adb and checks the commands it would send.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
LOG="$WORK/adb.log"; STATE="$WORK/services"; DISABLED="$WORK/disabled"
cat > "$WORK/adb" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$LOG"
case "\$*" in
  *"settings get secure enabled_accessibility_services"*) cat "$STATE" ;;
  *"pm list packages -d"*) cat "$DISABLED"; seq 1 20000 | sed 's/^/package:filler.pkg/' ;;
  *"pm disable-user"*) echo "Package \${@: -1} new state: disabled-user" ;;
esac
exit 0
STUB
chmod +x "$WORK/adb"
export ADB="$WORK/adb" TV_HOST=10.0.0.9 CONFIG_PORT=1
fail() { echo "FAIL: $*"; echo "--- adb log"; cat "$LOG"; exit 1; }
GUARD=com.ielali.simpletv/com.ielali.simpletv.guard.HomeGuardService

# guard-on keeps other services and appends ours once
echo "com.other/.Svc" > "$STATE"; : > "$DISABLED"; : > "$LOG"
"$ROOT/tools/tv-admin.sh" guard-on >/dev/null
grep -q "pm grant com.ielali.simpletv android.permission.WRITE_SECURE_SETTINGS" "$LOG" || fail "grant missing"
grep -q "settings put secure enabled_accessibility_services com.other/.Svc:$GUARD" "$LOG" || fail "guard-on list"

# guard-on is idempotent when the short form is already present
echo "com.ielali.simpletv/.guard.HomeGuardService" > "$STATE"; : > "$LOG"
"$ROOT/tools/tv-admin.sh" guard-on >/dev/null
grep -q "settings put secure enabled_accessibility_services $GUARD\$" "$LOG" || fail "guard-on idempotent"

# guard-off removes only ours and revokes self-repair
echo "com.other/.Svc:$GUARD" > "$STATE"; : > "$LOG"
"$ROOT/tools/tv-admin.sh" guard-off >/dev/null
grep -q "pm revoke com.ielali.simpletv" "$LOG" || fail "revoke missing"
grep -q "settings put secure enabled_accessibility_services com.other/.Svc\$" "$LOG" || fail "guard-off kept others"

# guard-off with only ours deletes the setting and turns accessibility off
echo "$GUARD" > "$STATE"; : > "$LOG"
"$ROOT/tools/tv-admin.sh" guard-off >/dev/null
grep -q "settings delete secure enabled_accessibility_services" "$LOG" || fail "guard-off delete"
grep -q "settings put secure accessibility_enabled 0" "$LOG" || fail "guard-off a11y 0"

# a disabled stock home is re-enabled, even with a long package list after it (pipefail regression)
echo "package:com.google.android.apps.tv.launcherx" > "$DISABLED"; : > "$LOG"
"$ROOT/tools/tv-admin.sh" guard-off >/dev/null
grep -q "pm enable com.google.android.apps.tv.launcherx" "$LOG" || fail "heal stock home"

# debloat refuses a list containing the stock home and changes nothing
: > "$DISABLED"; : > "$LOG"
printf 'com.netflix.ninja\ncom.google.android.apps.tv.launcherx  # bad\n' > "$WORK/bad.txt"
if "$ROOT/tools/tv-admin.sh" debloat "$WORK/bad.txt" >/dev/null 2>&1; then fail "protected list accepted"; fi
grep -c "pm disable-user" "$LOG" >/dev/null && fail "disabled something despite refusal"

# debloat of the shipped list disables each entry
: > "$LOG"
"$ROOT/tools/tv-admin.sh" debloat "$ROOT/tools/debloat/google-tv-dongle.txt" >/dev/null
[ "$(grep -c 'pm disable-user --user 0' "$LOG")" = "7" ] || fail "expected 7 disables"
grep "disable-user" "$LOG" | grep -c launcherx >/dev/null && fail "disabled stock home"

# status reports the guard whichever name form is stored
echo "com.other/.Svc:com.ielali.simpletv/.guard.HomeGuardService" > "$STATE"; : > "$DISABLED"
out="$("$ROOT/tools/tv-admin.sh" status)"; grep -qF "Home guard:    enabled" <<< "$out" || fail "status should see guard"
echo "com.other/.Svc" > "$STATE"
out="$("$ROOT/tools/tv-admin.sh" status)"; grep -qF "Home guard:    not enabled" <<< "$out" || fail "status should not see guard"

echo "tv-admin.sh: all checks passed"
