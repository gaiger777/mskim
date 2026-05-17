import java.util.List;

/**
 * 모든 로또 패턴 분석기의 기본 구조를 정의하는 인터페이스입니다.
 * 각 패턴 분석기는 이 인터페이스를 구현하여 독립적인 분석 로직을 가집니다.
 */
public interface PatternAnalyzer {

    /**
     * 전체 당첨 내역을 분석하여 패턴의 통계적 확률 또는 특성을 계산합니다.
     * 이 메소드는 주 분석 루프가 시작되기 전에 한 번만 호출됩니다.
     *
     * @param drawHistory 전체 당첨 내역 리스트
     */
    void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory);

    /**
     * 분석된 통계를 바탕으로 특정 번호에 대한 점수를 계산합니다.
     * 점수는 확률, 빈도, 또는 특정 규칙에 기반한 가중치가 될 수 있습니다.
     *
     * @param number       점수를 계산할 대상 번호 (1~45)
     * @param latestDraw   가장 최근 회차의 당첨 정보
     * @param drawHistory  전체 당첨 내역 (필요시 참조)
     * @return 해당 번호에 대한 패턴 점수. 패턴에 해당하지 않으면 0을 반환합니다.
     */
    double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw, List<LottoPatternAnalyzer.LottoDraw> drawHistory);

    /**
     * 분석기의 이름을 반환합니다. 이 이름은 결과 출력 시 태그로 사용됩니다.
     * (예: "이월수", "이웃수")
     *
     * @return 패턴 분석기의 이름
     */
    String getName();
}
