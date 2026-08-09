#!/usr/bin/env bash
# Privacy-disclosure freshness gate.
#
# Asserts the user-facing privacy copy stays consistent with the code:
#   1. every outbound MQTT flow registered in the DataDisclosure anchor
#      (HaClient.kt) is disclosed in the Settings -> Privacy copy;
#   2. every user-facing manifest permission is named in that copy;
#   3. the core posture claims (backup transfer, HTTPS, "Not affiliated")
#      still appear in the user-facing copy and match the manifest's
#      backup configuration.
#
# The disclosure copy is externalised for i18n: the Settings -> Privacy and
# About strings live in res/values/strings.xml (the .kt screens only hold
# stringResource references), so the keyword search targets the strings file,
# not the Composable source. The DataDisclosure anchor itself stays in
# HaClient.kt (it is code, not copy).
#
# The companion unit test (HaClientDataDisclosureTest) proves the inverse for
# (1): that HaClient does not publish a topic family missing from the anchor.
# Together they force a new outbound flow to update both the anchor and the
# disclosure. Exits non-zero on any BLOCKER.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

HACLIENT="app/src/main/java/es/jjrh/bikeradar/HaClient.kt"
# User-facing privacy + about copy, externalised to the default string resources.
STRINGS="app/src/main/res/values/strings.xml"
MANIFEST="app/src/main/AndroidManifest.xml"
RULES="app/src/main/res/xml/data_extraction_rules.xml"

# Install-time / capability permissions that carry no data and need no
# user-facing disclosure. Everything else must be named in the Privacy copy.
PERMISSION_ALLOWLIST="INTERNET RECEIVE_BOOT_COMPLETED VIBRATE \
FOREGROUND_SERVICE_CONNECTED_DEVICE FOREGROUND_SERVICE_SHORT_SERVICE \
FOREGROUND_SERVICE_MEDIA_PROJECTION"

fail=0
blocker() { echo "BLOCKER: $*"; fail=1; }

for f in "$HACLIENT" "$STRINGS" "$MANIFEST" "$RULES"; do
    [ -f "$f" ] || { echo "BLOCKER: missing file $f"; exit 2; }
done

# 1. Anchor disclosure keywords must appear in the Privacy copy.
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
        grep -qF "$kw" "$STRINGS" \
            || blocker "outbound flow '$kw' (DataDisclosure) is not disclosed in the Privacy copy (strings.xml)"
    done
fi

# 2. Every user-facing manifest permission must be named in the Privacy copy.
perms="$(grep -oE 'android\.permission\.[A-Z_]+' "$MANIFEST" \
    | sed 's/android\.permission\.//' | sort -u)"
if [ -z "$perms" ]; then
    # Without this the loop below runs once with an empty pattern, and
    # `grep -qF ""` matches every line, so every permission check passes.
    blocker "could not parse any permission from $MANIFEST"
fi
mapfile -t perm_arr <<<"$perms"
for p in "${perm_arr[@]}"; do
    case " $PERMISSION_ALLOWLIST " in
        *" $p "*) continue ;;
    esac
    grep -qF "$p" "$STRINGS" \
        || blocker "manifest permission '$p' is not named in the Privacy copy (strings.xml)"
done

# 3. Posture claims must still appear in the user-facing copy, and the
#    backup claim must match the manifest: credentials-in-backup is a
#    deliberate, disclosed posture (settings + HA creds transfer to a new
#    phone), so the copy and allowBackup must flip together, never alone.
grep -qF "backup" "$STRINGS" || blocker "backup-transfer disclosure missing from the Privacy copy (strings.xml)"
if grep -qF "backup" "$STRINGS" && ! grep -qF 'android:allowBackup="true"' "$MANIFEST"; then
    blocker "Privacy copy discloses backup transfer but the manifest disables backup"
fi
if grep -qF 'android:allowBackup="true"' "$MANIFEST" && ! grep -qF 'android:dataExtractionRules=' "$MANIFEST"; then
    blocker "allowBackup is on without dataExtractionRules - backup scope must be explicit"
fi
# The "stays on your phone" claims for ride history / capture logs / crash
# reports / link journal depend on the external-storage excludes (Auto Backup
# includes getExternalFilesDir() by DEFAULT), and the "encrypted with your
# screen lock" claim depends on refusing un-encryptable cloud backups.
if [ "$(grep -cF '<exclude' "$RULES")" -ne 2 ] || ! grep -qF 'domain="external"' "$RULES"; then
    blocker "external storage must be excluded from BOTH cloud backup and device transfer (data_extraction_rules.xml)"
fi
grep -qF 'disableIfNoEncryptionCapabilities="true"' "$RULES" \
    || blocker "cloud backup must refuse devices without a lock-screen secret (the screen-lock encryption claim depends on it)"
grep -qF "HTTPS" "$STRINGS" || blocker "network claim 'HTTPS' missing from the Privacy copy (strings.xml)"
grep -qF "Not affiliated" "$STRINGS" || blocker "'Not affiliated' disclaimer missing from the About copy (strings.xml)"

if [ "$fail" -ne 0 ]; then
    echo "privacy-disclosure-check: FAIL"
    exit 1
fi
echo "privacy-disclosure-check: PASS"
