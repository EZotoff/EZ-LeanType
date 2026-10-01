# Release Discipline for EZ LeanType

This document outlines the versioning, branching, and release standards for **EZ LeanType**.

---

## 📌 Principles

1. **Semantic Versioning (`MAJOR.MINOR.PATCH`)**:
   - **`MAJOR`**: Architectural redesign, breaking changes to plugins, settings schema migrations that cannot be backward compatible.
   - **`MINOR`**: New engines (e.g. new speech/handwriting backends), new keyboard layouts, major new feature toggles.
   - **`PATCH`**: Bug fixes, performance optimizations, stability patches, documentation updates.

2. **Immutable Releases**:
   - **Never overwrite or re-tag an existing release**.
   - Once a tag `vX.Y.Z` and its release assets are published, they must remain permanent.
   - If a bug is found in a release, do **not** force-push or re-upload to the old tag; instead, increment the `PATCH` version (`vX.Y.(Z+1)`), document the fix in release notes, and create a new release.

3. **Version Code Alignment**:
   - `versionCode` in [`app/build.gradle.kts`](file:///workspace/wise-bose/app/build.gradle.kts) must monotonically increase with each release:
     - Format: `MMmmPP` (e.g. `4.2.7` -> `4207`, `4.2.8` -> `4208`, `4.3.0` -> `4300`).
   - `versionName` must match the release tag string without the leading `v` (e.g., `versionName = "4.2.8"` for tag `v4.2.8`).

4. **Release Checklist**:
   - [ ] Run full unit tests: `./gradlew testStandardDebugUnitTest`
   - [ ] Bump `versionCode` and `versionName` in [`app/build.gradle.kts`](file:///workspace/wise-bose/app/build.gradle.kts)
   - [ ] Create release notes under `docs/releasenote/release_notes_v<version>.md`
   - [ ] Update documentation references (e.g., `docs/OFFLINE_VOICE_MODELS.md`, `README.md`)
   - [ ] Build release APK: `./gradlew assembleStandardDebug` (or release variant)
   - [ ] Commit with message `release: vX.Y.Z`
   - [ ] Create annotated git tag: `git tag -a vX.Y.Z -m "EZ LeanType vX.Y.Z"`
   - [ ] Push commit and tag: `git push origin main --tags`
   - [ ] Create GitHub release with release notes and attach APK and verified model assets
