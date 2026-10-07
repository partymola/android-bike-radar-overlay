#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 JJ del Rio
# Privacy-disclosure freshness gate.
#
# Asserts the user-facing privacy copy stays consistent with the code:
#   0. every Privacy paragraph, and the "not affiliated" title, has Spanish
#      copy;
#   1. every outbound MQTT flow registered in the DataDisclosure anchor
#      (HaClient.kt) is disclosed in the Privacy screen's Home Assistant
#      paragraph;
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
# True when a string would show the rider at least one letter. Entities and
# backslash escapes are dropped first, since every invisible form (spaces,
# no-break and zero-width spaces, quotes, newlines) is one of those or is not
# a letter.
has_text() {
    printf '%s' "$1" | sed -E 's/&#[0-9]+;|&#[xX][0-9a-fA-F]+;|&[a-zA-Z]+;//g; s/\\[uU][0-9a-fA-F]{4}//g; s/\\.//g' \
        | grep -q '[A-Za-z]'
}
PRIVACY_EN="$(privacy_copy "$STRINGS")"
PRIVACY_ES="$(privacy_copy "$STRINGS_ES")"
[ -n "$PRIVACY_EN" ] || { echo "BLOCKER: no settings_privacy_* strings parsed from $STRINGS"; exit 2; }
[ -n "$PRIVACY_ES" ] || { echo "BLOCKER: no settings_privacy_* strings parsed from $STRINGS_ES"; exit 2; }

# 0. Every Privacy paragraph shows some text in Spanish. Lint catches a missing
#    translation; an empty one, or one Android renders as nothing, passes lint
#    and shows the rider nothing (see has_text).
mapfile -t privacy_keys < <(grep -oE '<string name="settings_privacy_[^"]+"' "$STRINGS" | sed 's/.*name="//; s/"$//')
for k in "${privacy_keys[@]}"; do
    has_text "$(string_named "$STRINGS_ES" "$k")" \
        || blocker "Privacy paragraph '$k' is missing or empty in the Spanish copy ($STRINGS_ES)"
done
has_text "$(string_named "$STRINGS_ES" settings_about_unaffiliated_title)" \
    || blocker "the 'not affiliated' title is missing or empty in the Spanish copy ($STRINGS_ES)"

# 1. Anchor disclosure keywords must appear in the Home Assistant paragraph,
#    the one the anchor names. Several also appear in other paragraphs
#    ("battery", "close-pass", "summary"), which would otherwise stand in for it.
#    Pull the outbound listOf(...) block, take its quoted strings in order, and
#    keep every 3rd one - the disclosureKeyword of each
#    Flow(topicFamily, category, disclosureKeyword).
HA_PARA="$(string_named "$STRINGS" settings_privacy_to_ha_publish)"
[ -n "$HA_PARA" ] || blocker "settings_privacy_to_ha_publish missing from $STRINGS"
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
        has "$HA_PARA" "$kw" \
            || blocker "outbound flow '$kw' (DataDisclosure) is not disclosed in the Home Assistant paragraph (settings_privacy_to_ha_publish)"
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
# DISCLOSED SOMEWHERE, not that a specific paragraph agrees with it. Each
# paragraph about a SharedPreferences value must name the backup and must not
# use a stays-on-the-device phrase. The phrase lists catch the wordings seen so
# far, not every one: a paragraph that says it in other words passes, so read
# both locales side by side when one of these changes.
backup_paragraph() { # key, what it holds
    local en es
    en="$(string_named "$STRINGS" "$1")"
    es="$(string_named "$STRINGS_ES" "$1")"
    [ -n "$en" ] || { blocker "$1 missing from the Privacy copy ($STRINGS)"; return; }
    # Lower-cased so a phrase that opens a sentence still matches.
    case "${en,,}" in
        *"this phone only"*|*"never sent anywhere"*|*"never leaves"*|*"stay on your phone"*|*"stays on your phone"*|*"stay on this phone"*)
            blocker "the paragraph on $2 ($1) claims it stays on the device, but it is SharedPreferences and travels in the Android backup" ;;
    esac
    has "$en" "backup" \
        || blocker "the paragraph on $2 ($1) must say it is included in the Android backup"
    case "${es,,}" in
        *"solo en este"*|*"solo en tu"*|*"nunca se envía"*|*"nunca sale"*|*"se queda en tu"*|*"se quedan en tu"*)
            blocker "the Spanish paragraph on $2 ($1) claims it stays on the device" ;;
    esac
    has "$es" "copia de seguridad" \
        || blocker "the Spanish paragraph on $2 ($1) must say it is included in the Android backup"
}
backup_paragraph settings_privacy_on_phone_settings "your settings"
backup_paragraph settings_privacy_on_phone_creds "the Home Assistant URL and token"
# A rider's home coordinates are the worst value to be wrong about.
backup_paragraph settings_privacy_on_phone_location "the manual coordinates"
# The granted-app list, whenever the copy has a sharing paragraph (2c above
# requires one while the radar can be shared).
if [ -n "$(string_named "$STRINGS" settings_privacy_to_apps_body)" ]; then
    backup_paragraph settings_privacy_to_apps_body "the granted-app list"
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
