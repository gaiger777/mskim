import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 멸(滅) 구간 패턴의 출현 주기성을 분석합니다.
 * 특정 번호대가 연속으로 멸하지 않았을 때(즉, 매 회차 출현했을 때),
 * 다음 회차에 멸할 확률을 분석하여, 멸하지 않을 번호에 가점을 부여합니다.
 * 반대로, 특정 번호대가 연속으로 멸했을 때, 다음 회차에 해당 번호대에서 번호가 나올 확률도 분석합니다.
 */
public class ExtinctBandPattern implements PatternAnalyzer {

    // K: 번호대 인덱스, V: {K': 연속 출현 횟수, V': [다음에 멸하지 않을 확률, 총 발생 횟수]}
    private final Map<Integer, Map<Integer, int[]>> survivalStatsAfterAppearStreak = new HashMap<>();
    
    // K: 번호대 인덱스, V: {K': 연속 멸한 횟수, V': [다음에 출현할 확률, 총 발생 횟수]}
    private final Map<Integer, Map<Integer, int[]>> appearanceStatsAfterExtinctStreak = new HashMap<>();

    private static final int MIN_OCCURRENCES_FOR_STAT = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.isEmpty()) {
            return;
        }

        // 각 번호대별로 분석
        for (int band = 0; band < 5; band++) {
            List<Boolean> bandHits = new ArrayList<>();
            for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
                boolean exists = false;
                for (int num : draw.getWinningNumbers()) {
                    if (getBandIndex(num) == band) {
                        exists = true;
                        break;
                    }
                }
                bandHits.add(exists);
            }

            // 연속 출현 후 생존(멸하지 않음) 통계
            survivalStatsAfterAppearStreak.put(band, new HashMap<>());
            int appearStreak = 0;
            for (int i = 0; i < bandHits.size() - 1; i++) {
                if (bandHits.get(i)) {
                    appearStreak++;
                    int[] stats = survivalStatsAfterAppearStreak.get(band).computeIfAbsent(appearStreak, k -> new int[2]);
                    stats[1]++; // 연속 출현 발생
                    if (bandHits.get(i + 1)) {
                        stats[0]++; // 다음에도 멸하지 않음 (생존)
                    }
                } else {
                    appearStreak = 0;
                }
            }

            // 연속 멸 후 출현 통계
            appearanceStatsAfterExtinctStreak.put(band, new HashMap<>());
            int extinctStreak = 0;
            for (int i = 0; i < bandHits.size() - 1; i++) {
                if (!bandHits.get(i)) {
                    extinctStreak++;
                    int[] stats = appearanceStatsAfterExtinctStreak.get(band).computeIfAbsent(extinctStreak, k -> new int[2]);
                    stats[1]++; // 연속 멸 발생
                    if (bandHits.get(i + 1)) {
                        stats[0]++; // 다음에 출현
                    }
                } else {
                    extinctStreak = 0;
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int targetBand = getBandIndex(number);
        if (targetBand == -1) {
            return 0;
        }

        // 1. 현재 번호대의 연속 출현/멸 기간 계산
        int currentAppearStreak = 0;
        int currentExtinctStreak = 0;
        boolean latestWasExtinct = false;

        for (int i = drawHistory.size() - 1; i >= 0; i--) {
            boolean exists = false;
            for (int num : drawHistory.get(i).getWinningNumbers()) {
                if (getBandIndex(num) == targetBand) {
                    exists = true;
                    break;
                }
            }
            if (i == drawHistory.size() - 1) {
                latestWasExtinct = !exists;
            }

            if (exists) {
                if (currentExtinctStreak > 0) break;
                currentAppearStreak++;
            } else {
                if (currentAppearStreak > 0) break;
                currentExtinctStreak++;
            }
        }

        double score = 0;

        // 2. 연속 멸한 경우, 다음 회차 출현 확률 기반 점수
        if (latestWasExtinct && currentExtinctStreak > 0) {
            Map<Integer, int[]> bandStats = appearanceStatsAfterExtinctStreak.get(targetBand);
            if (bandStats != null) {
                int[] stats = bandStats.get(currentExtinctStreak);
                if (stats != null && stats[1] >= MIN_OCCURRENCES_FOR_STAT) {
                    double probability = (double) stats[0] / stats[1];
                    if (probability > 0.6) { // 멸 다음엔 나올 확률이 높아야 함
                        score += (probability * 450) + (currentExtinctStreak * 35.0); // 400 -> 450, 30.0 -> 35.0
                    }
                }
            }
        }
        // 3. 연속 출현한 경우, 다음 회차 생존 확률 기반 점수 (멸하지 않을 것에 대한 점수)
        else if (!latestWasExtinct && currentAppearStreak > 0) {
            Map<Integer, int[]> bandStats = survivalStatsAfterAppearStreak.get(targetBand);
            if (bandStats != null) {
                int[] stats = bandStats.get(currentAppearStreak);
                if (stats != null && stats[1] >= MIN_OCCURRENCES_FOR_STAT) {
                    double probability = (double) stats[0] / stats[1];
                    if (probability > 0.7) { // 계속 나올 확률이 높아야 함
                        score += (probability * 120) + (currentAppearStreak * 12.0); // 100 -> 120, 10.0 -> 12.0
                    }
                }
            }
        }
        
        return score;
    }

    @Override
    public String getName() {
        return "멸구간(주기)";
    }

    private int getBandIndex(int number) {
        if (number >= 1 && number <= 9) return 0;
        if (number >= 10 && number <= 19) return 1;
        if (number >= 20 && number <= 29) return 2;
        if (number >= 30 && number <= 39) return 3;
        if (number >= 40 && number <= 45) return 4;
        return -1;
    }
}
