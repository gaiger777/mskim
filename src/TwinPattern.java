import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 쌍수(11, 22, 33, 44)의 출현 주기 패턴을 분석합니다.
 * 특정 기간 동안 쌍수가 나오지 않았을 때, 다음 회차에 나올 확률에 가중치를 부여합니다.
 */
public class TwinPattern implements PatternAnalyzer {

    private final Set<Integer> twins = Set.of(11, 22, 33, 44);
    // K: 연속 미출현 횟수, V: [다음 회차에 출현한 횟수, 총 발생 횟수]
    private final Map<Integer, int[]> hitStatsAfterMissStreak = new HashMap<>();
    private static final int MIN_OCCURRENCES_FOR_STAT = 3; // 통계적 유의성을 위한 최소 발생 횟수

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.isEmpty()) {
            return;
        }

        // 1. 각 회차별 쌍수 출현 여부(hit) 리스트 생성
        List<Boolean> hits = new ArrayList<>();
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            boolean hasTwin = !java.util.Collections.disjoint(draw.getWinningNumbers(), twins);
            hits.add(hasTwin);
        }

        // 2. 미출현 주기 통계 분석
        int missStreak = 0;
        for (int i = 0; i < hits.size() - 1; i++) {
            if (hits.get(i)) {
                // 출현했으면 미출현 기간 리셋
                missStreak = 0;
            } else {
                // 미출현했으면 기간 증가
                missStreak++;
                int[] stats = hitStatsAfterMissStreak.computeIfAbsent(missStreak, k -> new int[2]);
                stats[1]++; // 현재 길이의 미출현 주기가 발생함
                if (hits.get(i + 1)) {
                    stats[0]++; // 다음 회차에 출현함
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 분석 대상 번호가 쌍수가 아니면 점수 없음
        if (!twins.contains(number)) {
            return 0;
        }

        // 1. 현재까지의 실제 미출현 기간 계산
        int currentMissStreak = 0;
        for (int i = drawHistory.size() - 1; i >= 0; i--) {
            boolean hasTwin = !java.util.Collections.disjoint(drawHistory.get(i).getWinningNumbers(), twins);
            if (hasTwin) {
                break; // 가장 최근에 출현한 지점을 찾았으므로 중단
            } else {
                currentMissStreak++;
            }
        }

        // 2. 통계 기반 점수 계산
        if (currentMissStreak > 0) {
            int[] stats = hitStatsAfterMissStreak.get(currentMissStreak);
            // 해당 미출현 기간에 대한 통계가 충분히 쌓였을 경우
            if (stats != null && stats[1] >= MIN_OCCURRENCES_FOR_STAT) {
                double probability = (double) stats[0] / stats[1];
                // 확률이 높을수록, 미출현 기간이 길수록 높은 점수 부여 (350 -> 400, 25.0 -> 30.0)
                if (probability > 0.5) { // 통계적으로 나올 확률이 높을 때만 점수 부여
                    return (probability * 400) + (currentMissStreak * 30.0);
                }
            }
        }

        return 0; // 통계가 없거나, 미출현 상태가 아니면 점수 없음
    }

    @Override
    public String getName() {
        return "쌍수(주기)";
    }
}
