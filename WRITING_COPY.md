# Writing copy (UI strings)

User-facing text lives in `res/values/strings.xml` (en) + `values-es/`. When
adding or editing it, follow these principles - the `/qc` copy reviewer
enforces them, and CONTRIBUTING.md points contributors here:

- **Benefit, not mechanism.** Say what the rider gets, not how it works. "Set
  your lights by local sunset" beats "compute sunrise/sunset for the auto-mode
  state machine". Internals (MQTT discovery, BLE stack, GCM, file paths) are
  noise on most screens.
- **Short and scannable.** A phone screen is small and read mid-task. Prefer one
  line; use `\n• ` bullets for any list of three or more items rather than a
  dense paragraph (see the Privacy permissions/publish strings).
- **No jargon, acronyms, or filler nouns** the rider can't parse: drop
  "companion app", "telemetry", "bearer token", "phone home". Established
  product terms stay (Bluetooth, Home Assistant, Bosch Flow, MQTT, eBike).
- **es: Spain register** (tú), no LatAm vocab, and gender must match the
  on-screen referent: a shared string under both "Radar" (m) and "Cámara" (f)
  needs splitting (e.g. `_radar_not_seen` / `_cam_not_seen`).
- **es runs long - keep it tight.** Spanish averages ~15-30% longer than
  English, but the SHORT strings in the tightest spots expand worst - single
  labels and chips can grow 100-300% ("Dashcam" 7 chars -> "Cámara delantera"
  16). The layout-sensitive surfaces are labels, button text, screen/section
  titles, chips, and notification titles; size them to the longest es form,
  never the en width. Concrete levers (Spain UI convention, tú register):
  - **Infinitive for action labels** (buttons, menu items, chips): "Configurar",
    "Cancelar", "Seleccionar todo". **Imperative tú for prompts** that tell the
    rider to act: "Configúrala", "Elige", "Pulsa Aceptar". Both beat
    "Configurar la cámara delantera" - drop the object the screen already shows.
  - **Omit articles/possessives where Spanish allows** - "Crear carpeta" not
    "Crear una carpeta", "Modo de luz" not "Modo de la luz", "del eBike" not
    "de tu eBike". Don't stack "de la ... delantera" ("Luz de cámara").
  - **Drop a qualifier the screen already supplies** - "Cámara" not "Cámara
    delantera" on a chip / glyph legend / switch-row (the app has one camera).
  - **No gerund for titles/labels** - translate -ing as an infinitive or noun
    ("Configurar", "Búsqueda"); reserve the gerund for genuine progress
    ("Buscando…", "Imprimiendo…").
  - **Nominal style in short status strings** - drop ser/estar: "Disco lleno",
    "Cámara no disponible", not "El disco está lleno".
  - **Symbols, not abbreviations, for units** (no period, no plural, space):
    "30 s", "2 min", "10 km". **The percent sign is the exception and takes
    no space**: "12%", not "12 %". This overrides the RAE norm deliberately
    and `values-es` is consistent with it throughout, so a lone "12 %" is a
    regression rather than a correction. Ordinary abbreviations keep the dot
    and accent ("máx.", "mín.", "núm."). Reuse the Android-es words riders
    know ("Ajustes", "No molestar").
  - **"Riding" is "montar en bicicleta". "Conducir" is used too. NEVER
    "rodar".** Not a register preference: in the DLE every sense of `rodar`
    that involves wheels takes the VEHICLE as its subject ("El automóvil rodó
    lentamente"), so "mientras ruedas" says "while you roll". `montar` carries
    the rider sense (DLE 3, `cabalgar`, used transitively too). Cycling
    glossaries do use `rodar`, but as peloton jargon, which is the wrong
    register for a commuter. Do not reintroduce it.
    **Clipping it to `bici` is standard Spain and is accepted** where the full
    form does not fit, as in the riding-aid notice's title `Antes de montar en
    bici`. Settled; do not "correct" such a title back to `bicicleta`.
  - **Digits for numbers, even below 10**: "1 aviso", "3 coches".
  - **The all-clear is «Carretera despejada»**, never «Vía despejada» or
    «Vía libre»: it is what a Spanish rider says. A close pass is an
    `adelantamiento ajustado` in the app (`adelantamiento cercano` in the
    README's Spanish section), never a `pase`.
  - **Guillemets are accepted when es quotes one of the app's own labels**
    («Carretera despejada»), even though `values-es` elsewhere escapes straight
    quotes for the same job. Settled; do not raise it as an inconsistency.
  - **Sentence case** - capitalize only the first word ("Seguir mi luz", not
    "Seguir Mi Luz").
  A term that fits the en layout can overflow es - verify against the es
  Roborazzi golden (or on-device) for clipping/wrapping before committing.
- **The Privacy screen is the deliberate exception.** It is the "verify by
  reading the code" disclosure; it keeps full substance (and the literal tokens
  `scripts/privacy-disclosure-check.sh` pins: permission names, the backup
  disclosure + manifest/backup-rules pairing, HTTPS, the DataDisclosure
  keywords). Trim it to bullets, never gut it.
  **That script reads `values/strings.xml` only, so every Spanish disclosure gap
  is invisible to it** - it has already let the es sharing paragraph enumerate
  one fewer data category than the en one. Be precise about which half is
  guarded: a disclosure string PRESENT in one locale and missing from the other
  is caught by lint, since `MissingTranslation` and `ExtraTranslation` are
  errors with `abortOnError` (measured - deleting one es string fails
  `:app:lintDebug`). What nothing catches is the half that actually bit: a
  string that keeps its key in both locales while one of them says something
  narrower. When a disclosure changes, read both locales side by side.
  It also never opens `SettingsPrivacy.kt`, so it cannot see a string that
  exists but is no longer rendered. `SettingsPrivacyRendersEveryDisclosureTest`
  is what covers that half.
- **Review with screen context, not a flat string list.** Verbosity and
  gender-in-context bugs only show on the screen: use the English Roborazzi
  goldens or map each string to its Composable referent before judging it.
