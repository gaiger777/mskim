import java.util.List;
import java.util.Map;

/**
 * 커스텀 패턴 발굴기(CustomPatternMiner)를 추천 엔진에 투입하는 PatternAnalyzer 래퍼.
 *
 * analyze(history): 직전 W전이에서 가장 잘 맞은 상위 K패턴을 골라, 최신 회차에 적용한
 *                   후보군의 번호별 투표수를 계산한다.
 * getScore(n):      해당 번호의 투표수(=점수)를 반환. 후보가 아니면 0(기권).
 *
 * 이 분석기를 getAllAnalyzers()에 등록하면 1~45 종합점수·게임생성·PPT에 자동 반영되고,
 * 엔진의 z-가중치가 이 분석기의 적중률에 따라 비중을 자동 조절한다(노이즈면 자동 강등).
 */
public class CustomPatternAnalyzer implements PatternAnalyzer {

    private Map<Integer, Integer> votes = Map.of();

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        if (drawHistory.size() < CustomPatternMiner.MIN_PREFIX + CustomPatternMiner.W) {
            votes = Map.of();
            return;
        }
        int n = drawHistory.size();
        List<CustomPatternMiner.Scored> sel =
                CustomPatternMiner.selectTopK(CustomPatternMiner.patterns(), drawHistory, n);
        votes = CustomPatternMiner.poolVotes(sel, drawHistory, n);
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw,
                           List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        return votes.getOrDefault(number, 0); // 투표수 = 점수, 후보 아니면 기권(0)
    }

    @Override
    public String getName() {
        return "커스텀패턴";
    }
}
