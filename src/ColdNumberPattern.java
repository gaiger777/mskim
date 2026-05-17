import java.util.List;

/**
 * 장기 미출현(Cold Number) 패턴 분석기입니다.
 * 15주 이상 오랫동안 나오지 않은 번호가 다시 출현하는 경향을 분석합니다.
 */
public class ColdNumberPattern implements PatternAnalyzer {

    // 마지막으로 각 번호가 나타난 회차를 저장
    private final int[] lastSeenDraw = new int[46];
    // 장기 미출현(15주 초과) 상태에서 해당 번호가 나온 횟수
    private final int[] coldAppearanceCount = new int[46];
    private int totalColdOccurrences = 0;
    private static final int COLD_THRESHOLD = 15;

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        // 1. 최신 상태의 lastSeenDraw 계산 (getScore용)
        for (LottoPatternAnalyzer.LottoDraw draw : drawHistory) {
            for (int num : draw.getWinningNumbers()) {
                lastSeenDraw[num] = draw.drawNo;
            }
        }

        // 2. 과거 이력 시뮬레이션
        for (int i = 1; i < drawHistory.size(); i++) {
            LottoPatternAnalyzer.LottoDraw currentDraw = drawHistory.get(i);
            LottoPatternAnalyzer.LottoDraw previousDraw = drawHistory.get(i - 1);

            for (int num = 1; num <= 45; num++) {
                // 이 번호가 마지막으로 나온 회차 찾기
                int lastSeen = 0;
                for (int j = i - 1; j >= 0; j--) {
                    if (drawHistory.get(j).getWinningNumbers().contains(num)) {
                        lastSeen = drawHistory.get(j).drawNo;
                        break;
                    }
                }

                if (lastSeen > 0) {
                    int weeksMissing = previousDraw.drawNo - lastSeen;
                    // 장기 미출현 상태에서 현재 회차에 나왔는지 확인
                    if (weeksMissing > COLD_THRESHOLD) {
                        if (currentDraw.getWinningNumbers().contains(num)) {
                            coldAppearanceCount[num]++;
                            totalColdOccurrences++;
                        }
                    }
                }
            }
        }
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        int lastSeen = lastSeenDraw[number];
        if (lastSeen == 0) return 0; // 한 번도 안 나온 번호 (초기 데이터 부족 시)

        int weeksMissing = latestDraw.drawNo - lastSeen;

        // 현재 번호가 장기 미출현 상태인지 확인
        if (weeksMissing > COLD_THRESHOLD) {
            if (totalColdOccurrences == 0) {
                return 0;
            }
            // 과거 장기 미출현 상태에서 이 번호가 나온 빈도 확률
            double probability = (double) coldAppearanceCount[number] / totalColdOccurrences;
            
            // 미출현 기간이 길어질수록 가중치 증가 (선형)
            // 기본 점수 + (초과 주수 * 보너스) (600 -> 700, 6.0 -> 7.0)
            double baseScore = probability * 700; 
            double bonus = (weeksMissing - COLD_THRESHOLD) * 7.0;
            
            return baseScore + bonus;
        }

        return 0;
    }

    @Override
    public String getName() {
        return "장기미출현";
    }
}
