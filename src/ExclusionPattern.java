import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 제외수 패턴 분석기입니다.
 * 특정 조건에 해당하는 번호가 다음 회차에 나오지 않을 확률을 분석하여 페널티를 부여합니다.
 * 여기서는 '3회 연속 출현 번호'를 제외수 후보로 사용합니다.
 */
public class ExclusionPattern implements PatternAnalyzer {

    private Set<Integer> exclusionCandidates = new HashSet<>();

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 이 패턴은 점수 계산 시점에서 동적으로 결정되므로 사전 분석은 필요 없습니다.
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int historySize = drawHistory.size();
        if (historySize < 3) {
            return 0;
        }

        // 1. 3회 연속 출현 번호 찾기
        LottoPatternAnalyzer.LottoDraw drawN1 = latestDraw;
        LottoPatternAnalyzer.LottoDraw drawN2 = drawHistory.get(historySize - 2);
        LottoPatternAnalyzer.LottoDraw drawN3 = drawHistory.get(historySize - 3);

        Set<Integer> intersection = new HashSet<>(drawN1.getWinningNumbers());
        intersection.retainAll(drawN2.getWinningNumbers());
        intersection.retainAll(drawN3.getWinningNumbers());

        // 2. 페널티 부여
        if (intersection.contains(number)) {
            // 3회 연속 출현한 번호가 4회 연속으로 나올 확률은 극히 드물므로, 매우 강력한 페널티를 부여합니다.
            // 과거 데이터에서 4회 연속 나온 사례가 있는지 확인하여 확률을 계산할 수도 있지만,
            // 워낙 희귀한 경우이므로 여기서는 고정된 큰 음수 점수를 부여합니다.
            return -10000.0;
        }

        return 0;
    }

    @Override
    public String getName() {
        return "제외수";
    }
}
