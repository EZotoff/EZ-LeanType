# Voice corpus results

| block | feature | WER | checks | segments | refined | wall s | verdict |
|---|---|---|---|---|---|---|---|
| B0 | calibration | 15.0% | 4/5 | 1 | 0 | 17 | FAIL |
| B1 | validation/vocabulary | 29.5% | 11/14 | 1 | 0 | 31 | FAIL |
| B2 | dictionary/vocabulary | 41.7% | 4/12 | 1 | 0 | 31 | FAIL |
| B3 | homophones/context | 13.9% | 16/17 | 1 | 0 | 44 | FAIL |
| B4 | punctuation | 35.3% | 10/14 | 1 | 0 | 35 | FAIL |
| B5 | symbols/fillers/commands | 107.7% | 6/16 | 1 | 0 | 33 | FAIL |
| B6 | long-form/segmentation | 97.8% | 5/11 | 6 | 0 | 63 | FAIL |

## B0 (calibration)
- FAIL missing: 'voice validation'

## B1 (validation/vocabulary)
- FAIL missing: 'Mauro'
- FAIL missing: 'Elan'
- FAIL missing: 'OC Beacon'
- known-limitation: Light LLM -> LiteLLM (needs AI provider; d2 tier misses 7-char words)

## B2 (dictionary/vocabulary)
- FAIL missing: 'Qwen'
- FAIL missing: 'Vercel'
- FAIL missing: 'ez-omo-bench'
- FAIL missing: 'ez-omo-config'
- FAIL missing: 'Comfy UI'
- FAIL missing: 'OC Beacon'
- FAIL forbidden present: 'Quen'
- FAIL forbidden present: 'easy omo'

## B3 (homophones/context)
- FAIL missing: 'cites a source'

## B4 (punctuation)
- FAIL missing: 'simple, never'
- FAIL missing: 'things: coffee'
- FAIL missing: 'patience;'
- FAIL missing: 'it!'
- known-limitation: trailing 'billing period' sentence: standalone spoken-word 'period' currently converts to '.' — trap documents which behavior wins

## B5 (symbols/fillers/commands)
- FAIL missing: '[array]'
- FAIL missing: '(count)'
- FAIL missing: '"ship it"'
- FAIL missing: 'A/B'
- FAIL missing: 'pass/fail'
- FAIL missing: 'client/server'
- FAIL missing: 'should replace the filler words'
- FAIL forbidden present: 'open paren'
- FAIL forbidden present: 'open quote'
- FAIL regex newline_command (\n$|\n ) not matched

## B6 (long-form/segmentation)
- FAIL missing: 'USB-C'
- FAIL missing: 'iPhone'
- FAIL missing: 'macOS'
- FAIL missing: 'HTTP/2'
- FAIL missing: '8080'
- FAIL missing: 'LT-1042'
- known-limitation: digit/number formatting (4.2.9, 9:45, 2026) depends on ASR tokenization

## Transcripts
### B0
```
This is Lintai poise validation take one. The quick brown fox jumps over the lazy dog while the engine listens.
```

### B1
```
I work with Bruno and Maur on the Veran project. We use agents Ilan and Oren. I use Light L. L. M. and O. C. Beacon. We also verify stuff and open PRs. I also run agente QA. Yesterday, Veran merged my pull request and Nestor.
```

### B2
```
Quen handles the reasoning, Light LM routes the requests, and Versel hosts the demo. Fabro opened the ticket for Easy Omo Bench because Easy Omo Config lost its theme. Nestor moved the conf UI workflow to the new OCB control.
```

### B3
```
Their deploy failed because they're out of disk, so there are two options either way too long or write the migration right now. I accept all changes except the ones that break the build. The break fixed shipped in the first week, though the team was weak on testing. It's a peace deal. Each piece of the site sites a source you can see. We open issues and open PRs every morning.
```

### B4
```
The first rule is simple, Never skip the tests. Can I ship on Friday? I ordered three things, Coffee, a charger, and patients. Semi Then I waited. Wait for it.! The building.
```

### B5
```
(or a), then open parent count close parent. She said open quotes ship it close quotes twice. Try an s / b test or a pass / fail check and a client / server split. We should replace the fill awards new line.
```

### B6
```
Version four point two point nine fixed three crashes and shipped on twelfth October twenty twenty-six at nine forty five p. m. Twelve October twenty twenty-six at nine forty five p. m. The USB C port two monitors and about fifteen percent battery were fine. Port to monitors and about fifteen percent battery were fine i filed l t - one oh four two because the iphone kept disconnecting from macros over h t p / two on port eighty eighty Over h. t. p. / two on port eighty eighty. Later the same day, the c. I. Q. emptied. Bruno reviewed the OC Beacon. can locks and Oren shipped the fix before dinner. Bruno reviewed the OC Beacon. can locks and Orange shipped the fix before dinner. Nobody wrote it down, which is why I am dictating this instead. The lesson is simple. When the tests are green, but the phone is hot, someone else is running.
```
