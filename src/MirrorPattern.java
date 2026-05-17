import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 거울수(Mirror Number)의 출현 주기 패턴을 분석합니다.
 * 특정 기간 동안 거울수가 나오지 않았을 때, 다음 회차에 나올 확률에 가중치를 부여합니다.
 */
public class MirrorPattern implements PatternAnalyzer {

    private static final Map<Integer, Integer> MIRRORS = new HashMap<>();
    static {
        MIRRORS.put(12, 21); MIRRORS.put(21, 12);
        MIRRORS.put(13, 31); MIRRORS.put(31, 13);
        MIRRORS.put(14, 41); MIRRORS.put(41, 14);
        MIRRORS.put(23, 32); MIRRORS.put(32, 23);
        MIRRORS.put(24, 42); MIRRORS.put(42, 24);
        MIRRORS.put(34, 43); MIRRORS.put(43, 34);
    }

    // K: 연속 미출현 횟수, V: [다음 회차에 출현한 횟수, 총 발생 횟수]
    private final Map<Integer, int[]> hitStatsAfterMissStreak = new HashMap<>();
    private static final int MIN_OCCURRENCES_FOR_STAT = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < 2) {
            return;
        }

        // 1. 각 회차별 거울수 출현 여부(hit) 리스트 생성
        List<Boolean> hits = new ArrayList<>();
        hits.add(false); // 1회차는 이전 회차가 없으므로 false
        for (int i = 0; i < drawHistory.size() - 1; i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            
            boolean hasMirror = false;
            for (int num : currentDraw.getWinningNumbers()) {
                if (MIRRORS.containsKey(num)) {
                    if (nextDraw.getWinningNumbers().contains(MIRRORS.get(num))) {
                        hasMirror = true;
                        break;
                    }
                }
            }
            hits.add(hasMirror);
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
        // 1. 현재 번호가 최신 회차의 거울수 후보인지 확인
        boolean isMirrorCandidate = false;
        for (int lastNum : latestDraw.getWinningNumbers()) {
            if (MIRRORS.getOrDefault(lastNum, -1).equals(number)) {
                isMirrorCandidate = true;
                break;
            }
        }
        if (!isMirrorCandidate) {
            return 0;
        }

        // 2. 현재까지의 실제 미출현 기간 계산
        int currentMissStreak = 0;
        for (int i = drawHistory.size() - 2; i >= 0; i--) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            
            boolean hasMirror = false;
            for (int num : currentDraw.getWinningNumbers()) {
                if (MIRRORS.containsKey(num) && nextDraw.getWinningNumbers().contains(MIRRORS.get(num))) {
                    hasMirror = true;
                    break;
                }
            }

            if (hasMirror) {
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
                // 확률이 높을수록, 미출현 기간이 길수록 높은 점수 부여 (350 -> 400, 25.0 -> 30.0)
                if (probability > 0.5) {
                    return (probability * 400) + (currentMissStreak * 30.0);
                }
            }
        }

        return 0;
    }

    @Override
    public String getName() {
        return "거울수(주기)";
    }
}
