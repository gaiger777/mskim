import java.util.List;

/**
 * 최근 출현 빈도(Hot Number) 패턴 분석기입니다.
 * 최근 5회차 동안 자주 나타난 번호가 계속해서 나올 경향을 분석합니다.
 */
public class RecentHotPattern implements PatternAnalyzer {

    private final int RECENT_WEEKS = 5;
    private final int[] recentAppearanceCount = new int[46];
    private int totalRecentNumbersDrawn = 0;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 이 패턴은 분석 시점이 아닌, 점수 계산 시점에서 동적으로 계산되므로
        // 사전 분석(analyze) 단계에서는 특별한 작업을 수행하지 않습니다.
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int historySize = drawHistory.size();
        if (historySize < RECENT_WEEKS) {
            return 0;
        }

        // 최근 5회차 데이터만 추출
        List<LottoPatternAnalyzer.LottoDraw> recentDraws = drawHistory.subList(historySize - RECENT_WEEKS, historySize);

        int hotCount = 0;
        for (LottoPatternAnalyzer.LottoDraw draw : recentDraws) {
            if (draw.getWinningNumbers().contains(number)) {
                hotCount++;
            }
        }

        // 최근 5주간 나온 횟수 자체를 점수로 사용 (강력한 가중치)
        // 2번 이상 나왔을 경우 더 큰 보너스 부여 (60.0 -> 70.0, 120.0 -> 140.0)
        double score = hotCount * 70.0;
        if (hotCount >= 2) {
            score += (hotCount - 1) * 140.0;
        }
        
        return score;
    }

    @Override
    public String getName() {
        return "최근강세";
    }
}
