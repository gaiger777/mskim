import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 끝수 이월 패턴을 분석합니다.
 * 직전 회차에 나온 번호들의 끝수(last digit)가 다음 회차에 다시 나타나는 경향을 분석합니다.
 * 예를 들어, 직전 회차에 15, 23이 나왔다면, 다음 회차에 끝수가 5 또는 3인 번호(5, 15, 25, 35, 3, 13, 23, 33, 43)에
 * 가점을 부여합니다.
 */
public class SameLastDigitCarryOverPattern implements PatternAnalyzer {

    // 각 끝수(0-9)가 이월되어 나타난 횟수
    private final int[] lastDigitCarryOverCount = new int[10];
    // 각 끝수가 이월 후보가 되었던 총 횟수
    private final int[] lastDigitCandidateCount = new int[10];

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < 2) {
            return;
        }

        for (int i = 0; i < drawHistory.size() - 1; i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw nextDraw = drawHistory.get(i + 1);

            // 현재 회차의 끝수 집합
            Set<Integer> currentLastDigits = new HashSet<>();
            for (int num : currentDraw.getWinningNumbers()) {
                currentLastDigits.add(num % 10);
            }

            // 다음 회차의 끝수 집합
            Set<Integer> nextLastDigits = new HashSet<>();
            for (int num : nextDraw.getWinningNumbers()) {
                nextLastDigits.add(num % 10);
            }

            // 후보 카운트 및 성공 카운트 업데이트
            for (int lastDigit = 0; lastDigit < 10; lastDigit++) {
                if (currentLastDigits.contains(lastDigit)) {
                    lastDigitCandidateCount[lastDigit]++;
                    if (nextLastDigits.contains(lastDigit)) {
                        lastDigitCarryOverCount[lastDigit]++;
                    }
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int targetLastDigit = number % 10;

        // 최신 회차에 해당 끝수가 있었는지 확인
        Set<Integer> latestLastDigits = new HashSet<>();
        for (int num : latestDraw.getWinningNumbers()) {
            latestLastDigits.add(num % 10);
        }

        if (latestLastDigits.contains(targetLastDigit)) {
            int candidateCount = lastDigitCandidateCount[targetLastDigit];
            if (candidateCount > 0) {
                // 과거에 이 끝수가 이월 후보였을 때 실제로 이월되었던 확률
                double probability = (double) lastDigitCarryOverCount[targetLastDigit] / candidateCount;
                // 끝수 관련 패턴은 영향력이 크므로 높은 가중치 부여 (450 -> 550)
                return probability * 550;
            }
        }

        return 0;
    }

    @Override
    public String getName() {
        return "끝수이월";
    }
}
