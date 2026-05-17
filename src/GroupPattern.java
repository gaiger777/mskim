import java.util.List;
import java.util.Set;

/**
 * 특정 숫자 그룹(예: 소수, 3의 배수)의 출현 경향을 분석하는 범용 패턴 분석기입니다.
 * 생성 시 어떤 그룹을 분석할지, 그리고 분석기의 이름을 무엇으로 할지 지정합니다.
 */
public class GroupPattern implements PatternAnalyzer {

    private final String patternName;
    public final Set<Integer> numberGroup; // 리플렉션 접근을 위해 public으로 변경

    // 그룹에 속한 각 번호의 출현 횟수
    private final int[] groupNumberAppearanceCount = new int[46];
    // 그룹 번호가 나온 전체 횟수
    private int totalGroupNumberOccurrences = 0;

    /**
     * @param patternName 분석기 이름 (예: "소수", "3의배수")
     * @param numberGroup 분석할 숫자 그룹 Set
     */
    public GroupPattern(String patternName, Set<Integer> numberGroup) {
        this.patternName = patternName;
        this.numberGroup = numberGroup;
    }

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.getWinningNumbers()) {
                if (numberGroup.contains(num)) {
                    groupNumberAppearanceCount[num]++;
                    totalGroupNumberOccurrences++;
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 현재 번호가 해당 그룹에 속하는지 확인
        if (numberGroup.contains(number)) {
            if (totalGroupNumberOccurrences == 0) {
                return 0;
            }
            // 전체 그룹 번호 출현 횟수 대비 이 번호의 출현 확률 (120 -> 150)
            double probability = (double) groupNumberAppearanceCount[number] / totalGroupNumberOccurrences;
            return probability * 150;
        }
        return 0;
    }

    @Override
    public String getName() {
        return patternName;
    }
}
