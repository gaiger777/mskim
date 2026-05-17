import java.util.List;

/**
 * 누적 출현 빈도 패턴 분석기입니다.
 * 각 번호가 전체 추첨 역사에서 얼마나 자주 나타났는지를 분석합니다.
 */
public class TotalAppearancePattern implements PatternAnalyzer {

    private final int[] appearanceCount = new int[46];
    private long totalNumbersDrawn = 0;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.getWinningNumbers()) {
                appearanceCount[num]++;
            }
        }
        totalNumbersDrawn = drawHistory.size() * 6L;
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (totalNumbersDrawn == 0) {
            return 0;
        }
        // 전체 추첨된 번호들 중 해당 번호가 차지하는 비율을 점수로 환산 (1200 -> 1500)
        double probability = (double) appearanceCount[number] / totalNumbersDrawn;
        return probability * 1500;
    }

    @Override
    public String getName() {
        return "누적빈도";
    }
}
