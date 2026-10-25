# Voice corpus results

| block | feature | WER | checks | segments | refined | wall s | verdict |
|---|---|---|---|---|---|---|---|
| B0 | calibration | 25.0% | 4/5 | 1 | 0 | 15 | FAIL |
| B1 | validation/vocabulary | 20.4% | 10/14 | 1 | 0 | 26 | FAIL |
| B2 | dictionary/vocabulary | 25.0% | 9/12 | 1 | 0 | 19 | FAIL |
| B3 | homophones/context | 20.8% | 15/17 | 1 | 0 | 28 | FAIL |
| B4 | punctuation | 58.8% | 4/14 | 1 | 0 | 21 | FAIL |
| B5 | symbols/fillers/commands | 100.0% | 8/16 | 1 | 0 | 29 | FAIL |
| B6 | long-form/segmentation | 58.7% | 7/11 | 6 | 0 | 62 | FAIL |

## B0 (calibration)
- FAIL missing: 'voice validation'

## B1 (validation/vocabulary)
- FAIL missing: 'Elan'
- FAIL missing: 'Oren'
- FAIL missing: 'OC Beacon'
- FAIL forbidden present: 'Elon'
- known-limitation: Light LLM -> LiteLLM (needs AI provider; d2 tier misses 7-char words)

## B2 (dictionary/vocabulary)
- FAIL missing: 'ez-omo-bench'
- FAIL missing: 'ez-omo-config'
- FAIL forbidden present: 'easy omo'

## B3 (homophones/context)
- FAIL missing: 'accept all changes except'
- FAIL missing: 'cites a source'

## B4 (punctuation)
- FAIL missing: 'simple, never'
- FAIL missing: 'tests.'
- FAIL missing: 'things: coffee'
- FAIL missing: 'charger,'
- FAIL missing: 'patience;'
- FAIL missing: 'it!'
- FAIL forbidden present: ' period'
- FAIL forbidden present: ' question mark'
- FAIL forbidden present: ' exclamation mark'
- FAIL forbidden present: ' semicolon'
- known-limitation: trailing 'billing period' sentence: standalone spoken-word 'period' currently converts to '.' — trap documents which behavior wins

## B5 (symbols/fillers/commands)
- FAIL missing: '[array]'
- FAIL missing: '(count)'
- FAIL missing: '"ship it"'
- FAIL missing: 'A/B'
- FAIL missing: 'pass/fail'
- FAIL missing: 'client/server'
- FAIL forbidden present: 'open paren'
- FAIL regex newline_command (\n$|\n ) not matched

## B6 (long-form/segmentation)
- FAIL missing: 'USB-C'
- FAIL missing: 'HTTP/2'
- FAIL missing: '8080'
- FAIL missing: 'LT-1042'
- known-limitation: digit/number formatting (4.2.9, 9:45, 2026) depends on ASR tokenization

## Transcripts
### B0
```
Mrs. Lint I voice, validation take one. The quick brown fox jumps over the lazy dog while the engine listens.
```

### B1
```
I work with Bruno and Mauro on the Veron project. We use agents Elon and Oran. I use Light LM and O C beacon. We also verify stuff and open PRs. I also run Agenti QA. Yesterday Veran merged my pull request and Nestor approved it.
```

### B2
```
Qwen handles the reasoning, light LLM routes the requests, and Vercel hosts the demo. Fabro opened the ticket for Easy Omo Bench because Easy Omo Config lost its theme. Nestor moved the Comfy UI workflow to the new OC Beacon cluster.
```

### B3
```
Their deploy failed because they're out of disk. So there are two options either wait too long or write the migration right now. Except all changes except the ones that break the build. The break fixed shipped in the first week. Though the team was weak on testing. It's a peace deal, each piece of the sites sites a source you can see. We open issues and open PRs every morning.
```

### B4
```
The first rule is simple, coma, never skip the tests period. Can I ship on Friday? question Mark. I ordered three things, Coffee, coma, a charger coma and patience, semicolon. Then I waited. Wait for it, exclamation Mark. The billing
```

### B5
```
(or a). Then open parent count close parent. She said " ship it close quotes twice. Try an / B test or a pass / fail check and a client / server split. We should replace the filler words new line.
```

### B6
```
Version four point two point nine fixed three crashes and shipped on twelfth october twenty twenty six at nine forty five PM. The USB C port two monitors and about fifteen percent battery were fine. Port two monitors and about fifteen percent battery were fine. I filed LT one oh four two because the iPhone kept disconnecting from macOS over HTP / two on port eighty eighty. Later the same day the CIQ emptied. Bruno reviewed the OC Beacon logs and Oren shipped the fix before dinner. OC Beacon locks and Orange shipped the fix before dinner. Nobody wrote it down, which is why I am dictating this instead. The lesson is simple. When the tests are green but the phone is hot, someone else is running.
```
