# Contributing

Thanks for your interest. This is a small personal project (GPL-3.0-or-later),
but translations, bug reports and protocol corrections are all welcome.

## Translations

The app's user-facing text lives in Android string resources, so translating
it needs no Android tooling - just a text editor (or the GitHub web editor) and
the language. All English strings are in
[`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml);
[`app/src/main/res/values-es/strings.xml`](app/src/main/res/values-es/strings.xml)
is a worked example (Spanish).

To add a language:

1. Fork the repo.
2. Create `app/src/main/res/values-<code>/strings.xml`, where `<code>` is the
   Android locale qualifier - e.g. `values-fr` (French), `values-de` (German),
   `values-pt-rBR` (Brazilian Portuguese), `values-zh-rTW` (Traditional
   Chinese). Note Android uses `-r` before a region (`pt-rBR`), which differs
   from the web `pt-BR`.
3. Copy the `<string>` and `<plurals>` entries from `values/strings.xml` and
   translate **only the text between the tags**. The easiest way is to open the
   English file on GitHub, press <kbd>.</kbd> to launch the in-browser editor
   (github.dev), copy it into the new file, and translate in place.
4. Open a pull request.

Rules that keep a translation from crashing the app:

- **Don't touch the `name="..."` keys** - they're how the code finds each
  string. Translate values only.
- **Keep every `<xliff:g>...</xliff:g>` placeholder intact**, including the
  `%1$s` / `%1$d` inside it. These are filled in at runtime (a number, a device
  name, a time). You may move a placeholder earlier or later in the sentence if
  your language's word order needs it, but never delete, duplicate or renumber
  the `%n$` arguments - a dropped argument crashes the app, and CI rejects it.
- **`<plurals>` need the right categories for your language.** English and
  Spanish use `one` / `other`; some languages use more (`zero`, `few`, `many`).
  See the [Unicode plural rules](https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html).
- **Leave brand and technical terms as-is**: Bike Radar, Bluetooth, Home
  Assistant, Bosch Flow, MQTT, eBike, GPL-3.0, and similar.
- The `app_name` is intentionally not translatable; don't add it.

What CI checks on a translation PR: the full build runs, and `lintDebug`
treats missing strings and broken format arguments as errors (`MissingTranslation`,
the `StringFormat` checks). So a PR either has every string and valid
placeholders, or it fails - there's no way to half-break the app. Reviews are
manual; PRs aren't auto-merged. A locale doesn't have to be 100% complete to be
useful, but the more complete the better.

(A hosted web translation platform may be set up later if there's demand; until
then, plain pull requests are the way.)

## Bug reports

Please include your phone model, Android version, and radar firmware. The Debug
screen (hidden behind a triple long-press on the app title) can write a capture
log and copy a diagnostic bundle to help.

## Code

- Build and test instructions are in [`README.md`](README.md) and
  [`AGENTS.md`](AGENTS.md). Builds run in Docker; tests are pure-JVM
  (Robolectric) and include Roborazzi screenshot goldens.
- Decoder/behaviour changes must add or update unit tests.
- Wire-protocol corrections belong in the companion
  [`bike-radar-docs`](https://github.com/partymola/bike-radar-docs) repo, not
  here.
- Commit subjects use conventional-commit prefixes (`feat:`, `fix:`, `ui:`,
  `test:`, `build:`, `ci:`, `docs:`, plus area scopes like `ble:`, `ha:`,
  `protocol:`).
- GPL-3.0-or-later. Don't paste in code under an incompatible licence.
