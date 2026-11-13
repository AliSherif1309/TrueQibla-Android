# Arabic typography preference

- The user's preferred Arabic font is Thmanyah from https://font.thmanyah.com/.
- Prefer Thmanyah Sans for Arabic app UI, Serif Display for prominent headings, and Serif Text for long reading passages when appropriate.
- Use SahlTV-Launcher as the architectural reference: offline assets, build-time secret, private runtime font loading, and graceful system-font fallback. Do not claim that obfuscation alone establishes license compliance.
- Preserve suitable Latin typography, monospace codes, existing Quran/document fonts, text editing spans, accessibility scaling, and RTL behavior. Do not change source document content.
- Before adding or copying font assets, resolve the distribution restrictions documented in docs/ARABIC_TYPOGRAPHY.md. Do not publish raw fonts or keys, or copy encrypted font payloads into new repositories without confirmed distribution rights.
- Font acquisition must use the official source; do not scrape demo web fonts, modify/subset the font, or use third-party mirrors.
- Validate Arabic and mixed Arabic/English text, dynamic labels, inputs, dialogs, menus, clipping, RTL and offline operation. Add meaningful automated coverage when changing runtime typography.
- Submit changes through a pull request. Do not merge or enable automatic merge; required CI must pass before any separately authorized merge.
