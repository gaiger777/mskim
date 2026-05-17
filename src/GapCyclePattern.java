import java.util.List;

/**
 * 출현주기(Gap Cycle) 패턴 분석기입니다.
 * 각 번호의 과거 출현 간격(gap) 평균을 구하고,
 * 현재 미출현 기간이 자기 평균 주기에 가까워질수록 가점을 부여합니다.
 *
 * - 장기미출현(Cold) / 미출현(MissingPeriod)이 모든 번호에 동일 임계값을
 *   적용하는 데 반해, 이 패턴은 번호별 고유 평균 주기를 사용합니다.
 * - ratio = currentGap / avgGap
 *   ratio가 1.0 부근에서 최고점, 0.7 미만이면 0점, 2.5 이상이면 약한 점수.
 */
public class GapCyclePattern implements PatternAnalyzer {

    private final double[] avgGap = new double[46];
    private final int[] lastSeenDrawNo = new int[46];
    private static final int MIN_GAP_SAMPLES = 5;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int[] prevDrawNo = new int[46];
        long[] gapSum = new long[46];
        int[] gapCount = new int[46];

        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.nums) {
                if (prevDrawNo[num] > 0) {
                    int gap = draw.drawNo - prevDrawNo[num];
                    gapSum[num] += gap;
                    gapCount[num]++;
                }
                prevDrawNo[num] = draw.drawNo;
                lastSeenDrawNo[num] = draw.drawNo;
            }
        }

        for (int n = 1; n <= 45; n++) {
            avgGap[n] = (gapCount[n] >= MIN_GAP_SAMPLES) ? (double) gapSum[n] / gapCount[n] : 0;
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (avgGap[number] <= 0 || lastSeenDrawNo[number] == 0) return 0;

        int currentGap = latestDraw.drawNo - lastSeenDrawNo[number];
        if (currentGap <= 0) return 0;

        double ratio = currentGap / avgGap[number];

        if (ratio < 0.7) return 0;
        if (ratio > 2.5) return 30;

        double dist = Math.abs(1.0 - ratio);
        if (dist > 1.5) return 0;
        return (1.5 - dist) / 1.5 * 250;
    }

    @Override
    public String getName() {
        return "출현주기";
    }
}
