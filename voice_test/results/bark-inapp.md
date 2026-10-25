# Voice corpus results

| block | feature | WER | checks | segments | refined | wall s | verdict |
|---|---|---|---|---|---|---|---|
| B0 | calibration | 15.0% | 4/5 | 1 | 0 | 19 | FAIL |
| B1 | validation/vocabulary | 22.7% | 12/14 | 1 | 0 | 28 | FAIL |
| B2 | dictionary/vocabulary | 50.0% | 7/12 | 1 | 0 | 19 | FAIL |
| B3 | homophones/context | 26.4% | 15/17 | 1 | 0 | 28 | FAIL |
| B4 | punctuation | 35.3% | 10/14 | 1 | 0 | 22 | FAIL |
| B5 | symbols/fillers/commands | 107.7% | 6/16 | 1 | 0 | 29 | FAIL |
| B6 | long-form/segmentation | 51.1% | 5/11 | 6 | 0 | 62 | FAIL |

## B0 (calibration)
- FAIL missing: 'voice validation'

## B1 (validation/vocabulary)
- FAIL missing: 'Elan'
- FAIL missing: 'Nestor'
- known-limitation: Light LLM -> LiteLLM (needs AI provider; d2 tier misses 7-char words)

## B2 (dictionary/vocabulary)
- FAIL missing: 'ez-omo-bench'
- FAIL missing: 'ez-omo-config'
- FAIL missing: 'Comfy UI'
- FAIL missing: 'OC Beacon'
- FAIL forbidden present: 'easy omo'

## B3 (homophones/context)
- FAIL missing: 'open issues'
- FAIL missing: 'open PRs'

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
I work with Bruno and Mauro on the Veran project. We use agents Ilan and Oren. I use Light LiteLLM. and OC Beacon. We also verify stuff and open PRs. I also run agente QA. Yesterday, Veran merged my pull request.
```

### B2
```
Qwen handles the reasoning, Light LiteLLM. routes the requests, and Vercel hosts the demo. Fabro opened the ticket for Easy Omo Bench because Easy Omo Config lost its theme. Nestor removed the conf UI workflow.
```

### B3
```
Their deploy failed because they're out of disk. So there are two options. I wait too long or write the migration right now. I accept all changes except the ones that break the build. The break fixed shipped in the first week, though the team was weak on testing. It's a peace deal. Each piece of the site cites a source you can see.
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
Version four point two point nine fixed three crashes and shipped on twelfth October twenty twenty-six at nine forty five p. m. The US b. c. port to monitors, and about fifteen percent battery were fine. I filed l. t. - one oh four two because the iphone kept disconnecting from macros or h. t. p. / two on port eighty eighty. Later the same day, the CIQ emptied. Bruno reviewed the OC Beacon. can locks, and Oren shipped the fix before dinner. Nobody wrote it down, which is why I am dictating this instead The lesson is simple. When the tests are green, but the phone is hot, someone else is running.
```
