import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 오프셋 패턴의 출현 주기성을 분석합니다.
 * 특정 기간 동안 오프셋 패턴이 성공하지 않았을 때, 다음 회차에 성공할 확률에 가중치를 부여합니다.
 */
public class OffsetPattern implements PatternAnalyzer {

    // K: 연속 미출현 횟수, V: [다음 회차에 출현한 횟수, 총 발생 횟수]
    private final Map<Integer, int[]> hitStatsAfterMissStreak = new HashMap<>();
    private static final int MIN_OCCURRENCES_FOR_STAT = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < 2) {
            return;
        }

        // 1. 각 회차별 오프셋 패턴 성공 여부(hit) 리스트 생성
        List<Boolean> hits = new ArrayList<>();
        hits.add(false); // 1회차는 이전 회차가 없으므로 false
        for (int i = 0; i < drawHistory.size() - 1; i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            
            int diff = currentDraw.nums[3] - currentDraw.nums[0];
            boolean success = false;
            for (int offset = -2; offset <= 2; offset++) {
                if (nextDraw.getWinningNumbers().contains(diff + offset)) {
                    success = true;
                    break;
                }
            }
            hits.add(success);
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
        // 1. 현재 번호가 최신 회차의 오프셋 후보인지 확인
        int diff = latestDraw.nums[3] - latestDraw.nums[0];
        boolean isOffsetCandidate = false;
        for (int offset = -2; offset <= 2; offset++) {
            if (number == diff + offset) {
                isOffsetCandidate = true;
                break;
            }
        }
        if (!isOffsetCandidate) {
            return 0;
        }

        // 2. 현재까지의 실제 미출현 기간 계산
        int currentMissStreak = 0;
        for (int i = drawHistory.size() - 2; i >= 0; i--) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            
            int d = currentDraw.nums[3] - currentDraw.nums[0];
            boolean success = false;
            for (int offset = -2; offset <= 2; offset++) {
                if (nextDraw.getWinningNumbers().contains(d + offset)) {
                    success = true;
                    break;
                }
            }
            
            if (success) {
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
                if (probability > 0.4) { // 확률 임계치 0.5 -> 0.4로 하향 조정
                    return (probability * 450) + (currentMissStreak * 35.0); // 점수 배율 400 -> 450, 보너스 30.0 -> 35.0
                }
            }
        }

        return 0;
    }

    @Override
    public String getName() {
        return "오프셋(주기)";
    }
}
