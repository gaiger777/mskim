import java.util.*;
import java.util.stream.Collectors;

/**
 * "연승 선택" 패턴 분석기.
 *
 * 아이디어(사용자): 직전 S회 예측에서 연속으로 ≥1개 적중한 패턴은 "최근 잘 맞는 중"이므로
 * 다음 회차에도 그 패턴을 적용한다. (예: →1225 적중 ✓, →1226 적중 ✓ 인 패턴으로 1227 예측)
 *
 * 패턴 풀은 CustomPatternMiner.patterns() (고정 변환 + GP 진화 변환)를 공유한다.
 * 자격 패턴(연속 S회 적중)들을 최신 회차에 적용한 후보 합집합을 점수로 내보내
 * 엔진/게임생성/PPT에 자동 반영된다.
 *
 * ※ 핫핸드 주의: 독립 난수에서 "최근 연속 적중"은 다음 확률을 높이지 않는다.
 *   `streak` 모드의 walk-forward(out-of-sample)로 실제 효과를 정직하게 확인할 것.
 */
public class StreakPatternAnalyzer implements PatternAnalyzer {

    static int S = 2; // 요구 연속 적중 횟수
    private Map<Integer, Integer> votes = Map.of();

    @Override
    public void analyze(List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        votes = computeVotes(drawHistory, drawHistory.size(), S);
    }

    @Override
    public double getScore(int number, LottoPatternAnalyzer.LottoDraw latestDraw,
                           List<LottoPatternAnalyzer.LottoDraw> drawHistory) {
        return votes.getOrDefault(number, 0);
    }

    @Override
    public String getName() { return "연승패턴"; }

    // 패턴 p가, 회차 n 직전 S개 예측(→n-1 … →n-S)에서 모두 ≥1개 적중했는가? (n의 당첨은 미사용)
    static boolean hasStreak(CustomPatternMiner.Pattern p,
                             List<LottoPatternAnalyzer.LottoDraw> h, int n, int s) {
        for (int k = 1; k <= s; k++) {
            int j = n - k;                       // prefix(0,j)로 회차 j 예측
            if (j < 1) return false;
            Set<Integer> c = p.apply(h.subList(0, j));
            if (c.isEmpty()) return false;
            Set<Integer> win = h.get(j).getWinningNumbers();
            boolean hit = false;
            for (int x : c) if (win.contains(x)) { hit = true; break; }
            if (!hit) return false;
        }
        return true;
    }

    // 회차 n 예측 시 자격(연속 S회 적중) 패턴 목록
    static List<CustomPatternMiner.Pattern> qualifying(List<LottoPatternAnalyzer.LottoDraw> h, int n, int s) {
        List<CustomPatternMiner.Pattern> q = new ArrayList<>();
        for (CustomPatternMiner.Pattern p : CustomPatternMiner.patterns())
            if (hasStreak(p, h, n, s)) q.add(p);
        return q;
    }

    // 자격 패턴들을 prefix(0,n)에 적용한 후보 합집합 + 투표수
    static Map<Integer, Integer> computeVotes(List<LottoPatternAnalyzer.LottoDraw> h, int n, int s) {
        Map<Integer, Integer> v = new HashMap<>();
        for (CustomPatternMiner.Pattern p : qualifying(h, n, s))
            for (int x : p.apply(h.subList(0, n))) v.merge(x, 1, Integer::sum);
        return v;
    }

    // ── streak 모드: 연승 전략 walk-forward + 라이브 후보 ─────────────────
    public static void run(List<LottoPatternAnalyzer.LottoDraw> history) {
        GPPatternMiner.injectIntoEngine(history); // GP 진화 패턴까지 풀에 포함
        int size = history.size();
        int latestNo = history.get(size - 1).drawNo;

        System.out.println("==========================================================");
        System.out.printf ("   연승 선택 전략 (연속 %d회 적중 패턴 → 다음 회차 적용)\n", S);
        System.out.printf ("   패턴 풀: %d개 (고정 + GP 진화)\n", CustomPatternMiner.patterns().size());
        System.out.println("==========================================================");

        // 라이브: 1227 자격 패턴 + 후보
        List<CustomPatternMiner.Pattern> q = qualifying(history, size, S);
        System.out.printf("\n[%d회차 — 직전 %d회 연속 적중 패턴 %d개]\n", latestNo + 1, S, q.size());
        for (CustomPatternMiner.Pattern p : q) {
            List<Integer> c = new ArrayList<>(p.apply(history));
            Collections.sort(c);
            String nm = p.name(); if (nm.length() > 28) nm = nm.substring(0, 27) + "…";
            System.out.printf("  %-29s → %s\n", nm,
                    c.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        Map<Integer, Integer> live = computeVotes(history, size, S);
        List<Integer> pool = new ArrayList<>(live.keySet());
        pool.sort((a, b) -> live.get(b) != live.get(a) ? live.get(b) - live.get(a) : a - b);
        System.out.printf("▶ %d회차 후보군(투표순): %s\n", latestNo + 1,
                pool.isEmpty() ? "(자격 패턴 없음)" :
                        pool.stream().map(n -> live.get(n) > 1 ? n + "(" + live.get(n) + "표)" : String.valueOf(n))
                                .collect(Collectors.joining(", ")));

        // walk-forward 백테스트
        int first = CustomPatternMiner.MIN_PREFIX + GPPatternMiner.R + S + 1;
        int eval = Math.min(200, size - first);
        if (eval <= 0) { System.out.println("\n(백테스트할 회차 부족)"); return; }
        int start = size - eval;
        double hit = 0, psize = 0, any = 0, covered = 0;
        for (int n = start; n < size; n++) {
            Map<Integer, Integer> v = computeVotes(history, n, S);
            if (!v.isEmpty()) covered++;
            Set<Integer> win = history.get(n).getWinningNumbers();
            int h = (int) v.keySet().stream().filter(win::contains).count();
            hit += h; psize += v.size(); any += (h > 0 ? 1 : 0);
        }
        int wk = size - start;
        System.out.println("\n==========================================================");
        System.out.printf ("   연승 전략 walk-forward (최근 %d회차, 연속 %d 요구)\n", wk, S);
        System.out.println("==========================================================");
        System.out.printf ("자격 패턴 존재 회차: %.0f/%d (%.1f%%)\n", covered, wk, 100.0 * covered / wk);
        System.out.printf ("평균 후보 풀크기 : %.2f개\n", psize / wk);
        System.out.printf ("평균 적중수      : %.3f개  (적중률 ≥1개: %.1f%%)\n", hit / wk, 100.0 * any / wk);
        System.out.printf ("무작위 번호(동일 풀크기) 기대 적중수: %.3f개\n", (psize / wk) * (6.0 / 45.0));
        System.out.println("※ 연승 전략 적중수가 무작위 기대치를 유의하게 못 넘으면 '핫핸드'는 노이즈입니다.");
        System.out.println("==========================================================");
    }
}
