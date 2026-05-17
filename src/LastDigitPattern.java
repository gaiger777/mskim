import java.util.List;

/**
 * 끝수(Last Digit) 통계 패턴 분석기입니다.
 * 0부터 9까지 각 끝수를 가진 번호들의 출현 빈도를 분석합니다.
 */
public class LastDigitPattern implements PatternAnalyzer {

    private final int[] lastDigitCount = new int[10];
    private long totalNumbersDrawn = 0;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.getWinningNumbers()) {
                lastDigitCount[num % 10]++;
            }
        }
        totalNumbersDrawn = drawHistory.size() * 6L;
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (totalNumbersDrawn == 0) {
            return 0;
        }
        int lastDigit = number % 10;
        // 전체 번호 중 해당 끝수가 차지하는 비율을 점수로 환산 (2200 -> 2500)
        double probability = (double) lastDigitCount[lastDigit] / totalNumbersDrawn;
        return probability * 2500;
    }

    @Override
    public String getName() {
        return "끝수통계";
    }
}
