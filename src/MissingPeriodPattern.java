import java.util.List;

/**
 * 미출현 기간 패턴 분석기입니다.
 * 특정 기간 동안 나오지 않은 번호가 다시 출현하는 경향을 분석합니다.
 * 여기서는 5주에서 15주 사이의 미출현을 "주요 미출현" 기간으로 간주합니다.
 */
public class MissingPeriodPattern implements PatternAnalyzer {

    // 마지막으로 각 번호가 나타난 회차를 저장
    private final int[] lastSeenDraw = new int[46];
    // 주요 미출현 기간(5-15주)에 속한 번호가 다음 회차에 나온 횟수
    private final int[] majorMissingAppearanceCount = new int[46];
    private int totalMajorMissingOccurrences = 0;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 1. 각 번호의 마지막 출현 회차 기록
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.getWinningNumbers()) {
                lastSeenDraw[num] = draw.drawNo;
            }
        }

        // 2. 미출현 기간 후 출현하는 패턴 분석
        for (int i = 1; i < drawHistory.size(); i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw previousDraw = drawHistory.get(i - 1);

            for (int num = 1; num <= 45; num++) {
                // 이 번호가 마지막으로 나온 회차 찾기
                int lastSeen = 0;
                for (int j = i - 1; j >= 0; j--) {
                    if (drawHistory.get(j).getWinningNumbers().contains(num)) {
                        lastSeen = drawHistory.get(j).drawNo;
                        break;
                    }
                }

                if (lastSeen > 0) {
                    int weeksMissing = previousDraw.drawNo - lastSeen;
                    // 5주에서 15주 사이 미출현 번호가 현재 회차에 나왔는지 확인
                    if (weeksMissing >= 5 && weeksMissing <= 15) {
                        if (currentDraw.getWinningNumbers().contains(num)) {
                            majorMissingAppearanceCount[num]++;
                            totalMajorMissingOccurrences++;
                        }
                    }
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int latestDrawNo = latestDraw.drawNo;
        int weeksMissing = latestDrawNo - lastSeenDraw[number];

        // 현재 번호가 5주-15주 미출현 상태인지 확인
        if (weeksMissing >= 5 && weeksMissing <= 15) {
            if (totalMajorMissingOccurrences == 0) {
                return 0;
            }
            // 과거 동일한 조건(5-15주 미출현)에서 이 번호가 얼마나 자주 나왔는지에 대한 확률 점수
            double probability = (double) majorMissingAppearanceCount[number] / totalMajorMissingOccurrences;
            // 미출현 기간이 길수록 가중치를 더 부여 (선형적 증가) (120 -> 150, 0.12 -> 0.15)
            double bonusWeight = (weeksMissing - 4) * 0.15;
            return (probability * 150) + bonusWeight;
        }

        return 0;
    }

    @Override
    public String getName() {
        return "미출현";
    }
}
