#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 JJ del Rio
# Privacy-disclosure freshness gate.
#
# Asserts the user-facing privacy copy stays consistent with the code:
#   0. every Privacy paragraph has Spanish copy;
#   1. every outbound MQTT flow registered in the DataDisclosure anchor
#      (HaClient.kt) is disclosed in the Settings -> Privacy copy;
#   2. every user-facing manifest permission is named in that copy;
#   3. the core posture claims (backup transfer, HTTPS, "Not affiliated")
#      still appear in the user-facing copy and match the manifest's
#      backup configuration.
#
# The disclosure copy is externalised for i18n, so the search reads the
# `settings_privacy_*` strings of each locale, not the Composable source and not
# the whole strings file: a keyword in a toast or an onboarding line says
# nothing about what the Privacy screen discloses. The Spanish copy is checked
# for every paragraph, the literal tokens (permission names, HTTPS) and the
# backup statements; the MQTT keywords are English words, so they are checked in
# English only. The DataDisclosure anchor itself stays in HaClient.kt (it is
# code, not copy).
#
# The companion unit test (HaClientDataDisclosureTest) proves the inverse for
# (1): that HaClient does not publish a topic family missing from the anchor.
# Together they force a new outbound flow to update both the anchor and the
# disclosure. Exits non-zero on any BLOCKER.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

HACLIENT="app/src/main/java/es/jjrh/bikeradar/HaClient.kt"
STRINGS="app/src/main/res/values/strings.xml"
STRINGS_ES="app/src/main/res/values-es/strings.xml"
MANIFEST="app/src/main/AndroidManifest.xml"
RULES="app/src/main/res/xml/data_extraction_rules.xml"

# Install-time / capability permissions that carry no data and need no
# user-facing disclosure. Everything else must be named in the Privacy copy.
PERMISSION_ALLOWLIST="INTERNET RECEIVE_BOOT_COMPLETED VIBRATE \
FOREGROUND_SERVICE_CONNECTED_DEVICE FOREGROUND_SERVICE_SHORT_SERVICE \
FOREGROUND_SERVICE_MEDIA_PROJECTION"

fail=0
blocker() { echo "BLOCKER: $*"; fail=1; }

for f in "$HACLIENT" "$STRINGS" "$STRINGS_ES" "$MANIFEST" "$RULES"; do
    [ -f "$f" ] || { echo "BLOCKER: missing file $f"; exit 2; }
done

privacy_copy() { sed -n 's/.*<string name="settings_privacy_[^"]*">\(.*\)<\/string>.*/\1/p' "$1"; }
string_named() { sed -n "s/.*<string name=\"$2\">\(.*\)<\/string>.*/\1/p" "$1"; }
has() { printf '%s' "$1" | grep -qF -- "$2"; }
PRIVACY_EN="$(privacy_copy "$STRINGS")"
PRIVACY_ES="$(privacy_copy "$STRINGS_ES")"
[ -n "$PRIVACY_EN" ] || { echo "BLOCKER: no settings_privacy_* strings parsed from $STRINGS"; exit 2; }
[ -n "$PRIVACY_ES" ] || { echo "BLOCKER: no settings_privacy_* strings parsed from $STRINGS_ES"; exit 2; }

# 0. Every Privacy paragraph is non-empty in Spanish. Lint catches a missing
#    translation; an empty one passes lint and shows the rider nothing. So does
#    one holding only spaces, quotes or a no-break space, all of which Android
#    renders as nothing.
mapfile -t privacy_keys < <(grep -oE '<string name="settings_privacy_[^"]+"' "$STRINGS" | sed 's/.*name="//; s/"$//')
for k in "${privacy_keys[@]}"; do
    [ -n "$(string_named "$STRINGS_ES" "$k" | sed 's/&#160;//g; s/&#[xX][aA]0;//g; s/\\[uU]00[aA]0//g; s/\xc2\xa0//g; s/"//g' | tr -d '[:space:]')" ] \
        || blocker "Privacy paragraph '$k' is missing or empty in the Spanish copy ($STRINGS_ES)"
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
        has "$PRIVACY_EN" "$kw" \
            || blocker "outbound flow '$kw' (DataDisclosure) is not disclosed in the Privacy copy (settings_privacy_*)"
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
    has "$PRIVACY_EN" "$p" \
        || blocker "manifest permission '$p' is not named in the Privacy copy (settings_privacy_*)"
    has "$PRIVACY_ES" "$p" \
        || blocker "manifest permission '$p' is not named in the Spanish Privacy copy"
done

# 2b. Permissions this app DECLARES, not only ones it uses. A custom permission
#     is how another app is let at the radar, and it is not in the
#     android.permission namespace, so check 2 above cannot see it at all.
#     Inert until one is declared, which is the point: it fires on the commit
#     that adds one.
# `<uses-permission` does not contain the substring `<permission`, so no guard
# against it is needed and one keyed on trailing whitespace missed the wrapped
# multi-line form the manifest actually uses.
declared="$(awk '/<permission/,/\/>/' "$MANIFEST" \
    | grep -oE 'android:name="[^"]+"' | sed 's/android:name="//; s/"//')"
if [ -n "$declared" ]; then
    mapfile -t decl_arr <<<"$declared"
    for d in "${decl_arr[@]}"; do
        [ -z "$d" ] && continue
        # By name, so adding a second permission cannot ride in on the first
        # one's disclosure.
        has "$PRIVACY_EN" "$d" \
            || blocker "this app declares '$d', which lets another app in, and the Privacy copy never names it"
        has "$PRIVACY_ES" "$d" \
            || blocker "this app declares '$d', which lets another app in, and the Spanish Privacy copy never names it"
    done
fi

# 2c. A cross-app data path is a second outbound channel. The anchor in
#     HaClient models MQTT only, so nothing above can see this one.
#
#     Scoped to the paragraph, not the file: two other paragraphs already
#     contain "never leaves the phone", so a file-wide grep matched one of
#     those and passed with this section deleted.
if [ -f "app/src/main/java/es/jjrh/bikeradar/access/RadarAccess.kt" ]; then
    apps_para=$(sed -n 's/.*<string name="settings_privacy_to_apps_body">\(.*\)<\/string>.*/\1/p' "$STRINGS")
    if [ -z "$apps_para" ]; then
        blocker "the radar can be shared with other apps but the Privacy copy has no sharing paragraph"
    else
        for kw in "not over the network" "up to that app" "Apps allowed to use your radar" "say yes"; do
            printf '%s' "$apps_para" | grep -qF "$kw" \
                || blocker "the sharing paragraph is missing '$kw'"
        done
    fi
fi

# 3. Posture claims must still appear in the user-facing copy, and the
#    backup claim must match the manifest: credentials-in-backup is a
#    deliberate, disclosed posture (settings + HA creds transfer to a new
#    phone), so the copy and allowBackup must flip together, never alone.
has "$PRIVACY_EN" "backup" || blocker "backup-transfer disclosure missing from the Privacy copy (settings_privacy_*)"
has "$PRIVACY_ES" "copia de seguridad" || blocker "backup-transfer disclosure missing from the Spanish Privacy copy"
if has "$PRIVACY_EN" "backup" && ! grep -qF 'android:allowBackup="true"' "$MANIFEST"; then
    blocker "Privacy copy discloses backup transfer but the manifest disables backup"
fi
if grep -qF 'android:allowBackup="true"' "$MANIFEST" && ! grep -qF 'android:dataExtractionRules=' "$MANIFEST"; then
    blocker "allowBackup is on without dataExtractionRules - backup scope must be explicit"
fi
# The "stays on your phone" claims for ride history / capture logs / crash
# reports / link journal / screenshots depend on the external-storage excludes
# (Auto Backup includes getExternalFilesDir() by DEFAULT), and the "encrypted
# with your screen lock" claim depends on refusing un-encryptable cloud backups.
if [ "$(grep -cF '<exclude' "$RULES")" -ne 2 ] || ! grep -qF 'domain="external"' "$RULES"; then
    blocker "external storage must be excluded from BOTH cloud backup and device transfer (data_extraction_rules.xml)"
fi
grep -qF 'disableIfNoEncryptionCapabilities="true"' "$RULES" \
    || blocker "cloud backup must refuse devices without a lock-screen secret (the screen-lock encryption claim depends on it)"
# Anything stored in SharedPreferences rides the backup, so a paragraph about
# one of those values may not claim it stays on the device. This exists because
# the manual-coordinate paragraph said "on this phone only ... never sent
# anywhere" while every check above passed: they prove the backup posture is
# DISCLOSED SOMEWHERE, not that a specific paragraph agrees with it. A rider's
# home coordinates are the worst value to be wrong about.
loc=$(sed -n 's/.*<string name="settings_privacy_on_phone_location">\(.*\)<\/string>.*/\1/p' "$STRINGS")
[ -n "$loc" ] || blocker "settings_privacy_on_phone_location missing from the Privacy copy (strings.xml)"
case "$loc" in
    *"this phone only"*|*"never sent anywhere"*|*"never leaves"*)
        blocker "the manual-coordinate paragraph claims the coordinates stay on the device, but they are SharedPreferences and travel in the Android backup" ;;
esac
printf '%s' "$loc" | grep -qF "backup" \
    || blocker "the manual-coordinate paragraph must say the coordinates are included in the Android backup"
loc_es=$(string_named "$STRINGS_ES" settings_privacy_on_phone_location)
case "$loc_es" in
    *"solo en este"*|*"nunca se envían"*|*"nunca sale"*)
        blocker "the Spanish manual-coordinate paragraph claims the coordinates stay on the device" ;;
esac
has "$loc_es" "copia de seguridad" \
    || blocker "the Spanish manual-coordinate paragraph must say the coordinates are included in the Android backup"

# The granted-app list is SharedPreferences, so it rides the backup exactly as
# the manual coordinates do. Same failure, same shape: a paragraph that says it
# stays on the device would be false.
apps=$(sed -n 's/.*<string name="settings_privacy_to_apps_body">\(.*\)<\/string>.*/\1/p' "$STRINGS")
if [ -n "$apps" ]; then
    case "$apps" in
        *"this phone only"*|*"never sent anywhere"*|*"never leaves this phone"*)
            blocker "the sharing paragraph claims the granted-app list stays on the device, but it is SharedPreferences and travels in the Android backup" ;;
    esac
    printf '%s' "$apps" | grep -qF "backup" \
        || blocker "the sharing paragraph must say the granted-app list is included in the Android backup"
    apps_es=$(string_named "$STRINGS_ES" settings_privacy_to_apps_body)
    case "$apps_es" in
        *"solo en este"*|*"nunca se envía"*|*"nunca sale de este"*)
            blocker "the Spanish sharing paragraph claims the granted-app list stays on the device" ;;
    esac
    has "$apps_es" "copia de seguridad" \
        || blocker "the Spanish sharing paragraph must say the granted-app list is included in the Android backup"
fi

has "$PRIVACY_EN" "HTTPS" || blocker "network claim 'HTTPS' missing from the Privacy copy (settings_privacy_*)"
has "$PRIVACY_ES" "HTTPS" || blocker "network claim 'HTTPS' missing from the Spanish Privacy copy"
has "$(string_named "$STRINGS" settings_about_unaffiliated_title)" "Not affiliated" \
    || blocker "'Not affiliated' disclaimer missing from the About copy (settings_about_unaffiliated_title)"

if [ "$fail" -ne 0 ]; then
    echo "privacy-disclosure-check: FAIL"
    exit 1
fi
echo "privacy-disclosure-check: PASS"
