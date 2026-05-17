import java.util.List;

/**
 * 동반출현(궁합수) 패턴 분석기입니다.
 * 두 번호가 한 회차 안에서 함께 출현한 빈도 행렬을 구축하고,
 * 직전 회차의 6개 번호와 동반 친화도가 높은 번호에 가점을 부여합니다.
 *
 * - 이월수(같은 번호 재출현)나 연번(N±1)과 달리, 임의의 두 번호 간
 *   장기 동반 경향을 잡아내는 점이 차별점입니다.
 * - 정규화: pairCount[a][b] / appearanceCount[b]
 *   (b가 출현했을 때 a가 함께 나왔던 비율)
 */
public class CompanionPattern implements PatternAnalyzer {

    private final int[][] pairCount = new int[46][46];
    private final int[] appearanceCount = new int[46];

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            int[] nums = draw.nums;
            for (int num : nums) {
                appearanceCount[num]++;
            }
            for (int i = 0; i < nums.length; i++) {
                for (int j = 0; j < nums.length; j++) {
                    if (i == j) continue;
                    pairCount[nums[i]][nums[j]]++;
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (appearanceCount[number] == 0) return 0;

        double affinitySum = 0;
        int validPartners = 0;
        for (int partner : latestDraw.nums) {
            if (partner == number) continue;
            if (appearanceCount[partner] == 0) continue;
            double affinity = (double) pairCount[number][partner] / appearanceCount[partner];
            affinitySum += affinity;
            validPartners++;
        }

        if (validPartners == 0) return 0;
        double avgAffinity = affinitySum / validPartners;

        double baseline = 0.12;
        if (avgAffinity <= baseline) return 0;
        return (avgAffinity - baseline) * 4500;
    }

    @Override
    public String getName() {
        return "궁합수";
    }
}
