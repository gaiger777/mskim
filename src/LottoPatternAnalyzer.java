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
        
        LottoDraw latestDraw = drawHistory.get(drawHistory.size() - 1);
        int nextDrawNo = latestDraw.drawNo + 1;

        // 2. 사용할 모든 패턴 분석기 등록
        List<PatternAnalyzer> allAnalyzers = getAllAnalyzers();

        // 3. 패턴별 정확도 분석 및 출력 (최근 15회차 기준) — 반환된 정확도를 분석기 가중치로 사용
        Map<String, Double> accuracyMap = analyzePatternAccuracy(drawHistory, getAllAnalyzers(), 15);

        // 4. 과거 당첨 분포 통계 분석 및 출력
        Map<String, Double> rankDistribution = analyzeRankDistribution(drawHistory, getAllAnalyzers(), 15); // 최근 15회차

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
            System.out.printf("▶ 필출 확률 UP: '%s' 그룹 (최근 15주간 당첨 확률: %.2f%%)\n", bestRankGroup, bestRankPercentage * 100);
            System.out.printf("  └ 예상: %d회차에 약 %.1f개 (1~3개) 번호가 포함될 수 있습니다.\n\n", nextDrawNo, expectedCount);
            System.out.printf("▶ 제외 확률 UP: '%s' 그룹 (최근 15주간 당첨 확률: %.2f%%)\n", worstRankGroup, worstRankPercentage * 100);
            System.out.println("  └ 참고: 이 그룹의 번호들은 후순위로 고려하는 것이 좋습니다.");
            System.out.println("==========================================================");
        }


        // 5. 최신 회차 기준으로 최종 점수 집계 (정확도 가중 + 분석기별 정규화 합산)
        List<PatternAnalyzer> currentAnalyzers = getAllAnalyzers();
        for (PatternAnalyzer analyzer : currentAnalyzers) {
            analyzer.analyze(drawHistory);
        }

        // 적용 전/후 효과 비교 (상위 추천에 실제 당첨 번호가 얼마나 몰리는지)
        evaluateImprovement(drawHistory, 15, accuracyMap, rankDistribution, bestGroup, worstGroup);

        Map<Integer, List<String>> scoreTags = new HashMap<>();
        for (int n = 1; n <= 45; n++) scoreTags.put(n, new ArrayList<>());
        Map<Integer, Double> rawScores =
                computeFinalScores(currentAnalyzers, drawHistory, latestDraw, accuracyMap, scoreTags);
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
        List<Integer> recommendationPool = topNumbers.subList(0, Math.min(25, topNumbers.size()));
        List<List<Integer>> finalGames = generateValidGames(recommendationPool, 5, latestDraw);

        System.out.println("----------------------------------------------------------");
        System.out.println("⚙️ [최종 5게임 자동 필터링]");
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
        analyzers.add(new ExclusionPattern());
        return analyzers;
    }

    private static Map<String, Double> analyzePatternAccuracy(List<LottoDraw> drawHistory, List<PatternAnalyzer> analyzers, int recentWeeks) {
        Map<String, int[]> patternStats = new HashMap<>(); // K: patternName, V: [hits, predictions]

        int startIdx = Math.max(1, drawHistory.size() - recentWeeks);
        for (int i = startIdx; i < drawHistory.size(); i++) {
            List<LottoDraw> historyForAnalysis = drawHistory.subList(0, i);
            LottoDraw previousDraw = drawHistory.get(i - 1);
            Set<Integer> actualWinningNumbers = drawHistory.get(i).getWinningNumbers();

            for (PatternAnalyzer analyzer : analyzers) {
                // 각 회차마다 분석기를 새로 초기화하여 상태 간섭 배제
                PatternAnalyzer freshAnalyzer = createNewAnalyzerInstance(analyzer);
                freshAnalyzer.analyze(historyForAnalysis);
                
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

        List<Map.Entry<String, int[]>> sortedStats = new ArrayList<>(patternStats.entrySet());
        sortedStats.sort((a, b) -> {
            double accA = (a.getValue()[1] == 0) ? 0 : (double) a.getValue()[0] / a.getValue()[1];
            double accB = (b.getValue()[1] == 0) ? 0 : (double) b.getValue()[0] / b.getValue()[1];
            return Double.compare(accB, accA);
        });

        System.out.println("==========================================================");
        System.out.println("      최근 15회차 패턴별 예측 정확도 순위");
        System.out.println("==========================================================");
        System.out.println("순위 | 패턴 기법    | 정확도 | (적중/예측)");
        System.out.println("----------------------------------------------------------");
        int rank = 1;
        for (Map.Entry<String, int[]> entry : sortedStats) {
            int hits = entry.getValue()[0];
            int predictions = entry.getValue()[1];
            double accuracy = (predictions == 0) ? 0 : (double) hits / predictions;
            if (predictions > 0) { // 예측을 한 번도 안 한 패턴은 제외
                 System.out.printf(" %2d위 | %-12s | %5.2f%% | (%d/%d)\n", rank++, entry.getKey(), accuracy * 100, hits, predictions);
            }
        }

        // 분석기 가중치로 재사용할 정확도 맵 반환
        Map<String, Double> accuracy = new HashMap<>();
        for (Map.Entry<String, int[]> e : patternStats.entrySet()) {
            int p = e.getValue()[1];
            accuracy.put(e.getKey(), p == 0 ? 0.0 : (double) e.getValue()[0] / p);
        }
        return accuracy;
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

    private static List<List<Integer>> generateValidGames(List<Integer> pool, int requiredGames, LottoDraw latestDraw) {
        List<List<Integer>> validGames = new ArrayList<>();
        if (pool.size() < 6) return validGames;
        int attempts = 0;

        Set<List<Integer>> consecutivePairsFromLatestDraw = new HashSet<>();
        int[] latestNums = latestDraw.nums;
        for (int i = 0; i < latestNums.length - 1; i++) {
            if (latestNums[i+1] == latestNums[i] + 1) {
                consecutivePairsFromLatestDraw.add(Arrays.asList(latestNums[i], latestNums[i+1]));
            }
        }

        while (validGames.size() < requiredGames && attempts < 100000) {
            attempts++;
            Collections.shuffle(pool);
            List<Integer> candidate = new ArrayList<>(pool.subList(0, 6));
            Collections.sort(candidate);

            int sum = candidate.stream().mapToInt(Integer::intValue).sum();
            long oddCount = candidate.stream().filter(n -> n % 2 != 0).count();
            long primeCount = candidate.stream().filter(PRIMES::contains).count();
            long multiplesOf3Count = candidate.stream().filter(MULTIPLES_OF_3::contains).count();
            long lowCount = candidate.stream().filter(n -> n <= 22).count();
            long lastDigitDistinct = candidate.stream().map(n -> n % 10).distinct().count();

            if (!(sum >= 100 && sum <= 180 && oddCount >= 2 && oddCount <= 4 && primeCount >= 1 && multiplesOf3Count >= 1
                    && lowCount >= 2 && lowCount <= 4 && lastDigitDistinct >= 4)) {
                continue;
            }

            if (containsConsecutiveCarryOver(candidate, consecutivePairsFromLatestDraw)) {
                continue;
            }

            if (!validGames.contains(candidate)) {
                validGames.add(candidate);
            }
        }
        return validGames;
    }

    private static boolean containsConsecutiveCarryOver(List<Integer> candidate, Set<List<Integer>> consecutivePairsFromLatestDraw) {
        for (List<Integer> pair : consecutivePairsFromLatestDraw) {
            if (candidate.contains(pair.get(0)) && candidate.contains(pair.get(1))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Double> analyzeRankDistribution(List<LottoDraw> drawHistory, List<PatternAnalyzer> analyzers, int recentWeeks) {
        Map<String, Integer> rankGroupCounts = new LinkedHashMap<>();
        Map<String, List<String>> rankGroupDetails = new LinkedHashMap<>(); // 변경: 당첨내역 상세 저장
        rankGroupCounts.put("1-5위", 0);
        rankGroupCounts.put("6-10위", 0);
        rankGroupCounts.put("11-15위", 0);
        rankGroupCounts.put("16-20위", 0);
        rankGroupCounts.put("21-25위", 0);
        rankGroupCounts.put("26-30위", 0);
        rankGroupCounts.put("31-35위", 0);
        rankGroupCounts.put("36-40위", 0);
        rankGroupCounts.put("41-45위", 0);
        
        rankGroupDetails.put("1-5위", new ArrayList<>());
        rankGroupDetails.put("6-10위", new ArrayList<>());
        rankGroupDetails.put("11-15위", new ArrayList<>());
        rankGroupDetails.put("16-20위", new ArrayList<>());
        rankGroupDetails.put("21-25위", new ArrayList<>());
        rankGroupDetails.put("26-30위", new ArrayList<>());
        rankGroupDetails.put("31-35위", new ArrayList<>());
        rankGroupDetails.put("36-40위", new ArrayList<>());
        rankGroupDetails.put("41-45위", new ArrayList<>());


        int totalWinningNumbers = 0;

        int startIdx = Math.max(1, drawHistory.size() - recentWeeks);
        for (int i = startIdx; i < drawHistory.size(); i++) {
            List<LottoDraw> historyForAnalysis = drawHistory.subList(0, i);
            LottoDraw previousDraw = drawHistory.get(i - 1);
            LottoDraw currentDraw = drawHistory.get(i);
            Set<Integer> actualWinningNumbers = currentDraw.getWinningNumbers();
            
            Map<Integer, Double> scores = new HashMap<>();
            for (int n = 1; n <= 45; n++) {
                double totalScore = 0;
                for (PatternAnalyzer analyzer : analyzers) {
                     PatternAnalyzer freshAnalyzer = createNewAnalyzerInstance(analyzer);
                     freshAnalyzer.analyze(historyForAnalysis);
                     totalScore += freshAnalyzer.getScore(n, previousDraw, historyForAnalysis);
                }
                scores.put(n, totalScore);
            }

            List<Integer> rankedNumbers = new ArrayList<>(scores.keySet());
            rankedNumbers.sort((a, b) -> scores.get(b).compareTo(scores.get(a)));

            for (int winningNum : actualWinningNumbers) {
                int rank = rankedNumbers.indexOf(winningNum) + 1;
                String rankGroup = getRankGroupFromRank(rank);
                if (rankGroup != null) {
                    rankGroupCounts.put(rankGroup, rankGroupCounts.get(rankGroup) + 1);
                    rankGroupDetails.get(rankGroup).add(String.format("%d,%d(%d위)", currentDraw.drawNo, winningNum, rank)); // 당첨회차,번호와 예측순위 함께 저장
                    totalWinningNumbers++;
                }
            }
        }

        System.out.println("\n==========================================================");
        System.out.println("      최근 15회차 추천 순위별 당첨 번호 분포 통계");
        System.out.println("==========================================================");
        System.out.println("총 당첨 번호 수: " + totalWinningNumbers + "개 (최근 15주)");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.println("순위 그룹   | 당첨 횟수 | 비율   | 당첨 내역 (회차,번호(예측순위))");
        System.out.println("------------------------------------------------------------------------------------------");

        Map<String, Double> rankDistribution = new HashMap<>();
        for (Map.Entry<String, Integer> entry : rankGroupCounts.entrySet()) {
            String group = entry.getKey();
            int count = entry.getValue();
            double percentage = (totalWinningNumbers > 0) ? (double) count / totalWinningNumbers : 0;
            
            String detailsStr = String.join(", ", rankGroupDetails.get(group));

            System.out.printf("%-10s | %8d | %6.2f%% | %s\n", group, count, percentage * 100, detailsStr);
            rankDistribution.put(group, percentage);
        }
        System.out.println("==========================================================================================");

        return rankDistribution;
    }
    
    // 주어진 history만으로 분석기별 정확도(가중치)를 계산. walk-forward 검증에서 매 주 재학습용.
    private static Map<String, Double> computeAccuracyWeights(List<LottoDraw> history, int recentWeeks) {
        Map<String, int[]> stats = new HashMap<>();
        int startIdx = Math.max(1, history.size() - recentWeeks);
        for (int i = startIdx; i < history.size(); i++) {
            List<LottoDraw> h = history.subList(0, i);
            LottoDraw prev = history.get(i - 1);
            Set<Integer> winners = history.get(i).getWinningNumbers();
            for (PatternAnalyzer a : getAllAnalyzers()) {
                PatternAnalyzer fa = createNewAnalyzerInstance(a);
                fa.analyze(h);
                stats.putIfAbsent(fa.getName(), new int[2]);
                int[] s = stats.get(fa.getName());
                for (int n = 1; n <= 45; n++) {
                    if (fa.getScore(n, prev, h) > 0) { s[1]++; if (winners.contains(n)) s[0]++; }
                }
            }
        }
        Map<String, Double> acc = new HashMap<>();
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            int p = e.getValue()[1];
            acc.put(e.getKey(), p == 0 ? 0.0 : (double) e.getValue()[0] / p);
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

            // 가중치 = 정확도 - 기준선. 기준선(13.33%) 이하 분석기는 변별력이 없으므로 제외.
            double weight = Math.max(0.0, accuracyWeights.getOrDefault(analyzer.getName(), 0.0) - BASELINE);
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
            Map<String, Double> accuracyWeights, Map<String, Double> rankDistribution,
            String bestGroup, String worstGroup) {

        final int trainWeeks = 50; // walk-forward 재학습 창
        int startIdx = Math.max(1, drawHistory.size() - recentWeeks);
        String[] labels = {"기존(무가중 합산)", "신규(in-sample 가중)", "신규+순위그룹보정", "신규(walk-forward)"};
        double[] sum6 = new double[4];
        double[] sum10 = new double[4];
        int weeks = 0;

        for (int i = startIdx; i < drawHistory.size(); i++) {
            List<LottoDraw> hist = drawHistory.subList(0, i);
            LottoDraw prev = drawHistory.get(i - 1);
            Set<Integer> winners = drawHistory.get(i).getWinningNumbers();

            List<PatternAnalyzer> fresh = getAllAnalyzers();
            for (PatternAnalyzer a : fresh) a.analyze(hist);

            Map<Integer, Double> sA = computeFinalScores(fresh, hist, prev, null, null);
            Map<Integer, Double> sB = computeFinalScores(fresh, hist, prev, accuracyWeights, null);
            Map<Integer, Double> sC = applyRankBoost(new HashMap<>(sB), rankDistribution, bestGroup, worstGroup);
            // walk-forward: 이 주 이전 데이터만으로 가중치를 재학습 (look-ahead 없음)
            Map<String, Double> wfWeights = computeAccuracyWeights(hist, trainWeeks);
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
