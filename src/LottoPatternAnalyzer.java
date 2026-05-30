import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class LottoPatternAnalyzer {

    // --- LottoDraw 클래스 ---
    static class LottoDraw {
        int drawNo;
        int[] nums;

        public LottoDraw(int drawNo, int n1, int n2, int n3, int n4, int n5, int n6) {
            this.drawNo = drawNo;
            this.nums = new int[]{n1, n2, n3, n4, n5, n6};
            Arrays.sort(this.nums); // 데이터 정렬
        }

        public Set<Integer> getWinningNumbers() {
            return Arrays.stream(nums).boxed().collect(Collectors.toSet());
        }
    }

    // --- 상수 정의 ---
    static final Set<Integer> PRIMES = Set.of(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43);
    static final Set<Integer> MULTIPLES_OF_3 = new HashSet<>();
    static final Set<Integer> MULTIPLES_OF_5 = new HashSet<>();
    static final double BASELINE = 6.0 / 45.0; // 무작위 기대 적중률 (13.33%)
    // 학습창: sweep 진단(최근 52주 walk-forward)에서 상위10 적중이 가장 높았던 200주를 채택.
    // ※ 후보 간 차이는 사실상 노이즈 수준이며(무작위 대비 유의미한 우위 없음), 미래 적중을 보장하지 않음.
    static final int WF_TRAIN_WEEKS = 200; // walk-forward 가중치 학습 창 (라이브 추천 = 검증과 동일 방식)
    static final int RANK_DIST_WEEKS = 200; // 순위그룹 분포 산정 창 (표본 안정화)
    static final String[] RANK_GROUPS = {
            "1-5위", "6-10위", "11-15위", "16-20위", "21-25위",
            "26-30위", "31-35위", "36-40위", "41-45위"};

    static {
        for (int i = 3; i <= 45; i += 3) MULTIPLES_OF_3.add(i);
        for (int i = 5; i <= 45; i += 5) MULTIPLES_OF_5.add(i);
    }

    public static void main(String[] args) {
        // 1. 데이터 로드
        List<LottoDraw> drawHistory = loadDrawData("src/resources/lotto.csv");
        if (drawHistory.isEmpty()) {
            System.err.println("데이터를 로드할 수 없습니다.");
            return;
        }

        // 진단 모드: 학습창(주차) 탐색. (java ... LottoPatternAnalyzer sweep)
        if (args.length > 0 && args[0].equals("sweep")) {
            runWindowSweep(drawHistory);
            return;
        }
        // 진단 모드: 커스텀 패턴 발굴기(고정 풀). (java ... LottoPatternAnalyzer mine [W] [K])
        if (args.length > 0 && args[0].equals("mine")) {
            if (args.length > 1) CustomPatternMiner.W = Integer.parseInt(args[1]);
            if (args.length > 2) CustomPatternMiner.K = Integer.parseInt(args[2]);
            CustomPatternMiner.run(drawHistory);
            return;
        }
        // 진단 모드: 유전 프로그래밍 발굴 + 학습/검증 과적합 대비. (java ... LottoPatternAnalyzer gp)
        if (args.length > 0 && args[0].equals("gp")) {
            GPPatternMiner.run(drawHistory);
            return;
        }
        // 진단 모드: 연승 선택 전략. (java ... LottoPatternAnalyzer streak [S])
        if (args.length > 0 && args[0].equals("streak")) {
            if (args.length > 1) StreakPatternAnalyzer.S = Integer.parseInt(args[1]);
            StreakPatternAnalyzer.run(drawHistory);
            return;
        }

        // GP 진화 패턴을 커스텀 패턴 풀에 주입 (엔진/게임/PPT 자동 반영). 1회만 수행.
        GPPatternMiner.injectIntoEngine(drawHistory);

        LottoDraw latestDraw = drawHistory.get(drawHistory.size() - 1);
        int nextDrawNo = latestDraw.drawNo + 1;

        // 2. 사용할 모든 패턴 분석기 등록
        List<PatternAnalyzer> allAnalyzers = getAllAnalyzers();

        // 3. 패턴별 정확도 분석 및 출력 (최근 15회차 기준) — 반환된 정확도를 분석기 가중치로 사용
        Map<String, Double> accuracyMap = analyzePatternAccuracy(drawHistory, getAllAnalyzers(), 15);

        // 라이브 추천 가중치: 검증(walk-forward)과 동일하게 과거 데이터(최근 50주)로만 학습
        Map<String, Double> liveWeights = computeAccuracyWeights(drawHistory, WF_TRAIN_WEEKS);

        // 4. 과거 당첨 분포 통계 분석 및 출력
        // 라이브 추천과 동일한 가중 점수 기준으로, 최근 RANK_DIST_WEEKS주의 순위그룹 분포를 산정.
        Map<String, List<String>> rankDetails = new LinkedHashMap<>();
        Map<String, Double> rankDistribution =
                computeRankDistribution(drawHistory, RANK_DIST_WEEKS, liveWeights, rankDetails);
        printRankDistribution(rankDistribution, rankDetails, RANK_DIST_WEEKS);

        // 다음 회차 필출 및 제외 순위 분석 및 예상 개수 출력
        Optional<Map.Entry<String, Double>> maxEntry = rankDistribution.entrySet().stream().max(Map.Entry.comparingByValue());
        Optional<Map.Entry<String, Double>> minEntry = rankDistribution.entrySet().stream().min(Map.Entry.comparingByValue());
        String bestGroup = maxEntry.map(Map.Entry::getKey).orElse(null);
        String worstGroup = minEntry.map(Map.Entry::getKey).orElse(null);

        if (maxEntry.isPresent() && minEntry.isPresent()) {
            String bestRankGroup = maxEntry.get().getKey();
            double bestRankPercentage = maxEntry.get().getValue();
            double expectedCount = 6 * bestRankPercentage;

            String worstRankGroup = minEntry.get().getKey();
            double worstRankPercentage = minEntry.get().getValue();

            System.out.printf("\n[%d회차 당첨 번호 순위 그룹 확률 분석]\n", nextDrawNo);
            System.out.println("----------------------------------------------------------");
            System.out.printf("▶ 필출 확률 UP: '%s' 그룹 (최근 %d주간 당첨 확률: %.2f%%)\n", bestRankGroup, RANK_DIST_WEEKS, bestRankPercentage * 100);
            System.out.printf("  └ 예상: %d회차에 약 %.1f개 (1~3개) 번호가 포함될 수 있습니다.\n\n", nextDrawNo, expectedCount);
            System.out.printf("▶ 제외 확률 UP: '%s' 그룹 (최근 %d주간 당첨 확률: %.2f%%)\n", worstRankGroup, RANK_DIST_WEEKS, worstRankPercentage * 100);
            System.out.println("  └ 참고: 이 그룹의 번호들은 후순위로 고려하는 것이 좋습니다.");
            System.out.println("==========================================================");
        }


        // 5. 최신 회차 기준으로 최종 점수 집계 (정확도 가중 + 분석기별 정규화 합산)
        List<PatternAnalyzer> currentAnalyzers = analyzersFor(drawHistory);

        // 적용 전/후 효과 비교 (상위 추천에 실제 당첨 번호가 얼마나 몰리는지)
        evaluateImprovement(drawHistory, 15, accuracyMap);

        Map<Integer, List<String>> scoreTags = new HashMap<>();
        for (int n = 1; n <= 45; n++) scoreTags.put(n, new ArrayList<>());
        Map<Integer, Double> rawScores =
                computeFinalScores(currentAnalyzers, drawHistory, latestDraw, liveWeights, scoreTags);
        // 순위그룹 보정: 전체 점수 확정 후 2차 패스에서 적용 (기존 main의 순위 계산 버그 수정)
        final Map<Integer, Double> finalScores = applyRankBoost(rawScores, rankDistribution, bestGroup, worstGroup);

        // 6. 점수 기반으로 순위 매기고 결과 출력
        List<Integer> topNumbers = new ArrayList<>(finalScores.keySet());
        topNumbers.sort((a, b) -> finalScores.get(b).compareTo(finalScores.get(a)));

        System.out.println("==========================================================");
        System.out.printf("      %d회차 확률 기반 패턴 분석 로또 추천 시스템 (v7.3)\n", nextDrawNo);
        System.out.println("==========================================================");
        System.out.println("기준 회차: " + latestDraw.drawNo + "회");
        System.out.println("----------------------------------------------------------");
        System.out.println("순위 | 번호 |  종합 점수  | 기여한 패턴");
        System.out.println("----------------------------------------------------------");

        for (int i = 0; i < Math.min(45, topNumbers.size()); i++) {
            int num = topNumbers.get(i);
            double score = finalScores.get(num);
            String tags = String.join(", ", scoreTags.get(num));
            String rankLabel = (i == 0) ? "👑 1위" : String.format(" %2d위", i + 1);
            System.out.printf("%s | %2d번 | %10.2f점 | %s\n", rankLabel, num, score, tags);
        }

        // 7. 최종 게임 조합 생성
        //  - 후보: 45개 전체를 '순위그룹 당첨확률'에 비례해 가중 추출 (상위 25개 하드컷 제거)
        //  - 필터: 과거 당첨 이력의 중심 밀집구간(고확률)에서 임계값 자동 산출
        //  - 채택: 통과 조합 중 적합도(분포 정렬도) 상위 5개 (무작위 채택 제거)
        GameFilter filter = GameFilter.fromHistory(drawHistory);
        List<List<Integer>> finalGames =
                generateDistributionGames(topNumbers, finalScores, rankDistribution, filter, latestDraw, 5);

        System.out.println("----------------------------------------------------------");
        System.out.println("⚙️ [최종 5게임 — 분포 가중 추출 + 고확률 필터 + 세로열 2개↓ + 적합도 상위]");
        System.out.println(filter.describe());
        System.out.println("----------------------------------------------------------");
        char gameLabel = 'A';
        for (List<Integer> game : finalGames) {
            int sum = game.stream().mapToInt(Integer::intValue).sum();
            long oddCount = game.stream().filter(n -> n % 2 != 0).count();
            System.out.printf("[%c 게임] %s (합:%3d / 홀짝 %d:%d)\n",
                    gameLabel++,
                    game.stream().map(n -> String.format("%2d", n)).collect(Collectors.joining(", ")),
                    sum, oddCount, 6 - oddCount);
        }
        System.out.println("==========================================================");
    }
    
    private static List<PatternAnalyzer> getAllAnalyzers() {
        List<PatternAnalyzer> analyzers = new ArrayList<>();
        analyzers.add(new CarryOverPattern());
        analyzers.add(new NeighborPattern());
        analyzers.add(new TwinPattern());
        analyzers.add(new MirrorPattern());
        analyzers.add(new ExtinctBandPattern());
        analyzers.add(new DiagonalPattern());
        analyzers.add(new OffsetPattern());
        analyzers.add(new ConsecutiveNumberPattern());
        analyzers.add(new SameLastDigitCarryOverPattern());
        analyzers.add(new ACPattern());
        analyzers.add(new MissingPeriodPattern());
        analyzers.add(new ColdNumberPattern());
        analyzers.add(new TotalAppearancePattern());
        analyzers.add(new LastDigitPattern());
        analyzers.add(new RecentHotPattern());
        analyzers.add(new NumberBandAppearancePattern());
        analyzers.add(new GroupPattern("소수", PRIMES));
        analyzers.add(new GroupPattern("3의배수", MULTIPLES_OF_3));
        analyzers.add(new GroupPattern("5의배수", MULTIPLES_OF_5));
        analyzers.add(new CompanionPattern());
        analyzers.add(new GapCyclePattern());
        analyzers.add(new SumRangePattern());
        analyzers.add(new OddEvenRatioPattern());
        analyzers.add(new HighLowRatioPattern());
        analyzers.add(new CustomPatternAnalyzer());
        analyzers.add(new StreakPatternAnalyzer());
        analyzers.add(new ExclusionPattern());
        return analyzers;
    }

    // (분석기 이름, prefix 길이) → analyze() 완료 인스턴스 캐시.
    // 프로그램 내 모든 history는 동일한 전체 drawHistory의 subList(0, len)이므로
    // 길이(len)가 곧 prefix의 고유 식별자다. getScore는 인스턴스 상태를 변경하지 않으므로
    // 분석된 인스턴스를 호출 측이 공유해도 안전하다.
    private static final Map<String, PatternAnalyzer> ANALYZE_CACHE = new HashMap<>();

    // 주어진 history(prefix)에 대해 analyze()가 끝난 분석기 목록을 반환한다.
    // 캐시에 없으면 한 번만 analyze() 후 저장하여 동일 prefix 재계산을 제거한다.
    private static List<PatternAnalyzer> analyzersFor(List<LottoDraw> history) {
        int len = history.size();
        List<PatternAnalyzer> result = new ArrayList<>();
        for (PatternAnalyzer template : getAllAnalyzers()) {
            String key = template.getName() + ":" + len;
            PatternAnalyzer cached = ANALYZE_CACHE.get(key);
            if (cached == null) {
                template.analyze(history);
                cached = template;
                ANALYZE_CACHE.put(key, cached);
            }
            result.add(cached);
        }
        return result;
    }

    private static Map<String, Double> analyzePatternAccuracy(List<LottoDraw> drawHistory, List<PatternAnalyzer> analyzers, int recentWeeks) {
        Map<String, int[]> patternStats = new HashMap<>(); // K: patternName, V: [hits, predictions]

        int startIdx = Math.max(1, drawHistory.size() - recentWeeks);
        for (int i = startIdx; i < drawHistory.size(); i++) {
            List<LottoDraw> historyForAnalysis = drawHistory.subList(0, i);
            LottoDraw previousDraw = drawHistory.get(i - 1);
            Set<Integer> actualWinningNumbers = drawHistory.get(i).getWinningNumbers();

            for (PatternAnalyzer freshAnalyzer : analyzersFor(historyForAnalysis)) {
                patternStats.putIfAbsent(freshAnalyzer.getName(), new int[2]);
                int[] stats = patternStats.get(freshAnalyzer.getName());

                for (int n = 1; n <= 45; n++) {
                    double score = freshAnalyzer.getScore(n, previousDraw, historyForAnalysis);
                    if (score > 0) {
                        stats[1]++; // 예측 횟수 증가
                        if (actualWinningNumbers.contains(n)) {
                            stats[0]++; // 적중 횟수 증가
                        }
                    }
                }
            }
        }

        // 실제 가중치(효과크기 z) 기준으로 정렬 — 표시와 가중 로직을 일치시킴.
        List<Map.Entry<String, int[]>> sortedStats = new ArrayList<>(patternStats.entrySet());
        sortedStats.sort((a, b) -> Double.compare(
                effectZ(b.getValue()[0], b.getValue()[1]),
                effectZ(a.getValue()[0], a.getValue()[1])));

        System.out.println("==========================================================");
        System.out.printf ("      최근 %d회차 패턴별 효과크기(z·가중치) 순위\n", recentWeeks);
        System.out.println("==========================================================");
        System.out.println("순위 | 패턴 기법    |  z(가중치) | 정확도 | (적중/예측) | 반영");
        System.out.println("----------------------------------------------------------");
        int rank = 1;
        for (Map.Entry<String, int[]> entry : sortedStats) {
            int hits = entry.getValue()[0];
            int predictions = entry.getValue()[1];
            if (predictions == 0) continue; // 예측을 한 번도 안 한 패턴은 제외
            double accuracy = (double) hits / predictions;
            double z = effectZ(hits, predictions);
            String reflect = (z > 0) ? "○" : "×(제외)"; // z≤0(기준선 이하)은 가중치 0
            System.out.printf(" %2d위 | %-12s | %+8.2f | %5.2f%% | (%3d/%4d) | %s\n",
                    rank++, entry.getKey(), z, accuracy * 100, hits, predictions, reflect);
        }

        // 분석기 가중치로 재사용할 효과크기(z) 맵 반환
        Map<String, Double> weights = new HashMap<>();
        for (Map.Entry<String, int[]> e : patternStats.entrySet()) {
            weights.put(e.getKey(), effectZ(e.getValue()[0], e.getValue()[1]));
        }
        return weights;
    }

    private static PatternAnalyzer createNewAnalyzerInstance(PatternAnalyzer oldAnalyzer) {
        try {
            if (oldAnalyzer instanceof GroupPattern) {
                GroupPattern gp = (GroupPattern) oldAnalyzer;
                // GroupPattern은 생성자 인자가 있으므로 특별 처리
                return new GroupPattern(gp.getName(), gp.numberGroup);
            }
            return oldAnalyzer.getClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            // 간단한 처리를 위해 런타임 예외로 변환
            throw new RuntimeException("새로운 분석기 인스턴스 생성 실패: " + oldAnalyzer.getName(), e);
        }
    }


    private static List<LottoDraw> loadDrawData(String csvFile) {
        List<LottoDraw> drawList = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line = br.readLine(); // 헤더 라인 스킵
            while ((line = br.readLine()) != null) {
                String[] v = line.split(",");
                if (v.length < 7) continue;
                try {
                    drawList.add(new LottoDraw(
                            Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()),
                            Integer.parseInt(v[2].trim()), Integer.parseInt(v[3].trim()),
                            Integer.parseInt(v[4].trim()), Integer.parseInt(v[5].trim()),
                            Integer.parseInt(v[6].trim())
                    ));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("파일 읽기 오류: " + e.getMessage());
        }
        drawList.sort(Comparator.comparingInt(a -> a.drawNo));
        return drawList;
    }

    // ── 게임 특성 추출기 (정렬된 6개 번호 기준) ──
    private static int gameSum(List<Integer> g) { return g.stream().mapToInt(Integer::intValue).sum(); }
    private static int gameOdd(List<Integer> g) { return (int) g.stream().filter(n -> n % 2 != 0).count(); }
    private static int gameLow(List<Integer> g) { return (int) g.stream().filter(n -> n <= 22).count(); }
    private static int gameLastDigitDistinct(List<Integer> g) { return (int) g.stream().map(n -> n % 10).distinct().count(); }

    // 로또 용지(1~45를 7열×7행 행우선 배열: 1~7 / 8~14 / … / 43~45)에서
    // 같은 세로열에 든 번호의 최대 개수. 세로열 = (n-1) % 7.
    private static int maxLottoColumnCount(List<Integer> g) {
        int[] col = new int[7];
        for (int n : g) col[(n - 1) % 7]++;
        int mx = 0;
        for (int c : col) mx = Math.max(mx, c);
        return mx;
    }

    private static int gameConsecutivePairs(List<Integer> g) {
        List<Integer> s = new ArrayList<>(g); Collections.sort(s);
        int c = 0;
        for (int i = 0; i < s.size() - 1; i++) if (s.get(i + 1) == s.get(i) + 1) c++;
        return c;
    }

    // AC값(Arithmetic Complexity): 모든 쌍의 차이 중 서로 다른 값의 개수 - (n-1)
    private static int gameAC(List<Integer> g) {
        Set<Integer> diffs = new HashSet<>();
        for (int i = 0; i < g.size(); i++)
            for (int j = i + 1; j < g.size(); j++)
                diffs.add(Math.abs(g.get(i) - g.get(j)));
        return diffs.size() - (g.size() - 1);
    }

    // 과거 당첨 이력의 특성 분포에서 중심 밀집구간(백분위)으로 고확률 필터 임계값을 산출.
    static class GameFilter {
        int sumLo, sumHi, oddLo, oddHi, lowLo, lowHi, consLo, consHi, acLo, acHi, ldLo;

        // 정수 메트릭 값들의 [pLow, pHigh] 백분위 경계 (양끝 trim)
        private static int[] band(List<Integer> vals, double pLow, double pHigh) {
            List<Integer> v = new ArrayList<>(vals); Collections.sort(v);
            int n = v.size();
            int lo = v.get(Math.min(n - 1, (int) Math.floor(pLow * (n - 1))));
            int hi = v.get(Math.min(n - 1, (int) Math.ceil(pHigh * (n - 1))));
            return new int[]{lo, hi};
        }

        static GameFilter fromHistory(List<LottoDraw> history) {
            List<Integer> sums = new ArrayList<>(), odds = new ArrayList<>(), lows = new ArrayList<>(),
                    cons = new ArrayList<>(), acs = new ArrayList<>(), lds = new ArrayList<>();
            for (LottoDraw d : history) {
                List<Integer> g = new ArrayList<>();
                for (int x : d.nums) g.add(x);
                sums.add(gameSum(g)); odds.add(gameOdd(g)); lows.add(gameLow(g));
                cons.add(gameConsecutivePairs(g)); acs.add(gameAC(g)); lds.add(gameLastDigitDistinct(g));
            }
            GameFilter f = new GameFilter();
            // 합계·AC는 중심 80%(p10~p90), 이산 비율 메트릭은 중심 90%(p5~p95)로 채택
            int[] b;
            b = band(sums, 0.10, 0.90); f.sumLo = b[0]; f.sumHi = b[1];
            b = band(odds, 0.05, 0.95); f.oddLo = b[0]; f.oddHi = b[1];
            b = band(lows, 0.05, 0.95); f.lowLo = b[0]; f.lowHi = b[1];
            b = band(cons, 0.00, 0.90); f.consLo = b[0]; f.consHi = b[1]; // 연속쌍은 0부터 허용, 상단만 컷
            b = band(acs, 0.10, 1.00); f.acLo = b[0]; f.acHi = b[1];      // AC는 하한이 핵심(낮을수록 비당첨형)
            b = band(lds, 0.10, 1.00); f.ldLo = b[0];                     // 끝수 종류는 하한만
            return f;
        }

        boolean passes(List<Integer> g) {
            int s = gameSum(g), od = gameOdd(g), lo = gameLow(g),
                cn = gameConsecutivePairs(g), ac = gameAC(g), ld = gameLastDigitDistinct(g);
            return s >= sumLo && s <= sumHi
                    && od >= oddLo && od <= oddHi
                    && lo >= lowLo && lo <= lowHi
                    && cn >= consLo && cn <= consHi
                    && ac >= acLo && ac <= acHi
                    && ld >= ldLo;
        }

        String describe() {
            return String.format(
                "적용 필터(이력 기반 고확률): 합계 %d~%d · 홀수 %d~%d개 · 저수(≤22) %d~%d개 · 연속쌍 %d~%d · AC %d~%d · 끝수 %d종↑",
                sumLo, sumHi, oddLo, oddHi, lowLo, lowHi, consLo, consHi, acLo, acHi, ldLo);
        }
    }

    // 분포 비례 가중 추출 + 고확률 필터 + 적합도 상위 채택으로 게임을 생성한다.
    //  rankedNumbers: 종합점수 순위(index0=1위). finalScores: 제외수(veto) 판정용.
    //  rankDistribution: 순위그룹별 당첨확률. filter: 이력 기반 고확률 필터.
    private static List<List<Integer>> generateDistributionGames(
            List<Integer> rankedNumbers, Map<Integer, Double> finalScores,
            Map<String, Double> rankDistribution, GameFilter filter,
            LottoDraw latestDraw, int requiredGames) {

        // 1) 번호별 추출 가중치 = 소속 순위그룹의 당첨확률 (그룹당 5개가 공유). 제외수(veto)는 0.
        double[] weight = new double[46];
        for (int rank = 1; rank <= rankedNumbers.size(); rank++) {
            int n = rankedNumbers.get(rank - 1);
            String g = getRankGroupFromRank(rank);
            double p = (g == null) ? 0 : rankDistribution.getOrDefault(g, 0.0);
            boolean vetoed = finalScores.getOrDefault(n, 0.0) <= -1000;
            weight[n] = vetoed ? 0.0 : p; // 그룹 내 균등 추출이므로 그룹확률을 그대로 사용
        }

        // 직전 회차 연속쌍(이월) 회피 집합
        Set<List<Integer>> latestConsecutive = new HashSet<>();
        int[] ln = latestDraw.nums;
        for (int i = 0; i < ln.length - 1; i++)
            if (ln[i + 1] == ln[i] + 1) latestConsecutive.add(Arrays.asList(ln[i], ln[i + 1]));

        // 2) 가중 추출로 후보를 모아 필터 통과분만 수집 (충분히 모이거나 시도 소진까지)
        Random rnd = new Random();
        Map<List<Integer>, Double> candidates = new HashMap<>(); // 조합 → 적합도
        int attempts = 0;
        while (candidates.size() < 2000 && attempts < 300000) {
            attempts++;
            List<Integer> game = weightedSampleWithoutReplacement(weight, 6, rnd);
            if (game == null) break; // 추출 가능한 번호가 6개 미만
            Collections.sort(game);
            if (!filter.passes(game)) continue;
            if (containsConsecutivePair(game, latestConsecutive)) continue;
            if (maxLottoColumnCount(game) >= 3) continue; // 로또 용지 세로열 3개 이상 체크 회피
            candidates.putIfAbsent(game, fitness(game, rankedNumbers, rankDistribution));
        }

        // 3) 적합도 내림차순 정렬 후, 서로 2개 이상 다른 게임만 골라 상위 requiredGames개 채택
        List<List<Integer>> sorted = new ArrayList<>(candidates.keySet());
        sorted.sort((a, b) -> Double.compare(candidates.get(b), candidates.get(a)));
        List<List<Integer>> picked = new ArrayList<>();
        for (List<Integer> g : sorted) {
            if (picked.size() >= requiredGames) break;
            boolean tooSimilar = false;
            for (List<Integer> p : picked) if (overlapCount(g, p) >= 5) { tooSimilar = true; break; }
            if (!tooSimilar) picked.add(g);
        }
        // 다양성 제약으로 모자라면 적합도 순으로 채움
        for (List<Integer> g : sorted) {
            if (picked.size() >= requiredGames) break;
            if (!picked.contains(g)) picked.add(g);
        }
        return picked;
    }

    // 적합도 = 6개 번호가 속한 순위그룹 당첨확률의 합 (분포 정렬도가 높을수록 큼)
    private static double fitness(List<Integer> game, List<Integer> rankedNumbers, Map<String, Double> rankDistribution) {
        double f = 0;
        for (int n : game) {
            int rank = rankedNumbers.indexOf(n) + 1;
            String g = getRankGroupFromRank(rank);
            if (g != null) f += rankDistribution.getOrDefault(g, 0.0);
        }
        return f;
    }

    // weight[n]>0 인 번호들에서 가중치 비례·비복원으로 k개 추출. 가능 번호가 k개 미만이면 null.
    private static List<Integer> weightedSampleWithoutReplacement(double[] weight, int k, Random rnd) {
        List<Integer> remaining = new ArrayList<>();
        for (int n = 1; n <= 45; n++) if (weight[n] > 0) remaining.add(n);
        if (remaining.size() < k) return null;
        List<Integer> picked = new ArrayList<>();
        for (int s = 0; s < k; s++) {
            double total = 0;
            for (int n : remaining) total += weight[n];
            double r = rnd.nextDouble() * total, acc = 0;
            int chosen = remaining.get(remaining.size() - 1);
            for (int n : remaining) { acc += weight[n]; if (acc >= r) { chosen = n; break; } }
            picked.add(chosen);
            remaining.remove((Integer) chosen);
        }
        return picked;
    }

    private static int overlapCount(List<Integer> a, List<Integer> b) {
        int c = 0;
        for (int n : a) if (b.contains(n)) c++;
        return c;
    }

    private static boolean containsConsecutivePair(List<Integer> candidate, Set<List<Integer>> pairs) {
        for (List<Integer> pair : pairs)
            if (candidate.contains(pair.get(0)) && candidate.contains(pair.get(1))) return true;
        return false;
    }

    // 최근 recentWeeks주 동안 가중 점수(라이브 추천과 동일)로 1~45 순위를 매기고,
    // 실제 당첨 번호가 어느 순위그룹에 떨어졌는지 분포(그룹별 비율)를 반환한다.
    // weights: 각 주 점수 산정에 적용할 분석기 가중치(라이브=liveWeights, 검증=해당 주까지의 walk-forward 가중치).
    //          이 함수는 weights를 모든 주에 그대로 적용하므로, 호출 측에서 history 범위를 통해 look-ahead를 통제한다.
    // detailsOut: null이 아니면 그룹별 당첨 내역 문자열을 채워 출력에 사용.
    private static Map<String, Double> computeRankDistribution(
            List<LottoDraw> history, int recentWeeks,
            Map<String, Double> weights, Map<String, List<String>> detailsOut) {

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String g : RANK_GROUPS) counts.put(g, 0);
        if (detailsOut != null) for (String g : RANK_GROUPS) detailsOut.put(g, new ArrayList<>());

        int total = 0;
        int startIdx = Math.max(1, history.size() - recentWeeks);
        for (int i = startIdx; i < history.size(); i++) {
            List<LottoDraw> hist = history.subList(0, i);
            LottoDraw prev = history.get(i - 1);
            LottoDraw cur = history.get(i);

            List<PatternAnalyzer> fresh = analyzersFor(hist);
            Map<Integer, Double> scores = computeFinalScores(fresh, hist, prev, weights, null);
            List<Integer> ranked = rankByScore(scores);

            for (int win : cur.getWinningNumbers()) {
                int rank = ranked.indexOf(win) + 1;
                String g = getRankGroupFromRank(rank);
                if (g == null) continue;
                counts.put(g, counts.get(g) + 1);
                if (detailsOut != null) detailsOut.get(g).add(String.format("%d,%d(%d위)", cur.drawNo, win, rank));
                total++;
            }
        }

        Map<String, Double> dist = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            dist.put(e.getKey(), total > 0 ? (double) e.getValue() / total : 0);
        }
        return dist;
    }

    private static void printRankDistribution(Map<String, Double> dist,
            Map<String, List<String>> details, int recentWeeks) {
        int totalWinningNumbers = details.values().stream().mapToInt(List::size).sum();

        System.out.println("\n==========================================================");
        System.out.printf("      최근 %d회차 추천 순위별 당첨 번호 분포 통계\n", recentWeeks);
        System.out.println("==========================================================");
        System.out.printf("총 당첨 번호 수: %d개 (최근 %d주)\n", totalWinningNumbers, recentWeeks);
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.println("순위 그룹   | 당첨 횟수 | 비율   | 당첨 내역 (회차,번호(예측순위))");
        System.out.println("------------------------------------------------------------------------------------------");

        for (String group : RANK_GROUPS) {
            int count = details.get(group).size();
            double percentage = dist.getOrDefault(group, 0.0);
            String detailsStr = String.join(", ", details.get(group));
            System.out.printf("%-10s | %8d | %6.2f%% | %s\n", group, count, percentage * 100, detailsStr);
        }
        System.out.println("==========================================================================================");
    }
    
    // 분석기 효과크기(z-score): 분석기가 양수로 지목한 번호가 기준선(6/45)보다
    // 유의하게 더 자주 당첨됐는지를 표본 수까지 반영해 측정한다.
    //   z = (적중 − 예측×p0) / √(예측×p0×(1−p0)),  p0 = BASELINE
    // 적중률이 기준선보다 높을수록 ↑, 표본(예측)이 클수록 ↑ → 소표본 노이즈를 자동 디스카운트.
    private static double effectZ(int hits, int predictions) {
        if (predictions <= 0) return 0.0;
        double var = predictions * BASELINE * (1 - BASELINE);
        if (var <= 0) return 0.0;
        return (hits - predictions * BASELINE) / Math.sqrt(var);
    }

    // 주어진 history만으로 분석기별 효과크기(z, 가중치)를 계산. walk-forward 검증에서 매 주 재학습용.
    private static Map<String, Double> computeAccuracyWeights(List<LottoDraw> history, int recentWeeks) {
        Map<String, int[]> stats = new HashMap<>();
        int startIdx = Math.max(1, history.size() - recentWeeks);
        for (int i = startIdx; i < history.size(); i++) {
            List<LottoDraw> h = history.subList(0, i);
            LottoDraw prev = history.get(i - 1);
            Set<Integer> winners = history.get(i).getWinningNumbers();
            for (PatternAnalyzer fa : analyzersFor(h)) {
                stats.putIfAbsent(fa.getName(), new int[2]);
                int[] s = stats.get(fa.getName());
                for (int n = 1; n <= 45; n++) {
                    if (fa.getScore(n, prev, h) > 0) { s[1]++; if (winners.contains(n)) s[0]++; }
                }
            }
        }
        Map<String, Double> acc = new HashMap<>();
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            acc.put(e.getKey(), effectZ(e.getValue()[0], e.getValue()[1]));
        }
        return acc;
    }

    // 정확도 가중 + 분석기별 정규화 합산.
    // accuracyWeights == null 이면 레거시(무가중 단순 합산) 동작 — 효과 비교용.
    private static Map<Integer, Double> computeFinalScores(List<PatternAnalyzer> analyzers,
            List<LottoDraw> history, LottoDraw previousDraw,
            Map<String, Double> accuracyWeights, Map<Integer, List<String>> tagsOut) {

        Map<Integer, Double> scores = new HashMap<>();
        for (int n = 1; n <= 45; n++) scores.put(n, 0.0);

        for (PatternAnalyzer analyzer : analyzers) {
            double[] raw = new double[46]; // index 1..45 사용
            for (int n = 1; n <= 45; n++) raw[n] = analyzer.getScore(n, previousDraw, history);

            if (accuracyWeights == null) { // 레거시: 무가중 단순 합산
                for (int n = 1; n <= 45; n++) scores.put(n, scores.get(n) + raw[n]);
                continue;
            }

            // 강한 음수(제외수 veto, -10000)는 가중치와 무관하게 그대로 반영
            for (int n = 1; n <= 45; n++) {
                if (raw[n] <= -1000) scores.put(n, scores.get(n) + raw[n]);
            }

            // 가중치 = 효과크기 z (computeAccuracyWeights/analyzePatternAccuracy가 산출).
            // z ≤ 0 (기준선 이하)인 분석기는 변별력이 없으므로 제외.
            double weight = Math.max(0.0, accuracyWeights.getOrDefault(analyzer.getName(), 0.0));
            if (weight <= 0) continue;

            // veto를 제외한 점수를 min-max 정규화 (분석기별 척도 차이 제거)
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int n = 1; n <= 45; n++) {
                if (raw[n] <= -1000) continue;
                if (raw[n] < min) min = raw[n];
                if (raw[n] > max) max = raw[n];
            }
            if (max <= min) continue; // 모든 번호 동일 점수 → 변별 정보 없음

            for (int n = 1; n <= 45; n++) {
                if (raw[n] <= -1000) continue;
                double norm = (raw[n] - min) / (max - min); // 0..1
                scores.put(n, scores.get(n) + weight * norm);
                if (raw[n] > 0 && tagsOut != null) tagsOut.get(n).add(analyzer.getName());
            }
        }
        return scores;
    }

    // 순위그룹 보정: 과거 적중률이 가장 높았던 그룹은 가산, 가장 낮았던 그룹은 감산.
    private static Map<Integer, Double> applyRankBoost(Map<Integer, Double> scores,
            Map<String, Double> rankDistribution, String bestGroup, String worstGroup) {
        if (rankDistribution == null) return scores;
        List<Integer> ranked = rankByScore(scores);
        Map<Integer, Double> out = new HashMap<>(scores);
        for (int idx = 0; idx < ranked.size(); idx++) {
            int n = ranked.get(idx);
            String g = getRankGroupFromRank(idx + 1);
            if (g == null || !rankDistribution.containsKey(g)) continue;
            double factor = rankDistribution.get(g);
            if (g.equals(bestGroup)) out.put(n, out.get(n) * (1 + factor * 0.2));
            else if (g.equals(worstGroup)) out.put(n, out.get(n) * (1 - factor * 0.1));
        }
        return out;
    }

    // 분포에서 최고/최저 비율 그룹을 [best, worst]로 반환 (둘 다 없으면 null).
    private static String[] bestWorstGroup(Map<String, Double> dist) {
        String best = dist.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        String worst = dist.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new String[]{best, worst};
    }

    private static List<Integer> rankByScore(Map<Integer, Double> scores) {
        List<Integer> r = new ArrayList<>(scores.keySet());
        r.sort((a, b) -> scores.get(b).compareTo(scores.get(a)));
        return r;
    }

    // 상위 6위/10위 추천 안에 실제 당첨 번호가 몇 개 들어왔는지 반환 [top6, top10]
    private static int[] countTopHits(Map<Integer, Double> scores, Set<Integer> winners) {
        List<Integer> ranked = rankByScore(scores);
        int h6 = 0, h10 = 0;
        for (int t = 0; t < 10; t++) {
            if (winners.contains(ranked.get(t))) { if (t < 6) h6++; h10++; }
        }
        return new int[]{h6, h10};
    }

    // 적용 전/후 효과 비교: 최근 N주 동안 상위 추천에 실제 당첨이 얼마나 몰리는지 측정.
    private static void evaluateImprovement(List<LottoDraw> drawHistory, int recentWeeks,
            Map<String, Double> accuracyWeights) {

        final int trainWeeks = WF_TRAIN_WEEKS; // walk-forward 재학습 창 (라이브 추천과 동일)
        int startIdx = Math.max(1, drawHistory.size() - recentWeeks);
        String[] labels = {"기존(무가중 합산)", "신규(in-sample 가중)", "walk-forward+순위보정", "신규(walk-forward)"};
        double[] sum6 = new double[4];
        double[] sum10 = new double[4];
        int weeks = 0;

        for (int i = startIdx; i < drawHistory.size(); i++) {
            List<LottoDraw> hist = drawHistory.subList(0, i);
            LottoDraw prev = drawHistory.get(i - 1);
            Set<Integer> winners = drawHistory.get(i).getWinningNumbers();

            List<PatternAnalyzer> fresh = analyzersFor(hist);

            // walk-forward: 이 주 이전 데이터만으로 가중치를 재학습 (look-ahead 없음)
            Map<String, Double> wfWeights = computeAccuracyWeights(hist, trainWeeks);

            Map<Integer, Double> sA = computeFinalScores(fresh, hist, prev, null, null);
            Map<Integer, Double> sB = computeFinalScores(fresh, hist, prev, accuracyWeights, null);
            // 순위그룹 보정도 walk-forward: 분포/best·worst를 이 주 이전 데이터로만 산정 (look-ahead 없음)
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, wfWeights, null);
            String[] bw = bestWorstGroup(wfDist);
            Map<Integer, Double> sC = applyRankBoost(computeFinalScores(fresh, hist, prev, wfWeights, null), wfDist, bw[0], bw[1]);
            Map<Integer, Double> sD = computeFinalScores(fresh, hist, prev, wfWeights, null);

            int[] cA = countTopHits(sA, winners);
            int[] cB = countTopHits(sB, winners);
            int[] cC = countTopHits(sC, winners);
            int[] cD = countTopHits(sD, winners);
            sum6[0] += cA[0]; sum10[0] += cA[1];
            sum6[1] += cB[0]; sum10[1] += cB[1];
            sum6[2] += cC[0]; sum10[2] += cC[1];
            sum6[3] += cD[0]; sum10[3] += cD[1];
            weeks++;
        }

        if (weeks == 0) return;
        System.out.println("\n==========================================================");
        System.out.printf ("      추천 방식 개선 효과 비교 (최근 %d주 백테스트)\n", weeks);
        System.out.println("==========================================================");
        System.out.printf ("기대값(무작위): 상위6 평균 %.2f개 / 상위10 평균 %.2f개\n", 6 * 6.0 / 45, 10 * 6.0 / 45);
        System.out.println("----------------------------------------------------------");
        System.out.println("방식                        | 상위6 적중 | 상위10 적중");
        System.out.println("----------------------------------------------------------");
        for (int k = 0; k < 4; k++) {
            System.out.printf("%-24s | %6.2f개 | %7.2f개\n", labels[k], sum6[k] / weeks, sum10[k] / weeks);
        }
        System.out.println("==========================================================");
    }

    // 상위 n위 추천 안에 실제 당첨 번호가 몇 개 들어왔는지 반환.
    private static int countTopNHits(Map<Integer, Double> scores, Set<Integer> winners, int n) {
        List<Integer> ranked = rankByScore(scores);
        int h = 0;
        for (int t = 0; t < n && t < ranked.size(); t++) if (winners.contains(ranked.get(t))) h++;
        return h;
    }

    // 진단 모드: 여러 학습창(window)을 walk-forward로 평가해, 최근 회차에서 추천 상위권에
    // 실제 당첨 번호가 가장 많이 들어오는 창을 찾는다. 라이브 추천과 동일한 경로
    // (walk-forward 가중치 + 순위그룹 보정)로 매 주 재학습하므로 look-ahead가 없다.
    private static void runWindowSweep(List<LottoDraw> drawHistory) {
        int[] windows = {10, 15, 20, 25, 30, 40, 52, 75, 100, 150, 200};
        int evalWeeks = 52; // 최근 52주(약 1년)에 대해 검증
        int startIdx = Math.max(1, drawHistory.size() - evalWeeks);
        int weeksUsed = drawHistory.size() - startIdx;
        int latestNo = drawHistory.get(drawHistory.size() - 1).drawNo;

        System.out.println("==========================================================");
        System.out.printf ("   학습창(주차) 탐색 — 최근 %d주 walk-forward 백테스트\n", weeksUsed);
        System.out.println("==========================================================");
        System.out.printf ("기대값(무작위): 상위6 %.2f개 / 상위10 %.2f개 / 상위25 %.2f개\n",
                6 * BASELINE, 10 * BASELINE, 25 * BASELINE);
        System.out.println("----------------------------------------------------------");
        System.out.printf ("학습창 | 상위6적중 | 상위10적중 | 상위25적중 | %d적중(상10)\n", latestNo);
        System.out.println("----------------------------------------------------------");

        int bestW = windows[0];
        double bestMetric = -1;
        for (int W : windows) {
            double s6 = 0, s10 = 0, s25 = 0;
            int weeks = 0, lastHit10 = 0;
            for (int i = startIdx; i < drawHistory.size(); i++) {
                List<LottoDraw> hist = drawHistory.subList(0, i);
                LottoDraw prev = drawHistory.get(i - 1);
                Set<Integer> winners = drawHistory.get(i).getWinningNumbers();

                List<PatternAnalyzer> fresh = analyzersFor(hist);
                Map<String, Double> wfWeights = computeAccuracyWeights(hist, W);
                Map<String, Double> wfDist = computeRankDistribution(hist, W, wfWeights, null);
                String[] bw = bestWorstGroup(wfDist);
                Map<Integer, Double> scores = applyRankBoost(
                        computeFinalScores(fresh, hist, prev, wfWeights, null), wfDist, bw[0], bw[1]);

                int[] c = countTopHits(scores, winners); // [top6, top10]
                s6 += c[0]; s10 += c[1];
                s25 += countTopNHits(scores, winners, 25);
                weeks++;
                if (i == drawHistory.size() - 1) lastHit10 = c[1];
            }
            double avg6 = s6 / weeks, avg10 = s10 / weeks, avg25 = s25 / weeks;
            System.out.printf("%4d주 | %7.2f개 | %8.2f개 | %8.2f개 | %d개\n",
                    W, avg6, avg10, avg25, lastHit10);
            double metric = avg10 * 100 + avg6; // 상위10 우선, 동률 시 상위6
            if (metric > bestMetric) { bestMetric = metric; bestW = W; }
        }
        System.out.println("==========================================================");
        System.out.printf ("▶ 추천 학습창: %d주 (최근 %d주 평균 상위10 적중 최대)\n", bestW, weeksUsed);
        System.out.println("  ※ 주의: 과거에 가장 잘 맞은 값을 고른 것일 뿐, 미래 적중을 보장하지 않습니다(과적합).");
        System.out.println("==========================================================");
    }

    private static String getRankGroupFromRank(int rank) {
        if (rank >= 1 && rank <= 5) return "1-5위";
        if (rank >= 6 && rank <= 10) return "6-10위";
        if (rank >= 11 && rank <= 15) return "11-15위";
        if (rank >= 16 && rank <= 20) return "16-20위";
        if (rank >= 21 && rank <= 25) return "21-25위";
        if (rank >= 26 && rank <= 30) return "26-30위";
        if (rank >= 31 && rank <= 35) return "31-35위";
        if (rank >= 36 && rank <= 40) return "36-40위";
        if (rank >= 41 && rank <= 45) return "41-45위";
        return null;
    }
}
