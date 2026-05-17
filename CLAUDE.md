# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Java project that analyzes Korean Lotto (로또 6/45) draw history with an ensemble of statistical pattern detectors and prints a ranked recommendation plus 5 filtered game combinations. All console output is in Korean.

## Build & run

There is no Maven/Gradle/build script — this is an IntelliJ IDEA Java module (`mskim.iml`) using the inherited project JDK and a flat `src/` source root in the default (unnamed) package.

The intended run flow is **IntelliJ → run `LottoPatternAnalyzer.main`**. `Main.java` is just the IDE-generated "Hello and welcome!" template; ignore it.

CLI equivalent (must be invoked from the **project root** — see CSV path note below):

```bash
# compile
javac -d out/production/mskim src/*.java
# run (no args)
java -cp out/production/mskim LottoPatternAnalyzer
```

There are no tests, no linter config, and no CI.

## Critical data-path constraint

`LottoPatternAnalyzer.loadDrawData` hard-codes the relative path `src/resources/lotto.csv`. The program **must be launched with the project root as the working directory** or it will print "데이터를 로드할 수 없습니다." and exit. If you change the launch CWD or move the CSV, update the literal in `LottoPatternAnalyzer.main` (line ~37).

`lotto.csv` format: header row `회차,번호1..6,번호7`, then one row per draw with columns `[drawNo, n1..n6, bonus]`. The bonus column is read but discarded; only the 6 main numbers feed the analyzers. The loader silently skips any row with fewer than 7 columns or non-integer fields, then sorts by `drawNo` ascending.

## Architecture

### Plug-in pattern model

Every analysis technique implements `PatternAnalyzer` (`src/PatternAnalyzer.java`):

- `analyze(drawHistory)` — one-shot precomputation over the full history.
- `getScore(number, latestDraw, drawHistory)` — score 1..45 individually; positive = boost, `0` = abstain, **negative = veto** (e.g. `ExclusionPattern` returns `-10000` to suppress 3-in-a-row repeats — this dwarfs other contributions and effectively eliminates the number).
- `getName()` — Korean tag shown in output.

Per-number final score = sum across all analyzers, then a small multiplicative adjustment (±10–20%) based on which rank-group the number fell into during the recent-15-week distribution sweep.

### Registration is manual and load-bearing

`LottoPatternAnalyzer.getAllAnalyzers()` is the single source of truth. To add a new pattern you must (1) implement `PatternAnalyzer`, (2) add it to this list, and (3) ensure it has a **no-arg constructor** — `createNewAnalyzerInstance` uses reflection (`getDeclaredConstructor().newInstance()`) to build fresh instances per backtest week so prior `analyze()` state doesn't leak. `GroupPattern` is the only special case: it's instantiated with `(name, Set<Integer>)`, and its branch in `createNewAnalyzerInstance` reads the `numberGroup` field directly (which is why `GroupPattern.numberGroup` is `public`).

Patterns currently registered include the 20 originals (이월수, 이웃수, 쌍수, 거울수, 멸구간, 대각선, 오프셋, 연번, 끝수이월, AC, 미출현, 장기미출현, 누적빈도, 끝수통계, 최근강세, 번호대빈도, 소수/3의배수/5의배수, 제외수) plus 5 added in v7.3: **궁합수** (`CompanionPattern` — pair-cooccurrence affinity to latest draw, the strongest new signal), **출현주기** (`GapCyclePattern` — per-number average gap regression), **합계균형** (`SumRangePattern` — distance from most-frequent sum window's mean), **홀짝균형** (`OddEvenRatioPattern`), **고저균형** (`HighLowRatioPattern`).

### Pipeline (single `main`)

1. Load CSV → list of `LottoDraw`.
2. **Backtest** each analyzer independently over the most recent 15 weeks (`analyzePatternAccuracy`) and print per-pattern hit-rate. Each week rebuilds analyzers from scratch via reflection.
3. **Rank-group distribution** (`analyzeRankDistribution`) — for each of the last 15 weeks, score all 45 numbers using the full ensemble, bucket actual winning numbers into rank groups (`1-5위`, `6-10위`, … `41-45위`), and emit per-group hit percentages.
4. **Final scoring pass** on the full history, applying the rank-group factor: numbers in the best-performing group are boosted by `(1 + p × 0.2)`, worst-performing group penalized by `(1 - p × 0.1)`.
5. Print numbers 1..45 ranked with contributing-pattern tags.
6. **Game generation** (`generateValidGames`) — repeatedly shuffle the top-25 pool and pick 6 until 5 distinct games satisfy: sum ∈ [100, 180], odd count ∈ [2, 4], **low count (≤22) ∈ [2, 4]**, **distinct last-digits ≥ 4**, ≥1 prime, ≥1 multiple of 3, and no consecutive pair that already appeared consecutively in the latest draw (`containsConsecutiveCarryOver`). Capped at 100k attempts; can produce fewer than 5 games if constraints are too tight.

### Conventions

- Code comments and all printed strings are Korean. Keep them Korean when editing — the output banner versioning (`v7.2`) is also Korean-facing.
- The 15-week window is repeated as a magic number in three places (`main`, `analyzePatternAccuracy` default arg, `analyzeRankDistribution` call). Change them together.
- The `PRIMES` / `MULTIPLES_OF_3` / `MULTIPLES_OF_5` sets live in `LottoPatternAnalyzer` and are reused both by `GroupPattern` instances and by the game-validity filter.
