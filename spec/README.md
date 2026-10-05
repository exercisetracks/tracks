# `spec/` — shared domain tables

Tracks has three implementations of the same domain: Python on the server,
JavaScript in the web app, Kotlin in the mobile core. The algorithms differ by
language, but the **tables** they operate on are the same facts — which FIT
sport maps to which type, where a heart-rate zone starts, what counts as a good
TSB.

Tables are where drift actually happens. A regex reordered in one file and not
the others gives two clients that disagree about what a ride is, and nothing
fails to announce it. The sport taxonomy decides which of 19 activity layouts
renders, so that disagreement means the phone and the browser show a different
page for the same ride.

So the tables live here once, and get generated into each language.

## Layout

```
spec/
├── sport_taxonomy.yaml   FIT sport/sub_sport -> Tracks sport type
├── zones.yaml            HR/power zone models, TSB form bands
├── codegen.py            YAML -> Python + JavaScript + Kotlin
├── make_fixtures.py      sport-taxonomy corpus (frozen; see below)
├── make_format_fixtures.py  formatting corpus (regenerable)
├── openapi.json          snapshot of the server schema, for the client contract test
└── fixtures/             the shared oracle every language tests against
```

Generated outputs — **never edit these**, the next codegen run overwrites them:

| Language | Path |
|---|---|
| Python | `backend/app/spec/` |
| JavaScript | `frontend/src/spec/` |
| Kotlin | `mobile/core/src/commonMain/kotlin/com/tracks/core/spec/` |

## Changing a table

```bash
$EDITOR spec/zones.yaml
python3 spec/codegen.py          # regenerate all three languages
python3 spec/codegen.py --check  # CI: exit 1 if anything is stale
```

Then run the three suites — they all read `spec/fixtures/`:

```bash
docker exec backend python -m pytest tests/test_spec/ -q
docker exec frontend npx vitest run src/test/SportTaxonomy.test.js src/test/Zones.test.js
(cd mobile && ./gradlew :core:jvmTest)
```

## What is generated, and what is not

**Data only.** The evaluators that walk these tables are hand-written and
idiomatic in each language. String-templating three loops would be far worse to
maintain than the drift it prevents, and the loops are small enough to read in
one sitting.

What keeps them honest is `fixtures/`: one input/output corpus that pytest,
vitest, and the Kotlin check all run. An evaluator that diverges fails its own
suite.

This is not theoretical, and it is not rare. **Every language rounds ties
differently**, and the fixtures have now caught it three separate times:

| | ties go | `round(0.5)` |
|---|---|---|
| Python `round` | to even | `0` |
| Kotlin `round` | to even | `0` |
| Kotlin `roundToInt` | away from zero | `1` |
| JavaScript `Math.round` | up (toward +∞) | `1` |
| JavaScript `toFixed` | away from zero | — |

Zone boundaries land on exact halves constantly (two-decimal percentages times
round LTHR values), so Friel zone 1 ended at 144 bpm on a phone and 143 on the
server. A 0.5 m elevation gain read "0 m" in Kotlin and "1 m" in the browser.
`mobile/core` therefore carries an explicit `jsRound` for the JavaScript
semantics and a separate `fixed` for `toFixed`, because JavaScript genuinely
uses both and they disagree on negatives.

## Two kinds of fixture

The taxonomy corpus is **frozen** — its oracle was deleted when `sportUtils.js`
started delegating to the spec. The formatting corpus is **regenerable**: the JS
formatters are still the web app's own implementation, so they remain an
independent oracle. Rerun `make_format_fixtures.py` after changing one, and the
Kotlin tests say whether the port still agrees.

## The fixtures are frozen

`fixtures/sport_taxonomy.json` was baselined by running the **original**
`frontend/src/utils/sportUtils.js` — the implementation that was already
shipping — under Node. That was deliberate: the spec is a transcription of that
file, and transcription is exactly the kind of work that looks right and isn't.
Grading the spec against code that predates it is the only way the corpus proves
anything.

`sportUtils.js` now delegates to the spec, so `make_fixtures.py` refuses to run:
regenerating would grade the implementation against itself. **Extend the corpus
by hand** — add the case and work out the expected value yourself.

## Adding a new table

1. Write the YAML. Document *why*, especially anything order-dependent — the
   next reader has no other way to know that `training` must beat `yoga`.
2. Add generator functions to `codegen.py` and register them in `GENERATORS`.
   Emit only what executes; prose fields like `why:` stay in the YAML.
3. For Kotlin, put every string through `_kstr()`. JSON escaping handles quotes
   and backslashes but misses `$`, which Kotlin interpolates — a regex end
   anchor would otherwise produce an uncompilable generated file.
4. Point the existing consumers at the generated table and delete their
   literals. A spec that nothing reads is just a fourth copy.
5. Add tests in all three languages pinning the values that used to be
   literals, so "sourced from the spec" can't quietly become "sourced from the
   spec, but different".

## Deliberate non-unifications

Two heart-rate models coexist in `zones.yaml` and that is not an oversight.
`friel_lthr_*` is percentages of LTHR — the training-science model behind hrTSS
and load. `display_maxhr` is percentages of max HR and is what the
activity-detail histogram has always drawn; it needs no LTHR, so it works for
every activity.

They genuinely disagree. Collapsing them would change what the web app shows,
which is a product decision rather than a refactor. Both are in the spec, both
are named, and all three clients now at least disagree identically.
