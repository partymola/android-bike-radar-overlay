# Security Policy

## Reporting a vulnerability

Please report security issues privately, not in a public issue.

Use GitHub's private vulnerability reporting: open the **Security** tab of
this repository and choose **Report a vulnerability**. If that is
unavailable, open a normal issue that asks for a private contact channel
without including any exploit detail.

Helpful things to include:

- app version (Settings -> About, or the `versionName` from the tag),
- Android version and device model,
- a description of the issue and, where possible, steps to reproduce.

This is a personal alpha project maintained in spare time, so responses
are best-effort rather than on a fixed SLA. Reports that turn out to be
genuine will be fixed and credited (if you want credit) in the CHANGELOG.

## Supported versions

Only the most recent release receives fixes. The project is pre-1.0 and
ships as a GitHub pre-release; older tags are not patched.

## Scope notes

The app stores Home Assistant credentials on-device (encrypted via the
Android Keystore) and speaks BLE to nearby radar/eBike/camera hardware.
Findings in those areas - credential handling, the BLE trust model, or
data written to the on-device capture log - are especially welcome.

The dependency graph is scoped to the app's **release runtime** classpath -
what actually ships in `app-release.apk` (AndroidX, Compose, kotlinx) - so
Dependabot alerts reflect code that can reach a rider's phone. The Gradle
plugin/build classpath (AGP, ktlint, Roborazzi and their server-side
transitives such as Netty or Bouncy Castle) runs only on the build host and
is deliberately excluded from the graph: those libraries never link into the
APK, so a CVE in them is build-pipeline hygiene, not a user-facing exposure.
