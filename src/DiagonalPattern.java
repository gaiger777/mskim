import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대각선 패턴의 출현 주기성을 분석합니다.
 * 특정 기간 동안 대각선 패턴이 성공하지 않았을 때, 다음 회차에 성공할 확률에 가중치를 부여합니다.
 */
public class DiagonalPattern implements PatternAnalyzer {

    // 패턴 A(좌상->우하)의 미출현 주기 통계
    private final Map<Integer, int[]> hitStatsA = new HashMap<>();
    // 패턴 B(우상->좌하)의 미출현 주기 통계
    private final Map<Integer, int[]> hitStatsB = new HashMap<>();
    private static final int MIN_OCCURRENCES_FOR_STAT = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < 4) {
            return;
        }

        // 1. 각 회차별 패턴 성공 여부 리스트 생성
        List<Boolean> hitsA = new ArrayList<>();
        List<Boolean> hitsB = new ArrayList<>();
        for (int i = 3; i < drawHistory.size(); i++) {
            LottoPatternAnalyzer.LottoDraw drawN3 = drawHistory.get(i - 3);
            LottoPatternAnalyzer.LottoDraw drawN2 = drawHistory.get(i - 2);
            LottoPatternAnalyzer.LottoDraw drawN1 = drawHistory.get(i - 1);
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);

            int targetEndDigitA = (drawN3.nums[0] % 10 + drawN2.nums[1] % 10 + drawN1.nums[2] % 10) % 10;
            int targetEndDigitB = (drawN3.nums[5] % 10 + drawN2.nums[4] % 10 + drawN1.nums[3] % 10) % 10;

            boolean successA = false;
            boolean successB = false;
            for (int num : currentDraw.getWinningNumbers()) {
                if (num % 10 == targetEndDigitA) successA = true;
                if (num % 10 == targetEndDigitB) successB = true;
            }
            hitsA.add(successA);
            hitsB.add(successB);
        }

        // 2. 미출현 주기 통계 분석 (패턴 A)
        int missStreakA = 0;
        for (int i = 0; i < hitsA.size() - 1; i++) {
            if (hitsA.get(i)) {
                missStreakA = 0;
            } else {
                missStreakA++;
                int[] stats = hitStatsA.computeIfAbsent(missStreakA, k -> new int[2]);
                stats[1]++;
                if (hitsA.get(i + 1)) {
                    stats[0]++;
                }
            }
        }

        // 3. 미출현 주기 통계 분석 (패턴 B)
        int missStreakB = 0;
        for (int i = 0; i < hitsB.size() - 1; i++) {
            if (hitsB.get(i)) {
                missStreakB = 0;
            } else {
                missStreakB++;
                int[] stats = hitStatsB.computeIfAbsent(missStreakB, k -> new int[2]);
                stats[1]++;
                if (hitsB.get(i + 1)) {
                    stats[0]++;
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int historySize = drawHistory.size();
        if (historySize < 4) { // streak 계산을 위해 최소 4회차 필요
            return 0;
        }

        // 1. 다음 회차의 목표 끝수 계산
        LottoPatternAnalyzer.LottoDraw drawN3 = drawHistory.get(historySize - 3);
        LottoPatternAnalyzer.LottoDraw drawN2 = drawHistory.get(historySize - 2);
        LottoPatternAnalyzer.LottoDraw drawN1 = latestDraw;
        int targetEndDigitA = (drawN3.nums[0] % 10 + drawN2.nums[1] % 10 + drawN1.nums[2] % 10) % 10;
        int targetEndDigitB = (drawN3.nums[5] % 10 + drawN2.nums[4] % 10 + drawN1.nums[3] % 10) % 10;

        double score = 0;
        int numberEndDigit = number % 10;

        // 2. 패턴 A 점수 계산
        if (numberEndDigit == targetEndDigitA) {
            int currentMissStreak = calculateMissStreak(drawHistory, 'A');
            if (currentMissStreak > 0) {
                int[] stats = hitStatsA.get(currentMissStreak);
                if (stats != null && stats[1] >= MIN_OCCURRENCES_FOR_STAT) {
                    double probability = (double) stats[0] / stats[1];
                    // 가중치 상향 (300 -> 350, 25.0 -> 30.0)
                    if (probability > 0.5) {
                        score += (probability * 350) + (currentMissStreak * 30.0);
                    }
                }
            }
        }

        // 3. 패턴 B 점수 계산
        if (numberEndDigit == targetEndDigitB) {
            int currentMissStreak = calculateMissStreak(drawHistory, 'B');
            if (currentMissStreak > 0) {
                int[] stats = hitStatsB.get(currentMissStreak);
                if (stats != null && stats[1] >= MIN_OCCURRENCES_FOR_STAT) {
                    double probability = (double) stats[0] / stats[1];
                    // 가중치 상향 (300 -> 350, 25.0 -> 30.0)
                    if (probability > 0.5) {
                        score += (probability * 350) + (currentMissStreak * 30.0);
                    }
                }
            }
        }

        return score;
    }

    private int calculateMissStreak(List<LottoPatternAnalyzer.LottoDraw> drawHistory, char patternType) {
        int missStreak = 0;
        // historySize-1이 최신회차. 패턴은 3개 회차를 보므로, i는 3부터 시작.
        // 즉, i-1, i-2, i-3 회차를 보고 i회차의 성공여부를 판단.
        // 우리는 현재(미래)를 예측해야 하므로, 가장 마지막 성공 지점부터 역으로 추적.
        for (int i = drawHistory.size() - 1; i >= 3; i--) {
            LottoPatternAnalyzer.LottoDraw drawN3 = drawHistory.get(i - 3);
            LottoPatternAnalyzer.LottoDraw drawN2 = drawHistory.get(i - 2);
            LottoPatternAnalyzer.LottoDraw drawN1 = drawHistory.get(i - 1);
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);

            int targetEndDigit;
            if (patternType == 'A') {
                targetEndDigit = (drawN3.nums[0] % 10 + drawN2.nums[1] % 10 + drawN1.nums[2] % 10) % 10;
            } else { // 'B'
                targetEndDigit = (drawN3.nums[5] % 10 + drawN2.nums[4] % 10 + drawN1.nums[3] % 10) % 10;
            }

            boolean success = false;
            for (int num : currentDraw.getWinningNumbers()) {
                if (num % 10 == targetEndDigit) {
                    success = true;
                    break;
                }
            }

            if (success) {
                return missStreak; // 마지막 성공 지점을 찾았으므로 현재까지의 miss streak 반환
            } else {
                missStreak++;
            }
        }
        return missStreak; // 전체 기간 동안 한 번도 성공 안 한 경우
    }


    @Override
    public String getName() {
        return "대각선(주기)";
    }
}
