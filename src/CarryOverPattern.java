import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이월수 패턴의 출현 주기성을 분석합니다.
 * 특정 기간 동안 이월수가 나오지 않았을 때, 다음 회차에 나올 확률에 가중치를 부여합니다.
 */
public class CarryOverPattern implements PatternAnalyzer {

    // K: 연속 미출현 횟수, V: [다음 회차에 출현한 횟수, 총 발생 횟수]
    private final Map<Integer, int[]> hitStatsAfterMissStreak = new HashMap<>();
    private static final int MIN_OCCURRENCES_FOR_STAT = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < 2) {
            return;
        }

        // 1. 각 회차별 이월수 출현 여부(hit) 리스트 생성
        List<Boolean> hits = new ArrayList<>();
        hits.add(false); // 1회차는 이전 회차가 없으므로 false
        for (int i = 0; i < drawHistory.size() - 1; i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            
            boolean hasCarryOver = !java.util.Collections.disjoint(currentDraw.getWinningNumbers(), nextDraw.getWinningNumbers());
            hits.add(hasCarryOver);
        }

        // 2. 미출현 주기 통계 분석
        int missStreak = 0;
        for (int i = 0; i < hits.size() - 1; i++) {
            if (hits.get(i)) {
                missStreak = 0;
            } else {
                missStreak++;
                int[] stats = hitStatsAfterMissStreak.computeIfAbsent(missStreak, k -> new int[2]);
                stats[1]++;
                if (hits.get(i + 1)) {
                    stats[0]++;
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 1. 현재 번호가 최신 회차의 이월수 후보인지 확인
        if (!latestDraw.getWinningNumbers().contains(number)) {
            return 0;
        }

        // 2. 현재까지의 실제 미출현 기간 계산
        int currentMissStreak = 0;
        for (int i = drawHistory.size() - 2; i >= 0; i--) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            
            boolean hasCarryOver = !java.util.Collections.disjoint(currentDraw.getWinningNumbers(), nextDraw.getWinningNumbers());
            if (hasCarryOver) {
                break;
            } else {
                currentMissStreak++;
            }
        }

        // 3. 통계 기반 점수 계산
        if (currentMissStreak > 0) {
            int[] stats = hitStatsAfterMissStreak.get(currentMissStreak);
            if (stats != null && stats[1] >= MIN_OCCURRENCES_FOR_STAT) {
                double probability = (double) stats[0] / stats[1];
                // 이월수 패턴은 다른 패턴보다 발생 빈도가 높으므로 가중치를 약간 낮게 조정 (250 -> 300, 20.0 -> 25.0)
                if (probability > 0.5) {
                    return (probability * 300) + (currentMissStreak * 25.0);
                }
            }
        }

        return 0;
    }

    @Override
    public String getName() {
        return "이월수(주기)";
    }
}
