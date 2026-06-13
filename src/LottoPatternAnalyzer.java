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
    static final int GAP_REPEAT_WINDOW = 195; // 재출현 간격(차이수) 반복 분석 창
    // 100회 주기 보정: 다음 회차의 300회 이내 형제 회차(D-100·D-200·D-300) 당첨번호에 든 번호를
    // '주기 하강 스왑'으로 우선순위 상향. ※ 재출현율(35.3%)이 무작위 기대(34.9%)와 동일 → 효과는 백테스트로 확인.
    static final int CYCLE_LOOKBACK = 300;   // 주기 조회 범위(회)
    // 패턴 판단 데이터 창. 0=전체 history. >0 이면 최신 N회차만으로 모든 패턴을 analyze/getScore.
    // (예: 4 ≈ 최근 한 달) CLI 인자 win=N 으로 설정.
    static int ANALYZE_WINDOW = 0;
    // 게임 추출 시 순위 tier별 가중 배수: 상위권(1~15)·중위권(16~30)·하위권(31~45). 1.0=중립.
    static double TIER_TOP = 1.0, TIER_MID = 1.0, TIER_BOT = 1.0;
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

        // 패턴 판단 데이터 창 설정: win=N (모든 패턴을 최신 N회차만으로 판단). 0=전체.
        for (String a : args) if (a.startsWith("win=")) ANALYZE_WINDOW = Integer.parseInt(a.substring(4));

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

        // 진단 모드: 대상 회차들을 직전 데이터로 예측해 '어떤 패턴이 당첨번호를 잘 잡는지' 측정.
        //   (java ... LottoPatternAnalyzer patdiag [lo] [hi])  기본 1216~1226
        if (args.length > 0 && args[0].equals("patdiag")) {
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : 1216;
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : 1226;
            runPatternDiagnostic(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 당첨번호가 추천순위 특정 구간([15~25],[35~45])에 들어가는지 확인.
        //   (java ... LottoPatternAnalyzer bands [lo] [hi])  기본 1216~1225
        if (args.length > 0 && args[0].equals("bands")) {
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : 1216;
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : 1225;
            runBandCheck(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 당첨번호가 상위권(1~15)/중위권(16~30)/하위권(31~45)에서 얼마나 나오는지.
        //   (java ... LottoPatternAnalyzer tiers [lo] [hi])  기본 1216~1226
        if (args.length > 0 && args[0].equals("tiers")) {
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : 1216;
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : 1226;
            runTierCheck(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 3순위 단위(1-3,4-6,…,43-45 = 15구간)로 당첨번호 분포.
        //   (java ... LottoPatternAnalyzer tiers3 [lo] [hi])  기본 1216~1226
        if (args.length > 0 && args[0].equals("tiers3")) {
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : 1216;
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : 1226;
            runFineTierCheck(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 45의 약수(3·5·9·15·45)를 구간 크기로 나눠, 모든 회차에 ≥1 당첨이 나오는 '필출' 구간 탐색.
        //   (java ... LottoPatternAnalyzer divisors [lo] [hi])  기본 1216~1226
        if (args.length > 0 && args[0].equals("divisors")) {
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : 1216;
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : 1226;
            runDivisorCheck(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 목표 회차의 5게임을 tier 가중(균등 vs U자) 별로 여러 번 생성해 평균 적중 비교.
        //   (java ... LottoPatternAnalyzer gamesim [target] [trials])  기본 1226, 200회
        if (args.length > 0 && args[0].equals("gamesim")) {
            int target = args.length > 1 ? Integer.parseInt(args[1]) : 1226;
            int trials = args.length > 2 ? Integer.parseInt(args[2]) : 200;
            runGameSim(drawHistory, target, trials);
            return;
        }

        // 진단 모드: 순위별(1~45위) 추천 번호가 당첨으로 재출현한 횟수·회차 + 재출현 간격(차이수) 반복 분석.
        //   (java ... LottoPatternAnalyzer rankhits [lo] [hi])  기본 최근 GAP_REPEAT_WINDOW회
        if (args.length > 0 && args[0].equals("rankhits")) {
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : drawHistory.get(drawHistory.size() - 1).drawNo;
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : hi - GAP_REPEAT_WINDOW + 1;
            GPPatternMiner.injectIntoEngine(drawHistory); // 라이브 게임과 동일한 패턴 풀로 순위 산정(일관성)
            runRankHits(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 커스텀 패턴(고정 풀, GP 미주입)만으로 lo~hi 각 회차를 직전 데이터로 예측 → 당첨 적중 통계.
        //   (java ... LottoPatternAnalyzer customdiag [lo] [hi])  기본 최근 10회
        if (args.length > 0 && args[0].equals("customdiag")) {
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : drawHistory.get(drawHistory.size() - 1).drawNo;
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : hi - 9;
            runCustomDiag(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 커스텀 변환 패턴(고정 풀)을 2개씩 조합해 lo~hi 구간 당첨 적중 합이 가장 큰 조합 탐색.
        //   (java ... LottoPatternAnalyzer custompair [lo] [hi])  기본 최근 10회
        if (args.length > 0 && args[0].equals("custompair")) {
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : drawHistory.get(drawHistory.size() - 1).drawNo;
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : hi - 9;
            runCustomPair(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 커스텀 변환 패턴(고정 풀) 각각을 lo~hi 구간 단독 성적으로 정렬해 전체 표시.
        //   (java ... LottoPatternAnalyzer customrank [lo] [hi])  기본 최근 10회
        if (args.length > 0 && args[0].equals("customrank")) {
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : drawHistory.get(drawHistory.size() - 1).drawNo;
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : hi - 9;
            runCustomRank(drawHistory, lo, hi);
            return;
        }

        // 진단 모드: 대각합 전체 / 끝수 곱집합 각각의 '회차 내 생성 중복수'별 적중 확률.
        //   (java ... LottoPatternAnalyzer customdup [lo] [hi])  기본 최근 10회
        if (args.length > 0 && args[0].equals("customdup")) {
            int hi = args.length > 2 ? Integer.parseInt(args[2]) : drawHistory.get(drawHistory.size() - 1).drawNo;
            int lo = args.length > 1 ? Integer.parseInt(args[1]) : hi - 9;
            runCustomDup(drawHistory, lo, hi);
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
        // 100회 주기 보정: 다음 회차의 300회 이내 형제 회차에 든 번호를 표시
        Set<Integer> cycleSet = cycleNumbers(nextDrawNo, drawHistory);
        for (int n : cycleSet) if (scoreTags.containsKey(n)) scoreTags.get(n).add("주기300");
        // 재출현 간격(차이수) 5회 이상 반복 번호: 게임 생성 시 '무조건 포함' 대상
        Set<Integer> gapMustSet = gapRepeatNumbers(drawHistory, GAP_REPEAT_WINDOW);
        for (int n : gapMustSet) if (scoreTags.containsKey(n)) scoreTags.get(n).add("간격5회");
        if (!gapMustSet.isEmpty())
            System.out.println("※ 간격5회 필수 포함 번호(재출현 간격 5회↑ 반복): " + new TreeSet<>(gapMustSet));

        // 6. 점수순 정렬 → 인접 단일 비교 스왑(주기 포함 번호가 바로 위 미포함 번호를 한 칸씩 추월)
        List<Integer> topNumbers = new ArrayList<>(finalScores.keySet());
        topNumbers.sort((a, b) -> finalScores.get(b).compareTo(finalScores.get(a)));
        topNumbers = applyCycleAdjacentSwap(topNumbers, cycleSet);

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
                generateDistributionGames(topNumbers, finalScores, rankDistribution, filter, latestDraw, 5, cycleSet, gapMustSet);

        System.out.println("----------------------------------------------------------");
        System.out.println("⚙️ [최종 5게임 — 분포 가중 추출 + 고확률 필터 + 세로열 2개↓ + 주기300 필수 + 간격5회 필수 + 적합도 상위]");
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
    // 패턴 판단에 쓸 history 구간. ANALYZE_WINDOW>0 이면 최신 N회차만 반환.
    private static List<LottoDraw> win(List<LottoDraw> h) {
        if (ANALYZE_WINDOW <= 0 || h.size() <= ANALYZE_WINDOW) return h;
        return h.subList(h.size() - ANALYZE_WINDOW, h.size());
    }

    private static List<PatternAnalyzer> analyzersFor(List<LottoDraw> history) {
        List<LottoDraw> hw = win(history);
        int len = history.size();
        List<PatternAnalyzer> result = new ArrayList<>();
        for (PatternAnalyzer template : getAllAnalyzers()) {
            String key = template.getName() + ":" + len + ":" + ANALYZE_WINDOW;
            PatternAnalyzer cached = ANALYZE_CACHE.get(key);
            if (cached == null) {
                template.analyze(hw);
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
                    double score = freshAnalyzer.getScore(n, previousDraw, win(historyForAnalysis));
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
            LottoDraw latestDraw, int requiredGames, Set<Integer> cycleSet, Set<Integer> mustInclude) {

        // 1) 번호별 추출 가중치 = 소속 순위그룹의 당첨확률 (그룹당 5개가 공유). 제외수(veto)는 0.
        //    순위는 이미 주기 스왑이 반영된 순서이므로, 주기 포함 번호는 더 좋은 그룹 가중치를 받는다.
        double[] weight = new double[46];
        for (int rank = 1; rank <= rankedNumbers.size(); rank++) {
            int n = rankedNumbers.get(rank - 1);
            String g = getRankGroupFromRank(rank);
            double p = (g == null) ? 0 : rankDistribution.getOrDefault(g, 0.0);
            boolean vetoed = finalScores.getOrDefault(n, 0.0) <= -1000;
            weight[n] = vetoed ? 0.0 : p; // 그룹 내 균등 추출이므로 그룹확률을 그대로 사용
            // tier 가중: 상위권(1~15)·하위권(31~45) 강화, 중위권(16~30) 약화
            weight[n] *= (rank <= 15 ? TIER_TOP : rank <= 30 ? TIER_MID : TIER_BOT);
        }

        // 필수 포함(간격5회 반복) 번호를 강한순(반복수 큰 순)으로 시드 후보에 정렬. 6개를 넘으면 앞 6개만.
        List<Integer> seedAll = new ArrayList<>();
        for (int m : (mustInclude == null ? Collections.<Integer>emptySet() : mustInclude)) {
            if (seedAll.size() >= 6) break;
            if (m >= 1 && m <= 45) seedAll.add(m);
        }

        // 직전 회차 연속쌍(이월) 회피 집합
        Set<List<Integer>> latestConsecutive = new HashSet<>();
        int[] ln = latestDraw.nums;
        for (int i = 0; i < ln.length - 1; i++)
            if (ln[i + 1] == ln[i] + 1) latestConsecutive.add(Arrays.asList(ln[i], ln[i + 1]));

        // 2) 게임당 강제 가능한 최대 개수(forceCount) 결정 — 강한 부분집합으로 충분한 후보가 모이는 한계.
        //    필수번호 전부를 한 게임에 강제하면 제약 충돌로 게임이 안 나오므로, 줄여가며 한계를 찾는다.
        Random rnd = new Random();
        int forceCount = seedAll.size();
        for (; forceCount > 0; forceCount--) {
            Map<List<Integer>, Double> probe = gatherCandidates(
                    new ArrayList<>(seedAll.subList(0, forceCount)), weight, filter,
                    latestConsecutive, cycleSet, rankedNumbers, rankDistribution, rnd, requiredGames, 300000);
            if (probe.size() >= requiredGames) break;
        }

        List<List<Integer>> picked = new ArrayList<>();
        if (forceCount >= seedAll.size() || forceCount == 0) {
            // 필수번호 전체가 한 게임에 다 들어가거나(회전 불필요) 강제할 게 없으면, 단일 풀에서 적합도·다양성 추출.
            Map<List<Integer>, Double> candidates = gatherCandidates(
                    new ArrayList<>(seedAll.subList(0, forceCount)), weight, filter, latestConsecutive,
                    cycleSet, rankedNumbers, rankDistribution, rnd, 2000, 300000);
            picked = pickDiverse(candidates, requiredGames);
        } else {
            // 3) 회전: 게임마다 forceCount개의 '서로 다른' 부분집합을 순환창으로 강제 →
            //    필수번호가 특정 강한 몇 개에 고정되지 않고 5게임 전체에 고루 등장한다.
            int S = seedAll.size();
            System.out.printf("※ 간격5회 필수번호 %d개는 제약 충돌로 게임당 %d개씩 회전 포함해 전 게임에 고루 배치합니다.\n",
                    S, forceCount);
            for (int g = 0; g < requiredGames; g++) {
                // 순환창 부분집합: seedAll[g], seedAll[g+1], …(mod S) 중 forceCount개.
                List<Integer> seed = new ArrayList<>();
                for (int k = 0; k < forceCount; k++) seed.add(seedAll.get((g + k) % S));
                Map<List<Integer>, Double> pool = gatherCandidates(
                        seed, weight, filter, latestConsecutive, cycleSet,
                        rankedNumbers, rankDistribution, rnd, 800, 200000);
                // 이 부분집합으로 유효 게임이 없으면 가장 약한 강제부터 한 개씩 줄여 재시도.
                for (int fc = forceCount - 1; pool.isEmpty() && fc >= 0; fc--)
                    pool = gatherCandidates(new ArrayList<>(seed.subList(0, fc)), weight, filter,
                            latestConsecutive, cycleSet, rankedNumbers, rankDistribution, rnd, 800, 200000);
                if (pool.isEmpty()) continue;
                final Map<List<Integer>, Double> fpool = pool;
                List<List<Integer>> cand = new ArrayList<>(fpool.keySet());
                cand.sort((a, b) -> Double.compare(fpool.get(b), fpool.get(a)));
                List<Integer> choice = null;
                for (List<Integer> game : cand) { // 적합도 높은 순으로, 기존과 2개↑ 다른 게임 우선
                    if (picked.contains(game)) continue;
                    boolean tooSimilar = false;
                    for (List<Integer> p : picked) if (overlapCount(game, p) >= 5) { tooSimilar = true; break; }
                    if (!tooSimilar) { choice = game; break; }
                }
                if (choice == null) // 다양성 만족 후보가 없으면 중복만 아니면 적합도 1위 채택
                    for (List<Integer> game : cand) if (!picked.contains(game)) { choice = game; break; }
                if (choice != null) picked.add(choice);
            }
            // 회전으로 requiredGames개를 못 채우면 강한 부분집합 풀로 보충.
            if (picked.size() < requiredGames) {
                Map<List<Integer>, Double> filler = gatherCandidates(
                        new ArrayList<>(seedAll.subList(0, forceCount)), weight, filter, latestConsecutive,
                        cycleSet, rankedNumbers, rankDistribution, rnd, 2000, 300000);
                List<List<Integer>> fcand = new ArrayList<>(filler.keySet());
                fcand.sort((a, b) -> Double.compare(filler.get(b), filler.get(a)));
                for (List<Integer> gApp : fcand) {
                    if (picked.size() >= requiredGames) break;
                    if (!picked.contains(gApp)) picked.add(gApp);
                }
            }
        }
        return picked;
    }

    // 주어진 강제 포함(seed) 번호로 시작해 나머지를 가중 추출하고, 모든 게임 제약을 통과한 조합을
    // (조합 → 적합도) 맵으로 maxCand개까지 수집한다. seed의 가중치는 0으로 빼 중복 추출을 막는다.
    private static Map<List<Integer>, Double> gatherCandidates(
            List<Integer> seed, double[] weight, GameFilter filter,
            Set<List<Integer>> latestConsecutive, Set<Integer> cycleSet,
            List<Integer> rankedNumbers, Map<String, Double> rankDistribution,
            Random rnd, int maxCand, int maxAttempts) {
        double[] restWeight = weight.clone();
        for (int m : seed) restWeight[m] = 0.0;
        int sampleK = 6 - seed.size();
        Map<List<Integer>, Double> candidates = new HashMap<>();
        int attempts = 0;
        while (candidates.size() < maxCand && attempts < maxAttempts) {
            attempts++;
            List<Integer> game = new ArrayList<>(seed); // 필수 포함 번호로 시작
            if (sampleK > 0) {
                List<Integer> rest = weightedSampleWithoutReplacement(restWeight, sampleK, rnd);
                if (rest == null) break; // 추출 가능한 번호가 부족
                game.addAll(rest);
            }
            Collections.sort(game);
            if (!filter.passes(game)) continue;
            if (containsConsecutivePair(game, latestConsecutive)) continue;
            if (maxLottoColumnCount(game) >= 3) continue; // 로또 용지 세로열 3개 이상 회피
            if (Collections.disjoint(game, cycleSet)) continue; // 주기300 번호 0개면 재생성
            candidates.putIfAbsent(game, fitness(game, rankedNumbers, rankDistribution));
        }
        return candidates;
    }

    // 적합도 내림차순으로, 서로 2개 이상 다른 게임을 우선해 requiredGames개를 고른다(모자라면 적합도순 보충).
    private static List<List<Integer>> pickDiverse(Map<List<Integer>, Double> candidates, int requiredGames) {
        List<List<Integer>> sorted = new ArrayList<>(candidates.keySet());
        sorted.sort((a, b) -> Double.compare(candidates.get(b), candidates.get(a)));
        List<List<Integer>> picked = new ArrayList<>();
        for (List<Integer> g : sorted) {
            if (picked.size() >= requiredGames) break;
            boolean tooSimilar = false;
            for (List<Integer> p : picked) if (overlapCount(g, p) >= 5) { tooSimilar = true; break; }
            if (!tooSimilar) picked.add(g);
        }
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
                    if (fa.getScore(n, prev, win(h)) > 0) { s[1]++; if (winners.contains(n)) s[0]++; }
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
            for (int n = 1; n <= 45; n++) raw[n] = analyzer.getScore(n, previousDraw, win(history));

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

    // targetDrawNo의 CYCLE_LOOKBACK(300)회 이내 형제 회차(D-100·D-200·D-300) 당첨번호 합집합.
    // history는 drawNo 오름차순. backtest에서도 history는 target 이전 데이터만 담으므로 look-ahead 없음.
    private static Set<Integer> cycleNumbers(int targetDrawNo, List<LottoDraw> history) {
        Set<Integer> set = new HashSet<>();
        for (int lag = 100; lag <= CYCLE_LOOKBACK; lag += 100) {
            int s = targetDrawNo - lag;
            if (s < 1) continue;
            // 연속 회차면 index = s-1. 아니면 선형 탐색으로 보강.
            if (s - 1 < history.size() && history.get(s - 1).drawNo == s) {
                set.addAll(history.get(s - 1).getWinningNumbers());
            } else {
                for (LottoDraw d : history) if (d.drawNo == s) { set.addAll(d.getWinningNumbers()); break; }
            }
        }
        return set;
    }

    // 주기 하강 스왑: 점수순 리스트를 좌→우로 1회 훑으며 '위=주기 미포함 && 아래=주기 포함'이면 교환.
    // 교환 후에도 같은 미포함 번호가 다음 칸과 계속 비교되므로, 미포함 번호는 자기 아래의
    // '연속된' 주기 포함 번호들을 전부 지나 가라앉는다. (예: 1위 미포함, 2·3위 포함 → 1위가 3위로,
    //  아래가 전부 포함이면 1위가 45위까지 하강. 미포함 번호를 만나면 멈춤.)
    private static List<Integer> applyCycleAdjacentSwap(List<Integer> ranked, Set<Integer> cycle) {
        List<Integer> r = new ArrayList<>(ranked);
        for (int i = 0; i + 1 < r.size(); i++) {
            if (!cycle.contains(r.get(i)) && cycle.contains(r.get(i + 1))) {
                Collections.swap(r, i, i + 1);
            }
        }
        return r;
    }

    // 순서가 매겨진 리스트에서 상위 6/10 안의 당첨 번호 수 [top6, top10].
    private static int[] countTopHitsList(List<Integer> ranked, Set<Integer> winners) {
        int h6 = 0, h10 = 0;
        for (int t = 0; t < 10 && t < ranked.size(); t++) {
            if (winners.contains(ranked.get(t))) { if (t < 6) h6++; h10++; }
        }
        return new int[]{h6, h10};
    }

    // 단일 분석기 점수로 1~45 순위 (동점은 번호 오름차순).
    private static List<Integer> rankByAnalyzer(PatternAnalyzer a, LottoDraw prev, List<LottoDraw> hist) {
        Map<Integer, Double> s = new HashMap<>();
        for (int n = 1; n <= 45; n++) s.put(n, a.getScore(n, prev, win(hist)));
        List<Integer> r = new ArrayList<>();
        for (int n = 1; n <= 45; n++) r.add(n);
        r.sort((x, y) -> { int c = Double.compare(s.get(y), s.get(x)); return c != 0 ? c : Integer.compare(x, y); });
        return r;
    }

    // 진단: 대상 회차 lo~hi를 각각 직전 데이터로 예측해, 패턴별로 당첨번호를 얼마나 잘 잡는지 측정.
    // 어떤 패턴도 미리 고를 수 없음(회차마다 최적 패턴이 달라짐)을 보이는 것이 목적. look-ahead 없음.
    private static void runPatternDiagnostic(List<LottoDraw> all, int lo, int hi) {
        // drawNo → index
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        Map<String, double[]> acc = new LinkedHashMap<>(); // name → [sumTop6, sumTop10, count]
        double ensTop6 = 0, ensTop10 = 0; int nDraws = 0;

        System.out.println("==========================================================");
        System.out.printf("   회차별 '최적 패턴' 진단 (대상 %d~%d, 직전 데이터로 예측)\n", lo, hi);
        System.out.println("==========================================================");
        System.out.println("회차 | 앙상블 상위10 적중 | 그 회차 최적 단일패턴(상위10 적중수)");
        System.out.println("----------------------------------------------------------");

        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);

            String best = "-"; int bestHit = -1;
            for (PatternAnalyzer a : az) {
                List<Integer> ranked = rankByAnalyzer(a, prev, hist);
                int[] th = countTopHitsList(ranked, win);
                acc.computeIfAbsent(a.getName(), k -> new double[3]);
                double[] s = acc.get(a.getName()); s[0] += th[0]; s[1] += th[1]; s[2]++;
                if (th[1] > bestHit) { bestHit = th[1]; best = a.getName(); }
            }
            // 앙상블(walk-forward 가중 + 순위그룹 + 주기 스왑 = 라이브 로직)
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));
            int[] e = countTopHitsList(ranked, win);
            ensTop6 += e[0]; ensTop10 += e[1]; nDraws++;
            System.out.printf("%4d |        %d개         | %s (%d개)\n", T, e[1], best, bestHit);
        }

        System.out.println("----------------------------------------------------------");
        System.out.printf("앙상블 평균: 상위6 %.2f개 / 상위10 %.2f개  (무작위 기대 0.80 / 1.33)\n",
                ensTop6 / nDraws, ensTop10 / nDraws);
        System.out.println("\n패턴별 평균 적중 (대상 " + nDraws + "회, 상위10 기준 내림차순):");
        System.out.println("----------------------------------------------------------");
        List<Map.Entry<String, double[]>> rows = new ArrayList<>(acc.entrySet());
        rows.sort((x, y) -> Double.compare(y.getValue()[1] / y.getValue()[2], x.getValue()[1] / x.getValue()[2]));
        System.out.println("패턴            | 평균 상위6 | 평균 상위10");
        for (Map.Entry<String, double[]> en : rows) {
            double[] s = en.getValue();
            System.out.printf("%-14s | %8.2f | %9.2f\n", en.getKey(), s[0] / s[2], s[1] / s[2]);
        }
        System.out.println("==========================================================");
        System.out.println("※ '최적 패턴'이 회차마다 바뀌면, 다음 회차의 최적 패턴은 미리 알 수 없음 → 특정 패턴/수정으로");
        System.out.println("  과거 한 회차를 맞춰도 예측력이 아님(오버피팅). 패턴별 평균도 무작위 기대 부근에 수렴.");
    }

    // 진단: 대상 회차들의 당첨번호가 추천순위 [15~25]·[35~45] 구간에 들어가는지 확인.
    private static void runBandCheck(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        int totMid = 0, totHigh = 0, totUnion = 0, totWin = 0, nDraws = 0;
        System.out.println("==========================================================");
        System.out.printf("   당첨번호의 추천순위 구간 확인 (대상 %d~%d)\n", lo, hi);
        System.out.println("   구간: [15~25위], [35~45위]  (두 구간 합 = 45자리 중 22자리)\n");
        System.out.println("회차 | 당첨번호(추천순위)                          | 15~25 | 35~45");
        System.out.println("----------------------------------------------------------");
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));

            int mid = 0, high = 0;
            List<Integer> wl = new ArrayList<>(win); Collections.sort(wl);
            StringBuilder sb = new StringBuilder();
            for (int n : wl) {
                int r = ranked.indexOf(n) + 1;
                boolean m = r >= 15 && r <= 25, h = r >= 35 && r <= 45;
                if (m) mid++; if (h) high++;
                sb.append(String.format("%d(%d%s) ", n, r, m ? "M" : h ? "H" : ""));
            }
            int union = mid + high;
            totMid += mid; totHigh += high; totUnion += union; totWin += 6; nDraws++;
            System.out.printf("%4d | %-42s | %d개  | %d개\n", T, sb.toString().trim(), mid, high);
        }
        System.out.println("----------------------------------------------------------");
        System.out.printf("합계: 당첨 %d개 중  [15~25] %d개 · [35~45] %d개 · 두 구간 합 %d개 (%.1f%%)\n",
                totWin, totMid, totHigh, totUnion, 100.0 * totUnion / totWin);
        System.out.printf("무작위 기대(두 구간=22/45자리): %d개 중 %.1f개 (48.9%%)\n",
                totWin, totWin * 22.0 / 45);
        System.out.println("※ M=15~25위, H=35~45위. 관측이 무작위 기대 부근이면 '몰린다'고 볼 수 없음.");
        System.out.println("==========================================================");
    }

    // 진단: 당첨번호가 상위권(1~15)/중위권(16~30)/하위권(31~45)에서 얼마나 나오는지.
    private static void runTierCheck(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        int tTop = 0, tMid = 0, tBot = 0, nDraws = 0;
        System.out.println("==========================================================");
        System.out.printf("   당첨번호의 추천순위 구간 분포 (대상 %d~%d)\n", lo, hi);
        System.out.println("   상위권 1~15위 · 중위권 16~30위 · 하위권 31~45위\n");
        System.out.println("회차 | 당첨번호(추천순위)                          | 상위 중위 하위");
        System.out.println("----------------------------------------------------------");
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));

            int top = 0, mid = 0, bot = 0;
            List<Integer> wl = new ArrayList<>(win); Collections.sort(wl);
            StringBuilder sb = new StringBuilder();
            for (int n : wl) {
                int r = ranked.indexOf(n) + 1;
                String z = r <= 15 ? "상" : r <= 30 ? "중" : "하";
                if (r <= 15) top++; else if (r <= 30) mid++; else bot++;
                sb.append(String.format("%d(%d%s) ", n, r, z));
            }
            tTop += top; tMid += mid; tBot += bot; nDraws++;
            System.out.printf("%4d | %-42s |  %d   %d   %d\n", T, sb.toString().trim(), top, mid, bot);
        }
        System.out.println("----------------------------------------------------------");
        int totWin = nDraws * 6;
        System.out.printf("합계(%d회, 당첨 %d개): 상위권 %d · 중위권 %d · 하위권 %d\n",
                nDraws, totWin, tTop, tMid, tBot);
        System.out.printf("  비율:            상위권 %.1f%% · 중위권 %.1f%% · 하위권 %.1f%%\n",
                100.0 * tTop / totWin, 100.0 * tMid / totWin, 100.0 * tBot / totWin);
        System.out.printf("  무작위 기대:      각 구간 33.3%% (회차당 2개씩, %d개 중 %.1f개)\n", totWin, totWin / 3.0);
        System.out.println("==========================================================");
    }

    // 진단: 3순위 단위(1-3,4-6,…,43-45 = 15구간)로 당첨번호 분포.
    private static void runFineTierCheck(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        int[] cnt = new int[15]; // 구간 g = (rank-1)/3
        int nDraws = 0;
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));
            for (int n : win) {
                int r = ranked.indexOf(n) + 1;
                if (r >= 1 && r <= 45) cnt[(r - 1) / 3]++;
            }
            nDraws++;
        }
        int totWin = nDraws * 6;
        double exp = totWin * 3.0 / 45; // 구간당 무작위 기대
        System.out.println("==========================================================");
        System.out.printf("   당첨번호 3순위 단위 분포 (대상 %d~%d, %d회)\n", lo, hi, nDraws);
        System.out.printf("   당첨 %d개 · 무작위 기대 = 구간당 %.1f개 (6.7%%)\n", totWin, exp);
        System.out.println("----------------------------------------------------------");
        System.out.println("순위구간 | 당첨수 | 비율  | (막대)");
        int max = 0; for (int c : cnt) max = Math.max(max, c);
        for (int g = 0; g < 15; g++) {
            int a = g * 3 + 1, b = g * 3 + 3;
            int bars = max == 0 ? 0 : (int) Math.round(cnt[g] * 30.0 / max);
            String bar = "█".repeat(bars);
            String flag = cnt[g] >= exp * 1.5 ? " ▲" : cnt[g] <= exp * 0.5 ? " ▽" : "";
            System.out.printf("%2d~%2d위 | %4d개 | %4.1f%% | %s%s\n",
                    a, b, cnt[g], 100.0 * cnt[g] / totWin, bar, flag);
        }
        System.out.println("----------------------------------------------------------");
        System.out.println("▲=기대의 1.5배↑, ▽=기대의 0.5배↓. 표본 작으면 변동이 큼(노이즈 주의).");
        System.out.println("==========================================================");
    }

    // 진단: 45의 약수(>1)를 구간 크기로 나눠, 모든 회차에 ≥1 당첨이 나오는 '필출' 구간을 찾는다.
    private static void runDivisorCheck(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        // 회차별 당첨번호의 추천순위(1~45) 집합을 미리 계산
        List<Set<Integer>> winRanksPerDraw = new ArrayList<>();
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));
            Set<Integer> wr = new HashSet<>();
            for (int n : win) wr.add(ranked.indexOf(n) + 1);
            winRanksPerDraw.add(wr);
        }
        int nDraws = winRanksPerDraw.size();

        System.out.println("==========================================================");
        System.out.printf("   45 약수 구간별 '필출(모든 회차 ≥1 당첨)' 탐색 (대상 %d~%d, %d회)\n", lo, hi, nDraws);
        System.out.println("==========================================================");
        int[] divisors = {3, 5, 9, 15, 45};
        for (int d : divisors) {
            int groups = 45 / d;
            // 무작위 기대: 한 구간이 한 회차에 ≥1 당첨일 확률
            double pEmpty = 1.0;
            for (int i = 0; i < 6; i++) pEmpty *= (double) (45 - d - i) / (45 - i);
            double pHit = 1 - pEmpty;
            double expPilchul = groups * Math.pow(pHit, nDraws); // 기대 필출 구간 수

            System.out.printf("\n[구간 크기 %d위 → %d개 구간]  (한 구간 ≥1당첨 확률 %.1f%%, 무작위 기대 필출구간 %.2f개)\n",
                    d, groups, pHit * 100, expPilchul);
            List<String> pilchul = new ArrayList<>();
            for (int g = 0; g < groups; g++) {
                int a = g * d + 1, b = g * d + d;
                int hitDraws = 0;
                for (Set<Integer> wr : winRanksPerDraw) {
                    boolean any = false;
                    for (int r = a; r <= b; r++) if (wr.contains(r)) { any = true; break; }
                    if (any) hitDraws++;
                }
                String mark = (hitDraws == nDraws) ? "  ★필출" : "";
                if (hitDraws == nDraws) pilchul.add(a + "~" + b + "위");
                System.out.printf("  %2d~%2d위 | %d/%d 회 적중%s\n", a, b, hitDraws, nDraws, mark);
            }
            System.out.println("  → 필출 구간: " + (pilchul.isEmpty() ? "없음" : String.join(", ", pilchul)));
        }
        System.out.println("\n----------------------------------------------------------");
        System.out.println("※ 구간이 클수록 '필출'은 당연히 쉬워짐(자리를 많이 덮으니까). 무작위 기대보다");
        System.out.println("  특별히 많지 않으면 의미 없음. 표본 11회로는 큰 구간이 우연히 필출이 되기 쉬움.");
        System.out.println("==========================================================");
    }

    // 진단: 대상 lo~hi 각 회차를 직전 데이터로 예측한 순위에서, 순위별(1~45) 추천 번호가
    // 그 회차 당첨번호로 재출현한 횟수와 회차 목록을 집계한다. (파싱 가능한 형식으로 출력)
    private static void runRankHits(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        List<List<Integer>> hits = new ArrayList<>();
        for (int r = 0; r < 45; r++) hits.add(new ArrayList<>());
        int nDraws = 0;
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));
            for (int r = 1; r <= 45; r++) if (win.contains(ranked.get(r - 1))) hits.get(r - 1).add(T);
            nDraws++;
        }

        int total = 0; for (List<Integer> l : hits) total += l.size();
        System.out.println("==========================================================");
        System.out.printf("   순위별 당첨 재출현 통계 (대상 %d~%d, %d회)\n", lo, hi, nDraws);
        System.out.println("==========================================================");
        System.out.println("순위 | 적중 | 재출현 회차");
        System.out.println("----------------------------------------------------------");
        for (int r = 1; r <= 45; r++) {
            List<Integer> l = hits.get(r - 1);
            StringBuilder sb = new StringBuilder();
            for (int t : l) { if (sb.length() > 0) sb.append(","); sb.append(t); }
            System.out.printf("%2d위 | %d | %s\n", r, l.size(), l.isEmpty() ? "-" : sb.toString());
        }
        System.out.println("----------------------------------------------------------");
        System.out.printf("총 재출현 %d개 (=%d회×6) · 순위당 평균 %.2f개 (무작위 기대 %.2f)\n",
                total, nDraws, (double) total / 45, nDraws * 6.0 / 45);
        System.out.println("==========================================================");

        // ── 다음 회차(hi+1) 예측 순위 산정: 각 순위에 어떤 실제 번호가 놓이는지 매핑 ──
        int nextDraw = hi + 1;
        Integer lastIdx = idxOf.get(hi);
        List<Integer> rankedNext = null;
        if (lastIdx != null) {
            List<LottoDraw> histN = all.subList(0, lastIdx + 1);
            LottoDraw prevN = all.get(lastIdx);
            List<PatternAnalyzer> azN = analyzersFor(histN);
            Map<String, Double> wN = computeAccuracyWeights(histN, WF_TRAIN_WEEKS);
            Map<Integer, Double> scN = computeFinalScores(azN, histN, prevN, wN, null);
            Map<String, Double> distN = computeRankDistribution(histN, RANK_DIST_WEEKS, wN, null);
            String[] bwN = bestWorstGroup(distN);
            scN = applyRankBoost(scN, distN, bwN[0], bwN[1]);
            rankedNext = applyCycleAdjacentSwap(rankByScore(scN), cycleNumbers(nextDraw, histN));
        }

        // ── 재출현 간격(차이수) 반복 분석 ──
        //   각 순위의 재출현 회차 수열에서 인접 간격(=차이수)을 구해, 같은 간격이 ≥2회(반복 1번 이상) 나오는지 본다.
        //   '다음간격' = nextDraw - 마지막재출현회차. 이 값이 과거 간격으로 이미 나온 적이 있으면(반복) 이번에도 재출현 가능성↑.
        System.out.printf("   순위별 재출현 간격(차이수) 반복 분석 — 다음 회차 %d 예측\n", nextDraw);
        System.out.println("==========================================================");
        System.out.println("순위 | 번호 | 재출현간격(차이수) — 간격(전회차→후회차)        | 반복간격(횟수)  | 다음간격 | 이번가능성");
        System.out.println("----------------------------------------------------------");
        List<int[]> strongCandidates = new ArrayList<>(); // {rank, number, priorCount, nextGap}
        List<int[]> periodicRanks = new ArrayList<>();     // 반복 간격이 1번 이상 있는 순위 {rank, number}
        for (int r = 1; r <= 45; r++) {
            List<Integer> l = hits.get(r - 1);
            int number = (rankedNext != null) ? rankedNext.get(r - 1) : 0;
            if (l.size() < 2) {
                System.out.printf("%2d위 | %2d | %-48s | %-14s | %6s | %s\n",
                        r, number, l.isEmpty() ? "-" : "(간격없음, 재출현 " + l.get(0) + ")", "-", "-", "-");
                continue;
            }
            // 인접 간격 산출
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < l.size(); i++) gaps.add(l.get(i) - l.get(i - 1));
            // 간격별 등장 횟수
            Map<Integer, Integer> gapCount = new LinkedHashMap<>();
            for (int g : gaps) gapCount.merge(g, 1, Integer::sum);
            // 반복(≥2회) 간격 문자열
            StringBuilder rep = new StringBuilder();
            boolean hasRepeat = false;
            for (Map.Entry<Integer, Integer> e : gapCount.entrySet()) {
                if (e.getValue() >= 2) {
                    if (rep.length() > 0) rep.append(",");
                    rep.append(e.getKey()).append("(").append(e.getValue()).append("회)");
                    hasRepeat = true;
                }
            }
            int nextGap = nextDraw - l.get(l.size() - 1);          // 이번에 재출현한다면 가질 간격
            int priorCount = gapCount.getOrDefault(nextGap, 0);    // 그 간격이 과거에 나온 횟수
            String poss = priorCount >= 3
                    ? String.format("◎ 높음(간격 %d, 과거 %d회 반복)", nextGap, priorCount)
                    : (hasRepeat ? "○ 주기보유" : "-");
            StringBuilder gs = new StringBuilder();
            // 간격(차이수)에 어느 회차 사이의 간격인지 회차정보를 함께 표기: 간격(전회차→후회차)
            for (int i = 1; i < l.size(); i++) {
                if (gs.length() > 0) gs.append(",");
                gs.append(l.get(i) - l.get(i - 1))
                  .append("(").append(l.get(i - 1)).append("→").append(l.get(i)).append(")");
            }
            System.out.printf("%2d위 | %2d | %-48s | %-14s | %6d | %s\n",
                    r, number, gs.toString(), hasRepeat ? rep.toString() : "-", nextGap, poss);
            if (hasRepeat) periodicRanks.add(new int[]{r, number});
            if (priorCount >= 3) strongCandidates.add(new int[]{r, number, priorCount, nextGap});
        }
        System.out.println("----------------------------------------------------------");
        System.out.println("범례: 다음간격 = (다음회차 - 마지막재출현회차). 이 간격이 과거 재출현 간격으로 5회 이상 반복됐으면 ◎.");
        System.out.println("==========================================================");

        // ── 요약: 이번 회차 재출현 가능성이 있는 순위·번호 ──
        System.out.printf("   [%d회차] 재출현 간격 반복으로 본 '이번에도 나올 가능성' 순위\n", nextDraw);
        System.out.println("----------------------------------------------------------");
        if (strongCandidates.isEmpty()) {
            System.out.println("◎ 다음간격이 과거 간격으로 5회 이상 반복된 순위: 없음");
        } else {
            strongCandidates.sort((a, b) -> b[2] - a[2]); // 반복 횟수 많은 순
            System.out.println("◎ 다음간격이 과거 간격으로 5회 이상 반복된 순위 (강한 후보):");
            for (int[] c : strongCandidates)
                System.out.printf("   - %2d위(번호 %2d): 다음간격 %d이(가) 과거 %d회 반복 → 이번 회차 재출현 가능성 높음\n",
                        c[0], c[1], c[3], c[2]);
        }
        System.out.println();
        StringBuilder pr = new StringBuilder();
        for (int[] c : periodicRanks) {
            if (pr.length() > 0) pr.append(", ");
            pr.append(c[0]).append("위(").append(c[1]).append(")");
        }
        System.out.printf("○ 재출현 간격이 1번 이상 반복된 순위(주기 보유, 총 %d개): %s\n",
                periodicRanks.size(), periodicRanks.isEmpty() ? "없음" : pr.toString());
        System.out.println("==========================================================");
    }

    // 최근 window회 동안 walk-forward 순위로 본 순위별 재출현 회차에서, 인접 간격(차이수)을 구해
    // '다음 회차까지의 간격(=nextDraw-마지막재출현)'이 과거에 5회 이상 반복된 순위의 다음회차 예측번호를 반환.
    // (rankhits 진단의 ◎ '강한 후보'와 동일 기준 — 게임 생성 시 무조건 포함 대상)
    private static Set<Integer> gapRepeatNumbers(List<LottoDraw> all, int window) {
        if (all.isEmpty()) return Collections.emptySet();
        int hi = all.get(all.size() - 1).drawNo;
        int lo = hi - window + 1;
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);

        List<List<Integer>> hits = new ArrayList<>();
        for (int r = 0; r < 45; r++) hits.add(new ArrayList<>());
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<LottoDraw> hist = all.subList(0, idx);
            LottoDraw prev = all.get(idx - 1);
            Set<Integer> win = all.get(idx).getWinningNumbers();
            List<PatternAnalyzer> az = analyzersFor(hist);
            Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
            Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
            Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
            String[] bw = bestWorstGroup(wfDist);
            sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
            List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(T, hist));
            for (int r = 1; r <= 45; r++) if (win.contains(ranked.get(r - 1))) hits.get(r - 1).add(T);
        }

        // 다음 회차 예측 순위 (순위→번호 매핑)
        int nextDraw = hi + 1;
        int lastIdx = idxOf.get(hi);
        List<LottoDraw> histN = all.subList(0, lastIdx + 1);
        LottoDraw prevN = all.get(lastIdx);
        List<PatternAnalyzer> azN = analyzersFor(histN);
        Map<String, Double> wN = computeAccuracyWeights(histN, WF_TRAIN_WEEKS);
        Map<Integer, Double> scN = computeFinalScores(azN, histN, prevN, wN, null);
        Map<String, Double> distN = computeRankDistribution(histN, RANK_DIST_WEEKS, wN, null);
        String[] bwN = bestWorstGroup(distN);
        scN = applyRankBoost(scN, distN, bwN[0], bwN[1]);
        List<Integer> rankedNext = applyCycleAdjacentSwap(rankByScore(scN), cycleNumbers(nextDraw, histN));

        // (번호, 과거반복수) 수집 후 반복수 내림차순 → 강한 후보가 앞에 오도록 정렬해 반환.
        List<int[]> cand = new ArrayList<>(); // {번호, priorCount}
        for (int r = 1; r <= 45; r++) {
            List<Integer> l = hits.get(r - 1);
            if (l.size() < 2) continue;
            Map<Integer, Integer> gapCount = new HashMap<>();
            for (int i = 1; i < l.size(); i++) gapCount.merge(l.get(i) - l.get(i - 1), 1, Integer::sum);
            int nextGap = nextDraw - l.get(l.size() - 1);
            int prior = gapCount.getOrDefault(nextGap, 0);
            if (prior >= 5) cand.add(new int[]{rankedNext.get(r - 1), prior});
        }
        cand.sort((a, b) -> b[1] - a[1]);
        Set<Integer> result = new LinkedHashSet<>();
        for (int[] c : cand) result.add(c[0]);
        return result;
    }

    // 진단: 커스텀 패턴(고정 풀, GP 미주입)만으로 lo~hi 각 회차를 직전 데이터로 예측 → 후보풀이 당첨번호를 얼마나 잡는지.
    private static void runCustomDiag(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);
        List<CustomPatternMiner.Pattern> pats = CustomPatternMiner.patterns(); // GP 미주입 = 고정 풀만

        System.out.println("==========================================================");
        System.out.printf ("   커스텀 패턴(고정 풀)만 — 다음당첨 적중 통계 (대상 %d~%d, 직전 데이터로 예측)\n", lo, hi);
        System.out.printf ("   파이프라인: 최근 W=%d전이 최고 K=%d패턴 후보군  ·  변환 풀 %d개  ·  무작위 기대 적중 ≈ 풀크기×%.3f\n",
                CustomPatternMiner.W, CustomPatternMiner.K, pats.size(), 6.0 / 45.0);
        System.out.println("==========================================================");
        System.out.println("회차  | 풀크기 | 적중 | 당첨번호                  | 적중번호       | 후보풀(투표순)");
        System.out.println("----------------------------------------------------------");

        int rounds = 0, sumHit = 0, sumPool = 0, anyHit = 0;
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            List<CustomPatternMiner.Scored> sel = CustomPatternMiner.selectTopK(pats, all, idx);
            Map<Integer, Integer> votes = CustomPatternMiner.poolVotes(sel, all, idx);
            Set<Integer> win = all.get(idx).getWinningNumbers();

            List<Integer> pool = new ArrayList<>(votes.keySet());
            pool.sort((a, b) -> votes.get(b) != votes.get(a) ? votes.get(b) - votes.get(a) : a - b);
            List<Integer> hitNums = new ArrayList<>();
            for (int x : pool) if (win.contains(x)) hitNums.add(x);
            Collections.sort(hitNums);

            String poolStr = pool.stream()
                    .map(n -> votes.get(n) > 1 ? n + "(" + votes.get(n) + ")" : String.valueOf(n))
                    .collect(java.util.stream.Collectors.joining(", "));
            System.out.printf("%4d  | %5d  | %3d  | %-24s | %-13s | %s\n",
                    T, pool.size(), hitNums.size(), new TreeSet<>(win).toString(),
                    hitNums.isEmpty() ? "-" : hitNums.toString(), poolStr);

            rounds++; sumHit += hitNums.size(); sumPool += pool.size();
            if (!hitNums.isEmpty()) anyHit++;
        }

        System.out.println("----------------------------------------------------------");
        if (rounds == 0) { System.out.println("대상 회차 없음."); return; }
        double avgPool = (double) sumPool / rounds;
        System.out.printf("합계 %d회  |  총 적중 %d개  ·  회당 평균 적중 %.2f개  ·  평균 풀크기 %.1f  ·  적중률(≥1개) %.1f%% (%d/%d)\n",
                rounds, sumHit, (double) sumHit / rounds, avgPool, 100.0 * anyHit / rounds, anyHit, rounds);
        System.out.printf("무작위 기대(동일 풀크기): 회당 평균 적중 ≈ %.2f개\n", avgPool * 6.0 / 45.0);
        System.out.println("※ 로또는 독립 난수 — 평균 적중이 무작위 기대를 유의하게 넘지 못하면 예측력 없음(노이즈).");
        System.out.println("==========================================================");
    }

    // 진단: 대각합 전체 / 끝수 곱집합 각각의 '회차 내 생성 중복수'별 적중 확률.
    //   각 회차를 직전 데이터로 예측해 패턴 후보를 multiset(중복 허용)으로 만들고, 번호별 생성 횟수(=중복수)를
    //   집계한 뒤 그 번호가 실제 당첨이었는지 본다. 중복수가 높을수록 잘 맞는지 패턴별로 따로 측정.
    private static void runCustomDup(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);
        List<Integer> idxs = new ArrayList<>();
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx != null && idx >= 6) idxs.add(idx); // 대각합에 최근 6회 필요
        }

        runDupFor(all, idxs, "대각합 전체(↘)", true);
        runDupFor(all, idxs, "끝수 곱집합", false);
    }

    // diag=true → ↘대각합 전체 multiset, false → 끝수 곱집합 multiset.
    private static void runDupFor(List<LottoDraw> all, List<Integer> idxs, String title, boolean diag) {
        // 중복수 c → {번호개수, 적중개수}
        Map<Integer, int[]> byDup = new TreeMap<>();
        System.out.println("\n==========================================================");
        System.out.printf ("   [%s] 회차 내 생성 중복수별 적중 (대상 %d회)\n", title, idxs.size());
        System.out.println("==========================================================");
        System.out.println("회차  | 번호(중복수) …                         | 적중(중복수)");
        System.out.println("----------------------------------------------------------");

        for (int idx : idxs) {
            Map<Integer, Integer> mult = new TreeMap<>(); // 번호 → 생성 횟수
            if (diag) {
                for (int k = 0; k < 6; k++) {
                    int sum = 0;
                    for (int r = 0; r < 6; r++) {
                        int c = ((k + r) % 6 + 6) % 6;
                        sum += all.get(idx - 1 - r).nums[c];
                    }
                    mult.merge(CustomPatternMiner.wrap(sum), 1, Integer::sum);
                }
            } else {
                int[] d = all.get(idx - 1).nums;
                for (int x : d) {
                    int e = x % 10;
                    mult.merge(CustomPatternMiner.wrap(e * (e == 0 ? 1 : e)), 1, Integer::sum);
                }
            }
            Set<Integer> win = all.get(idx).getWinningNumbers();

            StringBuilder line = new StringBuilder(), hitStr = new StringBuilder();
            for (Map.Entry<Integer, Integer> en : mult.entrySet()) {
                int num = en.getKey(), c = en.getValue();
                line.append(num).append(c > 1 ? "(" + c + ") " : " ");
                byDup.computeIfAbsent(c, k -> new int[2])[0]++;
                if (win.contains(num)) {
                    byDup.get(c)[1]++;
                    hitStr.append(num).append("(").append(c).append(") ");
                }
            }
            System.out.printf("%4d  | %-38s | %s\n", all.get(idx).drawNo,
                    line.toString().trim(), hitStr.length() == 0 ? "-" : hitStr.toString().trim());
        }

        System.out.println("----------------------------------------------------------");
        System.out.println("중복수 | 번호개수 | 적중 | 적중률");
        for (Map.Entry<Integer, int[]> en : byDup.entrySet()) {
            int[] v = en.getValue();
            System.out.printf("  %2d회 |   %3d    | %3d  | %5.1f%%\n",
                    en.getKey(), v[0], v[1], v[0] > 0 ? 100.0 * v[1] / v[0] : 0.0);
        }
        System.out.println("※ 무작위 기대 적중률 = 6/45 ≈ 13.3%. 표본 작음 — 우연 주의.");
    }

    // 진단: 커스텀 변환 패턴(고정 풀) 각각의 lo~hi 단독 성적(총적중·회당평균·적중회차·평균풀·lift)을 정렬해 전체 표시.
    private static void runCustomRank(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);
        List<CustomPatternMiner.Pattern> pats = CustomPatternMiner.patterns(); // GP 미주입 = 고정 풀만
        final double BASE = 6.0 / 45.0;

        List<Integer> idxs = new ArrayList<>();
        List<Set<Integer>> wins = new ArrayList<>();
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            idxs.add(idx);
            wins.add(all.get(idx).getWinningNumbers());
        }
        int R = idxs.size();

        // {패턴index, 총적중, 총출력, 적중회차수}
        List<int[]> rows = new ArrayList<>();
        for (int p = 0; p < pats.size(); p++) {
            int hit = 0, emit = 0, anyR = 0;
            for (int r = 0; r < R; r++) {
                Set<Integer> c = pats.get(p).apply(all.subList(0, idxs.get(r)));
                emit += c.size();
                int h = 0;
                for (int x : c) if (wins.get(r).contains(x)) h++;
                hit += h;
                if (h > 0) anyR++;
            }
            rows.add(new int[]{p, hit, emit, anyR});
        }
        // 1차: 총적중 내림차순, 2차: lift 내림차순(=적중/출력), 3차: 출력 작은 순.
        rows.sort((a, b) -> {
            if (b[1] != a[1]) return b[1] - a[1];
            double la = a[2] > 0 ? (double) a[1] / a[2] : 0, lb = b[2] > 0 ? (double) b[1] / b[2] : 0;
            if (lb != la) return Double.compare(lb, la);
            return a[2] - b[2];
        });

        System.out.println("==========================================================");
        System.out.printf ("   커스텀 변환 패턴 단독 성적 정렬 (대상 %d~%d, %d회 · 패턴 %d개)\n", lo, hi, R, pats.size());
        System.out.printf ("   정렬: 총적중↓ → lift↓ → 풀작은순  ·  lift = 적중 ÷ (출력 × %.3f), 1.0=무작위\n", BASE);
        System.out.println("==========================================================");
        System.out.printf ("순위 | 패턴               | 총적중 | 회당평균 | 적중회차 | 평균풀 |  lift\n");
        System.out.println("----------------------------------------------------------");
        for (int k = 0; k < rows.size(); k++) {
            int[] e = rows.get(k);
            double avgPool = (double) e[2] / R;
            double lift = e[2] > 0 ? e[1] / (e[2] * BASE) : 0;
            System.out.printf("%3d  | %-18s | %5d  | %7.2f  | %3d/%-3d  | %5.1f  | %5.2f\n",
                    k + 1, pats.get(e[0]).name(), e[1], (double) e[1] / R, e[3], R, avgPool, lift);
        }
        System.out.println("==========================================================");
        System.out.println("※ lift>1 이라도 1218~1227 사후(in-sample) 표본 10회 — 우연일 수 있음(로또는 독립 난수).");

        // ── 다음회차 예측: 상위 패턴 두 지표(적중회차 비율 / lift) 기준 — Python PPT 파싱용 ──
        int nextNo = all.get(all.size() - 1).drawNo + 1;
        String[] metricNames = {"적중회차비율", "lift"};
        for (int mi = 0; mi < 2; mi++) {
            final int fmi = mi;
            List<int[]> sorted = new ArrayList<>(rows);
            sorted.sort((a, b) -> {
                double la = a[2] > 0 ? (double) a[1] / a[2] : 0, lb = b[2] > 0 ? (double) b[1] / b[2] : 0;
                if (fmi == 0) { // 적중회차(anyR=[3]) 우선 → 동률 lift → 풀작은순
                    if (b[3] != a[3]) return b[3] - a[3];
                    if (lb != la) return Double.compare(lb, la);
                    return a[2] - b[2];
                } else {        // lift 우선 → 동률 적중회차 → 풀작은순
                    if (lb != la) return Double.compare(lb, la);
                    if (b[3] != a[3]) return b[3] - a[3];
                    return a[2] - b[2];
                }
            });
            System.out.printf("%n##NEXT## %d회 예측 — 지표:%s (상위 5패턴)%n", nextNo, metricNames[mi]);
            System.out.println("순위 | 패턴 | 적중회차 | 회당평균 | lift | " + nextNo + "후보");
            Map<Integer, Integer> votes = new TreeMap<>();
            int topN = Math.min(5, sorted.size());
            for (int k = 0; k < topN; k++) {
                int[] e = sorted.get(k);
                Set<Integer> pred = new TreeSet<>(pats.get(e[0]).apply(all));
                for (int x : pred) votes.merge(x, 1, Integer::sum);
                double lift = e[2] > 0 ? e[1] / (e[2] * BASE) : 0;
                System.out.printf("%d@@%s@@%d/%d@@%.2f@@%.2f@@%s%n",
                        k + 1, pats.get(e[0]).name(), e[3], R, (double) e[1] / R, lift,
                        pred.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(" ")));
            }
            List<Integer> pool = new ArrayList<>(votes.keySet());
            pool.sort((a, b) -> votes.get(b) != votes.get(a) ? votes.get(b) - votes.get(a) : a - b);
            System.out.printf("##POOL## %s%n", pool.stream()
                    .map(n -> n + ":" + votes.get(n)).collect(java.util.stream.Collectors.joining(" ")));
        }
    }

    // 진단: 커스텀 변환 패턴(고정 풀)을 2개씩 조합 → lo~hi 각 회차를 직전 데이터로 예측한 두 패턴의 후보 합집합이
    //       당첨번호를 잡은 총합이 가장 큰 조합을 brute-force로 탐색.
    private static void runCustomPair(List<LottoDraw> all, int lo, int hi) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);
        List<CustomPatternMiner.Pattern> pats = CustomPatternMiner.patterns(); // GP 미주입 = 고정 풀만

        // 대상 회차(유효) 목록과 당첨번호 수집.
        List<Integer> rounds = new ArrayList<>();
        List<Set<Integer>> wins = new ArrayList<>();
        for (int T = lo; T <= hi; T++) {
            Integer idx = idxOf.get(T);
            if (idx == null || idx < 1) continue;
            rounds.add(idx);
            wins.add(all.get(idx).getWinningNumbers());
        }
        int R = rounds.size();
        int P = pats.size();

        // 패턴별·회차별 후보 캐시: cand[p][r] = p.apply(직전데이터).
        @SuppressWarnings("unchecked")
        Set<Integer>[][] cand = new Set[P][R];
        for (int p = 0; p < P; p++)
            for (int r = 0; r < R; r++)
                cand[p][r] = pats.get(p).apply(all.subList(0, rounds.get(r)));

        // 모든 2조합 평가: 총 적중수(합집합∩당첨) + 합집합 풀크기 누적.
        int[] bestI = new int[0], bestJ = new int[0];
        List<int[]> results = new ArrayList<>(); // {i, j, totalHit, totalPool}
        for (int i = 0; i < P; i++) {
            for (int j = i + 1; j < P; j++) {
                int totalHit = 0, totalPool = 0;
                for (int r = 0; r < R; r++) {
                    Set<Integer> u = new HashSet<>(cand[i][r]);
                    u.addAll(cand[j][r]);
                    totalPool += u.size();
                    for (int x : u) if (wins.get(r).contains(x)) totalHit++;
                }
                results.add(new int[]{i, j, totalHit, totalPool});
            }
        }
        results.sort((a, b) -> b[2] != a[2] ? b[2] - a[2] : a[3] - b[3]); // 적중 내림차순, 동률은 풀 작은 순

        System.out.println("==========================================================");
        System.out.printf ("   커스텀 변환 패턴 2조합 — 당첨 적중 최대 탐색 (대상 %d~%d, %d회)\n", lo, hi, R);
        System.out.printf ("   풀 %d개  ·  조합 수 %d개  ·  look-ahead 없음(각 회차 직전 데이터로 예측)\n",
                P, P * (P - 1) / 2);
        System.out.println("   동률은 풀크기가 작은(=효율 높은) 조합 우선.");
        System.out.println("==========================================================");
        System.out.println("순위 | 패턴 A             + 패턴 B             | 총적중 | 회당평균 | 평균풀 | 무작위기대");
        System.out.println("----------------------------------------------------------");
        int show = Math.min(15, results.size());
        for (int k = 0; k < show; k++) {
            int[] e = results.get(k);
            double avgPool = (double) e[3] / R;
            System.out.printf("%3d  | %-18s + %-18s | %5d  | %7.2f  | %5.1f  | %.2f\n",
                    k + 1, pats.get(e[0]).name(), pats.get(e[1]).name(),
                    e[2], (double) e[2] / R, avgPool, avgPool * 6.0 / 45.0);
        }
        System.out.println("==========================================================");

        // 최적 조합의 회차별 상세 — 두 패턴을 따로 표기.
        int[] top = results.get(0);
        String nameA = pats.get(top[0]).name(), nameB = pats.get(top[1]).name();
        System.out.printf("\n[1위 조합 회차별 상세 — 패턴 따로]  A=%s  ·  B=%s\n", nameA, nameB);
        System.out.println("----------------------------------------------------------");
        System.out.printf("회차  | 당첨번호                  | A후보              | A적중      | B후보         | B적중\n");
        int aHitTot = 0, bHitTot = 0;
        int interRounds = 0, interNums = 0, interHits = 0, interHitRounds = 0; // 교집합 통계
        for (int r = 0; r < R; r++) {
            Set<Integer> ca = new TreeSet<>(cand[top[0]][r]), cb = new TreeSet<>(cand[top[1]][r]);
            List<Integer> ha = new ArrayList<>(), hb = new ArrayList<>();
            for (int x : ca) if (wins.get(r).contains(x)) ha.add(x);
            for (int x : cb) if (wins.get(r).contains(x)) hb.add(x);
            aHitTot += ha.size(); bHitTot += hb.size();

            Set<Integer> inter = new TreeSet<>(ca); inter.retainAll(cb); // A∩B (중복 번호)
            List<Integer> ih = new ArrayList<>();
            for (int x : inter) if (wins.get(r).contains(x)) ih.add(x);
            if (!inter.isEmpty()) interRounds++;
            interNums += inter.size(); interHits += ih.size();
            if (!ih.isEmpty()) interHitRounds++;

            System.out.printf("%4d  | %-24s | %-17s | %-9s | %-12s | %-9s | 교집합 %-8s 적중 %s\n",
                    all.get(rounds.get(r)).drawNo, new TreeSet<>(wins.get(r)).toString(),
                    ca.toString(), ha.isEmpty() ? "-" : ha.toString(),
                    cb.toString(), hb.isEmpty() ? "-" : hb.toString(),
                    inter.isEmpty() ? "-" : inter.toString(), ih.isEmpty() ? "-" : ih.toString());
        }
        System.out.println("----------------------------------------------------------");
        System.out.printf("합계: A(%s) 총 적중 %d개(회당 %.2f)  ·  B(%s) 총 적중 %d개(회당 %.2f)\n",
                nameA, aHitTot, (double) aHitTot / R, nameB, bHitTot, (double) bHitTot / R);
        System.out.println("----------------------------------------------------------");
        System.out.printf("[교집합(두 패턴 중복 번호) 적중 확률]  교집합 발생 %d/%d회  ·  교집합 번호 총 %d개\n",
                interRounds, R, interNums);
        System.out.printf("  · 번호 기준 적중률: %d/%d = %.1f%% (중복으로 나온 번호가 실제 당첨일 확률)\n",
                interHits, interNums, interNums > 0 ? 100.0 * interHits / interNums : 0.0);
        System.out.printf("  · 회차 기준 적중률: %d/%d = %.1f%% (교집합 발생 회차 중 그게 당첨된 회차 비율)\n",
                interHitRounds, interRounds, interRounds > 0 ? 100.0 * interHitRounds / interRounds : 0.0);
        System.out.println("※ 1218~1227에 과적합된 사후(in-sample) 최적 조합 — 미래 적중 보장 아님(로또는 독립 난수).");

        // 1위 조합을 최신 이력에 적용 → 다음 회차 예측 후보.
        int nextNo = all.get(all.size() - 1).drawNo + 1;
        Set<Integer> a = new TreeSet<>(pats.get(top[0]).apply(all));
        Set<Integer> b = new TreeSet<>(pats.get(top[1]).apply(all));
        Set<Integer> u = new TreeSet<>(a); u.addAll(b);
        System.out.printf("\n[1위 조합 %d회 예측 후보]  %s  +  %s\n",
                nextNo, pats.get(top[0]).name(), pats.get(top[1]).name());
        System.out.printf("  · %-14s → %s\n", pats.get(top[0]).name(), a);
        System.out.printf("  · %-14s → %s\n", pats.get(top[1]).name(), b);
        System.out.printf("  ▶ 합집합 후보(%d개): %s\n", u.size(), u);
    }

    // 진단: 목표 회차를 직전 데이터로 예측한 순위로, tier 가중 설정별 5게임을 trials회 생성해 평균 적중 비교.
    private static void runGameSim(List<LottoDraw> all, int target, int trials) {
        Map<Integer, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < all.size(); i++) idxOf.put(all.get(i).drawNo, i);
        Integer idx = idxOf.get(target);
        if (idx == null || idx < 1) { System.out.println("대상 회차 없음: " + target); return; }

        List<LottoDraw> hist = all.subList(0, idx);
        LottoDraw prev = all.get(idx - 1);
        Set<Integer> winners = all.get(idx).getWinningNumbers();

        List<PatternAnalyzer> az = analyzersFor(hist);
        Map<String, Double> w = computeAccuracyWeights(hist, WF_TRAIN_WEEKS);
        Map<Integer, Double> sc = computeFinalScores(az, hist, prev, w, null);
        Map<String, Double> wfDist = computeRankDistribution(hist, RANK_DIST_WEEKS, w, null);
        String[] bw = bestWorstGroup(wfDist);
        sc = applyRankBoost(sc, wfDist, bw[0], bw[1]);
        List<Integer> ranked = applyCycleAdjacentSwap(rankByScore(sc), cycleNumbers(target, hist));
        Set<Integer> cyc = cycleNumbers(target, hist);
        GameFilter filter = GameFilter.fromHistory(hist);

        List<Integer> wl = new ArrayList<>(winners); Collections.sort(wl);
        System.out.println("==========================================================");
        System.out.printf("   tier 가중별 5게임 평균 적중 (대상 %d, 직전 %d 기준, %d회 시뮬)\n", target, prev.drawNo, trials);
        System.out.println("   실제 당첨: " + wl);
        System.out.println("----------------------------------------------------------");
        System.out.println("tier 가중(상/중/하)      | 평균 총적중/30 | 평균 당첨번호커버/6 | 1개↑게임%");
        System.out.println("----------------------------------------------------------");
        double[][] configs = {{1, 1, 1}, {1.3, 0.4, 1.3}, {1.5, 0.2, 1.5}};
        String[] cnames = {"균등 1.0/1.0/1.0", "U자 1.3/0.4/1.3", "강U자 1.5/0.2/1.5"};
        for (int c = 0; c < configs.length; c++) {
            TIER_TOP = configs[c][0]; TIER_MID = configs[c][1]; TIER_BOT = configs[c][2];
            double sumTot = 0, sumCover = 0; int gamesWithHit = 0, totGames = 0;
            for (int t = 0; t < trials; t++) {
                List<List<Integer>> games = generateDistributionGames(ranked, sc, wfDist, filter, prev, 5, cyc, Collections.<Integer>emptySet());
                Set<Integer> covered = new HashSet<>();
                for (List<Integer> g : games) {
                    int m = 0;
                    for (int n : g) if (winners.contains(n)) { m++; covered.add(n); }
                    sumTot += m; totGames++;
                    if (m > 0) gamesWithHit++;
                }
                sumCover += covered.size();
            }
            TIER_TOP = TIER_MID = TIER_BOT = 1.0;
            System.out.printf("%-22s | %12.2f | %18.2f | %6.1f%%\n",
                    cnames[c], sumTot / trials, sumCover / trials, 100.0 * gamesWithHit / totGames);
        }
        System.out.println("----------------------------------------------------------");
        System.out.printf("무작위 기대(6/45): 게임당 평균 %.2f개, 5게임 총 %.2f/30\n", 6 * 6.0 / 45, 5 * 6 * 6.0 / 45);
        System.out.println("==========================================================");
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
        String[] labels = {"기존(무가중 합산)", "신규(in-sample 가중)", "walk-forward+순위보정",
                "신규(walk-forward)", "walk-forward+순위+주기스왑"};
        double[] sum6 = new double[5];
        double[] sum10 = new double[5];
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
            // 주기 스왑: sC 순위에서 인접 단일 비교 스왑 적용 (hist만 사용 → look-ahead 없음)
            Set<Integer> cyc = cycleNumbers(drawHistory.get(i).drawNo, hist);
            List<Integer> sE = applyCycleAdjacentSwap(rankByScore(sC), cyc);

            int[] cA = countTopHits(sA, winners);
            int[] cB = countTopHits(sB, winners);
            int[] cC = countTopHits(sC, winners);
            int[] cD = countTopHits(sD, winners);
            int[] cE = countTopHitsList(sE, winners);
            sum6[0] += cA[0]; sum10[0] += cA[1];
            sum6[1] += cB[0]; sum10[1] += cB[1];
            sum6[2] += cC[0]; sum10[2] += cC[1];
            sum6[3] += cD[0]; sum10[3] += cD[1];
            sum6[4] += cE[0]; sum10[4] += cE[1];
            weeks++;
        }

        if (weeks == 0) return;
        System.out.println("\n==========================================================");
        System.out.printf ("      추천 방식 개선 효과 비교 (최근 %d주 백테스트)\n", weeks);
        System.out.println("==========================================================");
        System.out.printf ("기대값(무작위): 상위6 평균 %.2f개 / 상위10 평균 %.2f개\n", 6 * 6.0 / 45, 10 * 6.0 / 45);
        System.out.println("----------------------------------------------------------");
        System.out.println("방식                          | 상위6 적중 | 상위10 적중");
        System.out.println("----------------------------------------------------------");
        for (int k = 0; k < labels.length; k++) {
            System.out.printf("%-28s | %6.2f개 | %7.2f개\n", labels[k], sum6[k] / weeks, sum10[k] / weeks);
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
