import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 연번(Consecutive Number) 패턴을 분석합니다.
 * 직전 회차에 특정 번호(N)가 나왔을 때, 그와 연속되는 번호(N-1, N+1)가
 * 다음 회차에 나올 확률을 통계적으로 분석하여 점수를 부여합니다.
 */
public class ConsecutiveNumberPattern implements PatternAnalyzer {

    // 각 번호가 연번 후보로서 다음 회차에 출현한 횟수
    private final int[] consecutiveAppearanceCount = new int[46];
    // 각 번호가 연번 후보가 되었던 총 횟수
    private final int[] consecutiveCandidateCount = new int[46];

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < 2) {
            return;
        }

        for (int i = 0; i < drawHistory.size() - 1; i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);
            Set<Integer> nextNumbers = nextDraw.getWinningNumbers();

            // 현재 회차의 모든 번호에 대해 연번 후보를 생성하고 카운트
            for (int num : currentDraw.getWinningNumbers()) {
                // N-1 후보
                if (num > 1) {
                    int candidate = num - 1;
                    consecutiveCandidateCount[candidate]++;
                    if (nextNumbers.contains(candidate)) {
                        consecutiveAppearanceCount[candidate]++;
                    }
                }
                // N+1 후보
                if (num < 45) {
                    int candidate = num + 1;
                    consecutiveCandidateCount[candidate]++;
                    if (nextNumbers.contains(candidate)) {
                        consecutiveAppearanceCount[candidate]++;
                    }
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 현재 번호가 최신 회차의 연번 후보인지 확인
        boolean isCandidate = false;
        for (int lastNum : latestDraw.getWinningNumbers()) {
            if (number == lastNum - 1 || number == lastNum + 1) {
                isCandidate = true;
                break;
            }
        }

        if (isCandidate) {
            int candidateCount = consecutiveCandidateCount[number];
            if (candidateCount > 0) {
                // 과거에 이 번호가 연번 후보였을 때 실제로 출현했던 확률
                double probability = (double) consecutiveAppearanceCount[number] / candidateCount;
                // 연번 패턴은 강력한 패턴 중 하나이므로 높은 가중치 부여 (500 -> 600)
                return probability * 600;
            }
        }

        return 0;
    }

    @Override
    public String getName() {
        return "연번";
    }
}
