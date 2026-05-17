import java.util.List;

/**
 * 합계회귀(Sum Range) 패턴 분석기입니다.
 * 과거 회차들의 6개 번호 합계 분포에서 가장 빈도가 높은 10단위 구간을 찾아,
 * 그 구간의 평균 번호값(=bestSum/6)에 가까운 번호에 가점을 부여합니다.
 *
 * - 게임 생성 단계의 sum∈[100,180] 필터와 별개로, 점수 단계에서
 *   "균형 잡힌 합계"에 기여하는 번호를 미리 우대합니다.
 */
public class SumRangePattern implements PatternAnalyzer {

    private double targetAvgNum = 23.0; // 기본값: (1+45)/2

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.isEmpty()) return;

        int[] sumDist = new int[300]; // 6개 번호 합 최대 = 40+41+42+43+44+45 = 255
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            int sum = 0;
            for (int n : draw.nums) sum += n;
            if (sum >= 0 && sum < 300) sumDist[sum]++;
        }

        int bestStart = 130;
        int bestCount = -1;
        for (int s = 21; s <= 240; s++) {
            int windowCount = 0;
            for (int j = s; j < s + 10 && j < 300; j++) windowCount += sumDist[j];
            if (windowCount > bestCount) {
                bestCount = windowCount;
                bestStart = s;
            }
        }
        targetAvgNum = (bestStart + 5) / 6.0;
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        double dist = Math.abs(number - targetAvgNum);
        if (dist >= 18) return 0;
        return (18 - dist) / 18.0 * 120;
    }

    @Override
    public String getName() {
        return "합계균형";
    }
}
