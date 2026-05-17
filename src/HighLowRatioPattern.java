import java.util.List;

/**
 * 고저 비율(High/Low Ratio) 회귀 패턴 분석기입니다.
 * 1~22를 저번호, 23~45를 고번호로 분류한 뒤, 과거 회차의 저번호 개수 분포(0~6)에서
 * 가장 빈도가 높은 값을 찾아, 직전 회차의 저번호 개수가 그 값보다 많으면 고번호에,
 * 적으면 저번호에 가점을 부여합니다.
 */
public class HighLowRatioPattern implements PatternAnalyzer {

    private static final int LOW_MAX = 22; // 1~22 = 저, 23~45 = 고

    private final int[] lowCountDist = new int[7];
    private int totalDraws = 0;
    private int mostCommonLow = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            int low = 0;
            for (int n : draw.nums) if (n <= LOW_MAX) low++;
            lowCountDist[low]++;
            totalDraws++;
        }
        int maxC = -1;
        for (int i = 0; i <= 6; i++) {
            if (lowCountDist[i] > maxC) {
                maxC = lowCountDist[i];
                mostCommonLow = i;
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (totalDraws == 0) return 0;

        int latestLow = 0;
        for (int n : latestDraw.nums) if (n <= LOW_MAX) latestLow++;

        boolean numberIsLow = number <= LOW_MAX;
        double prob = (double) lowCountDist[mostCommonLow] / totalDraws;

        if (latestLow > mostCommonLow && !numberIsLow) {
            return prob * 100;
        }
        if (latestLow < mostCommonLow && numberIsLow) {
            return prob * 100;
        }
        return 0;
    }

    @Override
    public String getName() {
        return "고저균형";
    }
}
