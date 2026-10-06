# LeanType On-Device QA

One command runs the full quality gate **on this phone** — no emulator, no CI,
no desktop:

```sh
sh .harness/qa.sh
```

What it does: assembleOfflineDebug (signed APK) → full unit-test suite
(284 tests: 44 pure JVM + 240 Robolectric with real Android framework code via
an arm64 native runtime) → pass/fail summary. Exit code 0 = green.
Full log: `/data/local/tmp/androidharness-scratch/qa-last.log`.

Typical runtime: ~3–5 min when the phone is idle.

## When to run it

- After any change to `app/src/main/` (input logic, settings, parser, dictionaries).
- Before committing / pushing.
- Not while the phone is under heavy use — the Gradle daemon fork is
  load-sensitive and can fail spuriously (a `libc.so: cannot open shared
  object file` on the daemon means fork failure under load, not a real break).
  `sh .harness/qa_when_idle.sh` waits for load < 5, then runs the gate.

## What it does NOT cover

- No UI testing: nothing taps keys or renders layouts. Use the crash watcher
  and (planned) UI probe for that.
- No IME service lifecycle on real Android, no gesture/voice behavior.
- Robolectric is an approximation of Android; a green suite is not a guarantee
  the app behaves on a real device.

## How it works (the non-obvious parts)

The suite runs on a **Debian glibc arm64 JDK 17** living in
`/data/local/tmp/androidharness-scratch/arm64-glibc/root` — stock Android can't
run it, so three patches make it work:

1. **ELF interpreter patch.** Every JDK executable's `PT_INTERP`
   (`/lib/ld-linux-aarch64.so.1`) is rewritten to `/data/local/tmp/ld64`
   (a symlink to the real loader; `/` is read-only so the original path can
   never exist). New path must be ≤ original length. `jspawnhelper` is patched
   too — without it the JVM cannot spawn child processes, so Gradle daemons
   die. Tool: `.harness/patch_interp.py`. If the sysroot is ever re-extracted,
   re-run `patch_all_bins2.sh` + `setup_short_interp2.sh` + `repoint_ld.sh`.

2. **glibc 2.41 (trixie)** layered over bookworm: the arm64 Robolectric native
   runtime was built against glibc 2.38 and fails on 2.36.

3. **arm64 Robolectric native runtime.** Upstream ships no `linux/aarch64`
   binary. Local artifact
   `com.seekrtech:robolectric-nativeruntime-arm64:4.14.1` lives in a local
   Maven repo (`$SCRATCH/local-maven`) and contains:
   - upstream `nativeruntime-4.14.1.jar` classes
   - `native/linux/aarch64/librobolectric-nativeruntime.so` and an alias
     `libandroid_runtime.so` (the name used on SDK ≥ 35)
   - `Arm64NativeRuntimeLoader` (source: `.harness/arm64-loader/src/`), a
     ServiceLoader-registered loader with `@Priority(MAX)` that accepts
     linux+aarch64 and can fall back to loading the `.so` from a plain
     directory (`robolectric.nativeruntime.dir` sysprop) when the sandbox
     classloader hides jar resources — each load uses a unique temp copy to
     avoid "already loaded in another classloader"
   - conscrypt arm64 slice at `META-INF/native/`
   Rebuild with `.harness/rebuild_loader_jar.sh` (slices come from
   seekrtech/robolectric-arm64 on GitHub; boringssl libs extracted from the
   openSUSE Leap 15.5 `libboringssl1` rpm — the current Tumbleweed boringssl
   namespaced its symbols under `bssl::` and does NOT work).

`app/build.gradle.kts` guards all of this behind `os.arch == "aarch64"`: on an
x86_64 CI machine the substitution is skipped and upstream Robolectric is used,
so this change is CI-safe.

## Mockito configuration (important)

`app/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
contains `mock-maker-subclass`. The default inline mock-maker self-attaches a
Java agent; on Android the attach mechanism blocks ~10 s per attempt and
hangs the suite. Consequences:

- Only classes/mocks compatible with subclass mocking work. Classes the tests
  mock must be `open` (MainKeyboardView, KeyboardId, SettingsValues' stubbed
  getter, SingleDictionaryFacilitator, App — already done).
- Stub Kotlin `val` getters with the `doReturn(x).when(mock).prop` style, not
  `when(mock.prop).thenReturn(x)` — the latter calls the real final getter.
- Do not "fix" a failing mock by switching back to the inline mock-maker.

## Maintenance cheatsheet

| Symptom | Cause | Fix |
|---|---|---|
| Daemon fails, `libc.so: cannot open` in log line 1 | phone under load (fork pressure) | wait for idle / use qa_when_idle.sh |
| `GLIBC_2.38 not found` loading nativeruntime | sysroot reverted to bookworm glibc | re-run `.harness/trixie_dl.sh` + `trixie_extract.sh` |
| `CANNOT LINK EXECUTABLE java` missing `libc.so.6` | `LD_LIBRARY_PATH` missing glibc dirs | use `.harness/run_tests_glibc.sh` env as reference |
| JVM cannot spawn children (Gradle daemon dies) | `jspawnhelper` lost its interp patch | re-run `patch_all_bins2.sh` |
| `resource native/linux/aarch64/... not found` | rebuilt jar missing alias/slice | re-run `rebuild_loader_jar.sh` |
| mock-maker errors after editing the extension file | stale `processJavaRes` output | touch the file / clean `processStandardDebugUnitTestJavaRes` |
| source `open` change not visible to mocks | stale kotlin-classes | delete the stale `.class` dir, recompile |

## Files

- `.harness/qa.sh` — the gate.
- `.harness/qa_when_idle.sh` — load-aware wrapper.
- `.harness/run_tests_glibc.sh` — tests only (reference for the env).
- `.harness/arm64-loader/` — loader Java source + services file.
- `.harness/patch_interp.py` — ELF interpreter rewriter.
- `.harness/rebuild_loader_jar.sh` — rebuild the arm64 nativeruntime artifact.

## Known gaps / roadmap

1. Crash/ANR watcher on live logcat (passive, real-usage signal) — not built.
2. Supervised UI probe (screencap → decide → input tap) for settings screens —
   not built.
3. Tailnet-PC lane for fast unattended QA, and an agentic emulator loop —
   design agreed, not built. On a PC the whole arm64/glibc patching vanishes
   (native loader exists); x86_64 CI needs no substitution at all.
4. Source changes for testability (`open` classes/methods) are in `main/` —
   if upstream HeliBoard merges are wanted, keep them minimal.

Never commit anything from `.harness/` (gitignored; contains keystore,
tokens, and device-specific paths). The only repo changes belonging to this
work are: `app/build.gradle.kts` (arm64 substitution + test jvmArgs),
`app/src/test/resources/mockito-extensions/*`, the `open` visibility tweaks,
and `PopupKeysUtilsTest.kt` stub-style fix.
