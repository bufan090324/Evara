# Evara development and release

The user authorized automatic publication on 2026-10-10: after a new version is built and required checks pass, publish its source, Windows portable package, Android APK and signed update manifest to the public bufan090324/Evara repository without asking for another release confirmation. This applies to work completed in an active development turn; do not claim unattended scheduled work exists. A later user request to hold publication takes precedence.

- Use one release version for both clients. Android versionCode must exceed every previously delivered build, including local test builds.
- Preserve Android package name, APK signing identity, update verification key, protocol and user data paths.
- Verify builds, relevant tests, APK signing/package/version, packaged Windows startup, and artifact hashes before publication. Failed checks block publication; fix or clearly report the obstacle.
- Stage only approved source. Never publish pairing credentials, TLS private keys, update private keys, AI settings/keys, real health reports, screenshots, runtime logs or machine-specific paths.
- Create a draft release, upload and verify all artifacts, then publish it as latest with its signed update.json. Never move published tags or silently replace existing releases.
- Release notes must distinguish compiled, automated, emulator and real phone evidence. Known unsupported pages or incomplete device validation must remain visible.
- Verify the public latest update feed after publication. Do not report successful publication or update availability until actual verification succeeds.
- FEATURES.md is the sole current capability list; other test documents are versioned evidence, not competing capability lists.
