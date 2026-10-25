# Voice corpus: spoken vs transcribed

Build: 66024bf + harness (production behavior unchanged this session —
pipeline extraction only). Engine: Phonon-2, AI post-processing ON.

## B0 — calibration

**Spoken:**

This is LeanType voice validation, take one. The quick brown fox jumps over the lazy dog while the engine listens.

**Transcribed:**

Mrs. Lint I voice, validation take one. The quick brown fox jumps over the lazy dog while the engine listens.

segments: 1 | refined-by-AI: 0 | wall: 15s

## B1 — validation/vocabulary

**Spoken:**

I work with Bruno and Mauro on the Veran project. We use agents Elan and Oren. I use Light LLM and OC Beacon. We also verify stuff and open PRs. I also run Agentic QA. Yesterday Veran merged my pull request and Nestor approved it.

**Transcribed:**

I work with Bruno and Mauro on the Veron project. We use agents Elon and Oran. I use Light LM and O C beacon. We also verify stuff and open PRs. I also run Agenti QA. Yesterday Veran merged my pull request and Nestor approved it.

segments: 1 | refined-by-AI: 0 | wall: 26s

## B2 — dictionary/vocabulary

**Spoken:**

Qwen handles the reasoning, LiteLLM routes the requests, and Vercel hosts the demo. Fabro opened a ticket for ez-omo-bench because ez-omo-config lost its theme. Nestor moved the Comfy UI workflow to the new OC Beacon cluster.

**Transcribed:**

Qwen handles the reasoning, light LLM routes the requests, and Vercel hosts the demo. Fabro opened the ticket for Easy Omo Bench because Easy Omo Config lost its theme. Nestor moved the Comfy UI workflow to the new OC Beacon cluster.

segments: 1 | refined-by-AI: 0 | wall: 19s

## B3 — homophones/context

**Spoken:**

Their deploy failed because they're out of disk, so their two options are to wait too long or write the migration right now. I accept all changes except the ones that break the build. The brake fix shipped in the first week, though the team was weak on testing. It's a peace deal: each piece of the site cites a source you can see. We open issues and open PRs every morning.

**Transcribed:**

Their deploy failed because they're out of disk. So there are two options either wait too long or write the migration right now. Except all changes except the ones that break the build. The break fixed shipped in the first week. Though the team was weak on testing. It's a peace deal, each piece of the sites sites a source you can see. We open issues and open PRs every morning.

segments: 1 | refined-by-AI: 0 | wall: 28s

## B4 — punctuation

**Spoken:**

The first rule is simple comma never skip the tests period. Can we ship on Friday question mark. I ordered three things colon coffee comma a charger comma and patience semicolon then I waited. Wait for it exclamation mark. The billing period ends Friday.

**Transcribed:**

The first rule is simple, coma, never skip the tests period. Can I ship on Friday? question Mark. I ordered three things, Coffee, coma, a charger coma and patience, semicolon. Then I waited. Wait for it, exclamation Mark. The billing

segments: 1 | refined-by-AI: 0 | wall: 21s

## B5 — symbols/fillers/commands

**Spoken:**

Open bracket array close bracket, then open paren count close paren. She said open quote ship it close quote twice. Try an A/B test, or a pass/fail check, and a client/server split. Uh, we should, um, replace the, er, filler words. New line.

**Transcribed:**

(or a). Then open parent count close parent. She said " ship it close quotes twice. Try an / B test or a pass / fail check and a client / server split. We should replace the filler words new line.

segments: 1 | refined-by-AI: 0 | wall: 29s

## B6 — long-form/segmentation

**Spoken:**

Version 4.2.9 fixed three crashes and shipped on 12 October 2026 at 9:45 PM. The USB-C port, two monitors, and about 15 percent battery were fine. I filed LT-1042 because the iPhone kept disconnecting from macOS over HTTP/2 on port 8080. Later the same day the CI queue emptied, Bruno reviewed the OC Beacon logs, and Oren shipped the fix before dinner. Nobody wrote it down, which is why I am dictating this instead. The lesson is simple: when the tests are green but the phone is hot, someone else is running.

**Transcribed:**

Version four point two point nine fixed three crashes and shipped on twelfth october twenty twenty six at nine forty five PM. The USB C port two monitors and about fifteen percent battery were fine. Port two monitors and about fifteen percent battery were fine. I filed LT one oh four two because the iPhone kept disconnecting from macOS over HTP / two on port eighty eighty. Later the same day the CIQ emptied. Bruno reviewed the OC Beacon logs and Oren shipped the fix before dinner. OC Beacon locks and Orange shipped the fix before dinner. Nobody wrote it down, which is why I am dictating this instead. The lesson is simple. When the tests are green but the phone is hot, someone else is running.

segments: 6 | refined-by-AI: 0 | wall: 62s
