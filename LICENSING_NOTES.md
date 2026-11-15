# Licensing notes

How the cross-app licensing is split and what keeps it that way. Read it
before touching a contract file, a licence header or the README's licence
section.

- **Six files are Apache-2.0**: the three `.aidl`, plus `RadarContract.kt`,
  `RadarStateParcel.kt` and `RadarVehicleParcel.kt`. Together they are a
  complete client contract; the `.aidl` alone are not, since
  `RadarStateParcel.aidl` is a forward declaration and the constants are not
  in them. Licence text is `LICENSES/Apache-2.0.txt`. Everything implementing
  them stays GPL-3.0-or-later, and Apache-2.0 combines into a GPL-3.0 whole,
  so the app's own licence is untouched. Do NOT normalise those headers, and
  keep those files free of any reference to the app behind them, or the
  permission means nothing. `InterfaceIsPermissiveImplementationIsNotTest`
  pins the list and `ContractIsSelfContainedTest` pins the self-containment.
  A new `.aidl` fails until someone puts it on a side; a new `.kt` lands in
  the copyleft bucket and passes quietly, so decide deliberately.
  **The list exists in three places and all three must move together**: that
  test, `PERMISSIVE_FILES` in `scripts/check-licence-headers.py`, and the
  README section the same check pins. Updating only the test reds CI worded as
  an SPDX mismatch, which is loud but names the wrong cause.
- **`additional-permission.txt` covers writing a consumer** rather than
  copying our files: a section 7 grant that an app communicating solely
  through the interface is not, by virtue of that communication, a work based
  on this one. That qualifier is load-bearing and must survive any rewrite;
  without it the grant reaches an app that also embeds our GPL code. Do not
  argue in public docs that two apps over Binder are separate programs. The
  grant settles the question rather than taking a position on it.
- **A permissive file's imports stay platform-only** (`android.`, `java.`,
  `kotlin.`). Nothing enforces that, deliberately: every other check on this
  surface exists because its failure is SILENT, and this one's is not. A
  third-party import fails at build time on the copier's side, naming what
  they lack; an `androidx` one usually just resolves, because virtually every
  Android project already carries androidx. Neither is a licence problem
  while every artifact on the release runtime classpath is Apache-2.0, which
  `scripts/check-transitive-licences.py` reports on rather than gates. An
  import here necessarily lands on that classpath. Do not read the absence of
  a test as the absence of the rule.
- **A NEW FIELD ON `RadarStateParcel` IS A DISCLOSURE CHANGE.** Whatever
  crosses that wire reaches any app the rider has granted, and
  `settings_privacy_to_apps_body` is where they read what that is, in BOTH
  locales. On the MQTT side `HaClientDataDisclosureTest` forces it for a new
  topic family and for a new field in the shared discovery device block
  (`theDeviceBlockCarriesOnlyTheFieldsItDiscloses`); a new key inside an
  existing payload is not caught there either and is disclosed by hand.
  There is no check at all for the cross-app side, so a field added here
  ships undisclosed with every gate green. Update the disclosure in the same
  commit.
