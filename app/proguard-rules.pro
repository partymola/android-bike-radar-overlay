# R8 rules for the release build.
#
# Conservative first pass: shrink only. R8 removes unused code (chiefly the
# material-icons-extended dex, of which the UI uses ~8 icons) but does NOT
# rename or optimize. That keeps every reflection-free string lookup the app
# relies on valid - org.json field names, enum valueOf()/name(),
# SharedPreferences keys, BuildConfig fields - and keeps the BLE callback and
# Compose class names intact. The app has no Gson/Moshi/kotlinx.serialization
# and no Class.forName/newInstance, so there are no reflection-keyed model
# classes to keep.
#
# This is the safe starting point for a safety-overlay app that must start
# reliably. scripts/check-release-dex-keeps.py is a first canary on tightening
# it (dropping -dontoptimize, enabling obfuscation): both of those take names
# out of the shipped DEX and turn it red. It does not clear such a change on
# its own - it checks enum names, and optimization can break reflection or BLE
# dispatch without touching one. Read its failure as this file needing a keep
# rule, not the gate needing an edit.

-dontobfuscate
-dontoptimize

# Manifest-declared components (Activity/Service/Receiver) are kept by the
# AAPT-generated rules; these are belt-and-braces so an accidental rename of
# an entry point or a directly-referenced BLE callback can't drop it.
-keep class es.jjrh.bikeradar.BikeRadarService { *; }
-keep class es.jjrh.bikeradar.**Receiver { *; }
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }
