import java.util.List;

/**
 * 번호대별 출현 빈도 패턴 분석기입니다.
 * 0번대(단번대), 10번대, 20번대, 30번대, 40번대 각각의 내부에서
 * 특정 번호가 얼마나 자주 나왔는지를 분석합니다.
 */
public class NumberBandAppearancePattern implements PatternAnalyzer {

    // 각 번호대별로 번호의 출현 횟수를 저장
    // 0: 0-9, 1: 10-19, 2: 20-29, 3: 30-39, 4: 40-45
    private final int[][] bandAppearanceCount = new int[5][46];
    // 각 번호대별 전체 출현 횟수
    private final int[] totalBandOccurrences = new int[5];

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.getWinningNumbers()) {
                int bandIndex = getBandIndex(num);
                if (bandIndex != -1) {
                    bandAppearanceCount[bandIndex][num]++;
                    totalBandOccurrences[bandIndex]++;
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int bandIndex = getBandIndex(number);
        if (bandIndex == -1 || totalBandOccurrences[bandIndex] == 0) {
            return 0;
        }

        // 해당 번호가 속한 번호대 내에서의 출현 확률
        double probabilityInBand = (double) bandAppearanceCount[bandIndex][number] / totalBandOccurrences[bandIndex];
        
        // 번호대 내에서의 확률을 점수로 사용 (영향력을 위해 120 -> 150 곱함)
        return probabilityInBand * 150;
    }

    @Override
    public String getName() {
        return "번호대빈도";
    }

    private int getBandIndex(int number) {
        if (number >= 1 && number <= 9) return 0;
        if (number >= 10 && number <= 19) return 1;
        if (number >= 20 && number <= 29) return 2;
        if (number >= 30 && number <= 39) return 3;
        if (number >= 40 && number <= 45) return 4;
        return -1;
    }
}
