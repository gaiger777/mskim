import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AC(Arithmetic Complexity, 산술적 복잡도) 패턴을 분석합니다.
 * AC는 6개 번호 간의 차이 값들의 종류가 몇 개인지를 나타내는 지표로, 조합의 균형을 측정합니다.
 * (AC값 범위: 5 ~ 10)
 * 통계적으로 자주 나오는 AC값(주로 7, 8, 9, 10)을 만드는 데 기여하는 번호에 가점을 부여합니다.
 */
public class ACPattern implements PatternAnalyzer {

    // 각 번호가 '좋은 AC값'(7-10)을 만드는 데 기여한 횟수
    private final int[] goodAcContributionCount = new int[46];
    // 각 번호가 당첨 번호로 나온 총 횟수
    private final int[] appearanceCount = new int[46];

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            int[] nums = draw.nums;
            int acValue = calculateAC(nums);

            // AC값이 통계적으로 유의미한 범위(7-10)에 속하는 경우
            if (acValue >= 7 && acValue <= 10) {
                for (int num : nums) {
                    goodAcContributionCount[num]++;
                }
            }
            // 전체 출현 횟수도 계산
            for (int num : nums) {
                appearanceCount[num]++;
            }
        }
    }

    /**
     * 주어진 번호 배열의 AC값을 계산합니다.
     */
    private int calculateAC(int[] nums) {
        if (nums.length != 6) {
            return 0;
        }
        Set<Integer> diffs = new HashSet<>();
        for (int i = 0; i < nums.length; i++) {
            for (int j = i + 1; j < nums.length; j++) {
                diffs.add(nums[j] - nums[i]);
            }
        }
        // AC = (차이값 종류의 개수) - (전체 번호 개수 - 1)
        return diffs.size() - (6 - 1);
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (appearanceCount[number] == 0) {
            return 0;
        }

        // 해당 번호가 전체 출현 중에서 '좋은 AC값'을 만드는 데 기여한 비율
        double contributionRate = (double) goodAcContributionCount[number] / appearanceCount[number];
        
        // AC 패턴은 조합의 전체적인 균형을 보는 것이므로, 개별 번호에 대한 점수는 다른 패턴보다 낮게 설정 (150 -> 250)
        return contributionRate * 250;
    }

    @Override
    public String getName() {
        return "AC패턴";
    }
}
