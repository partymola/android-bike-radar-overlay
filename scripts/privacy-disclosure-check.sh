#!/usr/bin/env bash
# Privacy-disclosure freshness gate.
#
# Asserts the user-facing privacy copy stays consistent with the code:
#   1. every outbound MQTT flow registered in the DataDisclosure anchor
#      (HaClient.kt) is disclosed on the Settings -> Privacy screen;
#   2. every user-facing manifest permission is named on the Privacy screen;
#   3. the core posture claims (AES-256/Keystore, HTTPS, "Not affiliated")
#      still appear where their backing code lives.
#
# The companion unit test (HaClientDataDisclosureTest) proves the inverse for
# (1): that HaClient does not publish a topic family missing from the anchor.
# Together they force a new outbound flow to update both the anchor and the
# disclosure. Exits non-zero on any BLOCKER.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

HACLIENT="app/src/main/java/es/jjrh/bikeradar/HaClient.kt"
PRIVACY="app/src/main/java/es/jjrh/bikeradar/ui/SettingsPrivacy.kt"
ABOUT="app/src/main/java/es/jjrh/bikeradar/ui/SettingsAbout.kt"
MANIFEST="app/src/main/AndroidManifest.xml"

# Install-time / capability permissions that carry no data and need no
# user-facing disclosure. Everything else must be named on the Privacy screen.
PERMISSION_ALLOWLIST="INTERNET RECEIVE_BOOT_COMPLETED VIBRATE \
FOREGROUND_SERVICE_CONNECTED_DEVICE FOREGROUND_SERVICE_SHORT_SERVICE \
FOREGROUND_SERVICE_MEDIA_PROJECTION"

fail=0
blocker() { echo "BLOCKER: $*"; fail=1; }

for f in "$HACLIENT" "$PRIVACY" "$ABOUT" "$MANIFEST"; do
    [ -f "$f" ] || { echo "BLOCKER: missing file $f"; exit 2; }
done

# 1. Anchor disclosure keywords must appear on the Privacy screen.
#    Pull the outbound listOf(...) block, take its quoted strings in order, and
#    keep every 3rd one - the disclosureKeyword of each
#    Flow(topicFamily, category, disclosureKeyword).
keywords="$(
    awk '/val outbound/{f=1} f{print} f && /^    \)/{exit}' "$HACLIENT" \
        | grep -oE '"[^"]*"' \
        | sed 's/^"//; s/"$//' \
        | awk 'NR % 3 == 0'
)"
if [ -z "$keywords" ]; then
    blocker "could not parse DataDisclosure.outbound keywords from $HACLIENT"
else
    mapfile -t kw_arr <<<"$keywords"
    for kw in "${kw_arr[@]}"; do
        [ -z "$kw" ] && continue
        grep -qF "$kw" "$PRIVACY" \
            || blocker "outbound flow '$kw' (DataDisclosure) is not disclosed in SettingsPrivacy.kt"
    done
fi

# 2. Every user-facing manifest permission must be named on the Privacy screen.
perms="$(grep -oE 'android\.permission\.[A-Z_]+' "$MANIFEST" \
    | sed 's/android\.permission\.//' | sort -u)"
mapfile -t perm_arr <<<"$perms"
for p in "${perm_arr[@]}"; do
    case " $PERMISSION_ALLOWLIST " in
        *" $p "*) continue ;;
    esac
    grep -qF "$p" "$PRIVACY" \
        || blocker "manifest permission '$p' is not named in SettingsPrivacy.kt"
done

# 3. Posture claims must still appear where their backing code lives.
grep -qF "AES-256" "$PRIVACY" || blocker "encryption claim 'AES-256' missing from SettingsPrivacy.kt"
grep -qF "Keystore" "$PRIVACY" || blocker "encryption claim 'Keystore' missing from SettingsPrivacy.kt"
grep -qF "HTTPS" "$PRIVACY" || blocker "network claim 'HTTPS' missing from SettingsPrivacy.kt"
grep -qF "Not affiliated" "$ABOUT" || blocker "'Not affiliated' disclaimer missing from SettingsAbout.kt"

if [ "$fail" -ne 0 ]; then
    echo "privacy-disclosure-check: FAIL"
    exit 1
fi
echo "privacy-disclosure-check: PASS"
