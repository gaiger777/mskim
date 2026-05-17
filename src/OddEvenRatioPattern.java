import java.util.List;

/**
 * 홀짝 비율(Odd/Even Ratio) 회귀 패턴 분석기입니다.
 * 과거 회차들의 홀수 개수 분포(0~6)에서 가장 빈도가 높은 값(보통 3)을 찾고,
 * 직전 회차의 홀수 개수가 그 값보다 많으면 짝수에, 적으면 홀수에 가점을 부여합니다.
 *
 * - "정상으로 회귀(regression to the mean)" 가설을 직접 점수화합니다.
 */
public class OddEvenRatioPattern implements PatternAnalyzer {

    private final int[] oddCountDist = new int[7]; // index = 한 회차 홀수 개수 (0~6)
    private int totalDraws = 0;
    private int mostCommonOdd = 3;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            int odd = 0;
            for (int n : draw.nums) if (n % 2 == 1) odd++;
            oddCountDist[odd]++;
            totalDraws++;
        }
        int maxC = -1;
        for (int i = 0; i <= 6; i++) {
            if (oddCountDist[i] > maxC) {
                maxC = oddCountDist[i];
                mostCommonOdd = i;
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (totalDraws == 0) return 0;

        int latestOdd = 0;
        for (int n : latestDraw.nums) if (n % 2 == 1) latestOdd++;

        boolean numberIsOdd = number % 2 == 1;
        double prob = (double) oddCountDist[mostCommonOdd] / totalDraws;

        if (latestOdd > mostCommonOdd && !numberIsOdd) {
            return prob * 100;
        }
        if (latestOdd < mostCommonOdd && numberIsOdd) {
            return prob * 100;
        }
        return 0;
    }

    @Override
    public String getName() {
        return "홀짝균형";
    }
}
