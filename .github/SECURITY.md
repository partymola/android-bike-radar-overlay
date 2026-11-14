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

This is a personal project maintained in spare time, so responses
are best-effort rather than on a fixed SLA. Reports that turn out to be
genuine will be fixed and credited (if you want credit) in the CHANGELOG.

## Supported versions

Only the most recent release receives fixes; older tags are not patched.

## Scope notes

The app stores Home Assistant credentials on-device in its private
SharedPreferences (readable by no other app); device backups of them are
protected by the platform's lockscreen-derived backup encryption. It
also speaks BLE to nearby radar/eBike/camera hardware. Findings in those
areas - credential handling, the BLE trust model, or data written to the
on-device capture log - are especially welcome.

## Cleartext HTTP to Home Assistant

The manifest sets `usesCleartextTraffic="true"`, and there is deliberately no
`network_security_config.xml`. This is not a blanket allowance: a
network-security-config exempts cleartext by hostname or domain, but the
policy the app actually needs is "cleartext only to LAN hosts": RFC1918,
loopback, IPv6 unique-local and link-local addresses, `.local` and `.lan`
names, and names with no dot that are at least three characters, do not start
with `xn--` and are not a single number. The HA host is an arbitrary
user-configured LAN IP or name that no static domain-config can enumerate.
Enforcement is therefore at the application layer: `HaUrlPolicy` requires
HTTPS for any other host, and the settings UI refuses to save a cleartext URL
to one. An IPv4 address must be written in plain dotted decimal: one with a
leading zero in any part, such as `010.1.1.1`, is refused, because some parsers
read it as octal. Beyond that the check is on the address range, not on whose
device it is: a private address can belong to another network's device. A name
is judged by its form, not by what it resolves to, so on a network whose DNS
answers a `.local`, `.lan` or dotless name with an internet address, the token
would go there over plain HTTP. HTTPS avoids that.

The Gradle dependency graph is scoped to the app's **release runtime**
classpath - what actually ships in `app-release.apk` (AndroidX, Compose,
kotlinx) - so its Dependabot alerts reflect code that can reach a rider's
phone. The Gradle
plugin/build classpath (AGP, ktlint, Roborazzi and their server-side
transitives such as Netty or Bouncy Castle) runs only on the build host and
is deliberately excluded from the graph: those libraries never link into the
APK, so a CVE in them is build-pipeline hygiene, not a user-facing exposure.
The Gradle submission adds one build-host entry, Gradle itself: the action
always reports the Gradle version running the build (`org.gradle:gradle-core`,
development scope), and no filter removes it. An alert on it concerns the
build host, not the APK.
