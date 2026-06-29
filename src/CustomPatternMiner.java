import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 커스텀 패턴 발굴기 — "롤링 최근-최고 패턴 선택".
 *
 * 변환 패턴(위치산술·그리드대각선·회차간델타·끝수)을 자동 열거한 뒤,
 * 각 예측 시점마다 "직전 W개 전이에서 가장 잘 맞은 상위 K개 패턴"을 골라
 * 최신 회차에 적용해 다음 회차 후보군을 만든다.
 *
 * 선택이 과거 데이터(직전 W전이)로만 이뤄지므로 look-ahead가 없고,
 * 전략 전체를 walk-forward로 정직하게 백테스트할 수 있다.
 *
 * 실행:  java -cp out/production/mskim LottoPatternAnalyzer mine [W] [K]
 */
public class CustomPatternMiner {

    static final double BASELINE = 6.0 / 45.0; // 번호 1개가 다음 6개 당첨에 포함될 확률
    static int W = 10;          // 롤링 선택 창 (전이 수)
    static int K = 5;           // 매 시점 선택할 상위 패턴 수
    static final int GRID_ROWS = 6;
    static final int MIN_PREFIX = GRID_ROWS; // 그리드 패턴에 필요한 최소 과거 회차

    static int wrap(int x) { int m = ((x - 1) % 45 + 45) % 45; return m + 1; } // 1~45 (46→1, 47→2)

    // ── 패턴 추상화 ───────────────────────────────────────────────────────
    interface Pattern { String name(); Set<Integer> apply(List<LottoPatternAnalyzer.LottoDraw> h); }

    static final class P implements Pattern {
        final String name;
        final Function<List<LottoPatternAnalyzer.LottoDraw>, Set<Integer>> fn;
        P(String name, Function<List<LottoPatternAnalyzer.LottoDraw>, Set<Integer>> fn) { this.name = name; this.fn = fn; }
        public String name() { return name; }
        public Set<Integer> apply(List<LottoPatternAnalyzer.LottoDraw> h) { return fn.apply(h); }
    }

    static int[] last(List<LottoPatternAnalyzer.LottoDraw> h) { return h.get(h.size() - 1).nums; }

    // 패턴은 무상태(stateless)라 한 번만 생성해 재사용 (walk-forward에서 반복 호출).
    private static List<Pattern> PATTERNS;
    // GP로 진화해 주입된 패턴 (GPPatternMiner.injectIntoEngine). 비면 고정 풀만 사용.
    static final List<Pattern> evolved = new ArrayList<>();
    private static List<Pattern> COMBINED;
    static List<Pattern> patterns() {
        if (COMBINED == null) {
            if (PATTERNS == null) PATTERNS = buildPatterns();
            COMBINED = new ArrayList<>(PATTERNS);
            COMBINED.addAll(evolved); // 주입은 엔진 사용 전에 1회 완료된다고 가정
        }
        return COMBINED;
    }

    static void replaceEvolved(Collection<Pattern> patterns) {
        evolved.clear();
        evolved.addAll(patterns);
        COMBINED = null;
    }

    // ── 변환 가족 자동 열거 ───────────────────────────────────────────────
    static List<Pattern> buildPatterns() {
        List<Pattern> ps = new ArrayList<>();

        // 1) 위치 산술: 정렬 6수의 모든 쌍 합/차
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                final int a = i, b = j;
                ps.add(new P(String.format("위치합 d%d+d%d", a + 1, b + 1),
                        h -> Set.of(wrap(last(h)[a] + last(h)[b]))));
                ps.add(new P(String.format("위치차 |d%d-d%d|", b + 1, a + 1),
                        h -> Set.of(wrap(Math.abs(last(h)[a] - last(h)[b])))));
            }
        }
        // 위치 인접차 집합 (소수 집합 출력)
        ps.add(new P("인접차 집합", h -> {
            int[] d = last(h); Set<Integer> s = new HashSet<>();
            for (int i = 0; i < 5; i++) s.add(wrap(Math.abs(d[i + 1] - d[i])));
            return s;
        }));

        // 2) 그리드 대각선: 최근 6회 6×6 행렬, ↘/↙ 토러스 대각선 합
        for (int k = 0; k < 6; k++) {
            final int off = k;
            ps.add(new P(String.format("↘대각합 k%d", off), h -> diag(h, off, +1)));
            ps.add(new P(String.format("↙대각합 k%d", off), h -> diag(h, off, -1)));
        }
        ps.add(new P("↘대각합 전체", h -> allDiag(h, +1))); // 6-집합 출력
        ps.add(new P("↙대각합 전체", h -> allDiag(h, -1)));

        // 3) 회차간 델타: 최신·직전 회차 위치별 합/차
        for (int i = 0; i < 6; i++) {
            final int p = i;
            ps.add(new P(String.format("델타합 p%d", p + 1), h -> {
                if (h.size() < 2) return Set.of();
                return Set.of(wrap(last(h)[p] + h.get(h.size() - 2).nums[p]));
            }));
            ps.add(new P(String.format("델타차 p%d", p + 1), h -> {
                if (h.size() < 2) return Set.of();
                return Set.of(wrap(Math.abs(last(h)[p] - h.get(h.size() - 2).nums[p])));
            }));
        }

        // 4) 끝수/자릿수
        ps.add(new P("끝수합", h -> { int s = 0; for (int x : last(h)) s += x % 10; return Set.of(wrap(s)); }));
        ps.add(new P("전체합 mod45", h -> { int s = 0; for (int x : last(h)) s += x; return Set.of(wrap(s)); }));
        ps.add(new P("최소+최대", h -> { int[] d = last(h); return Set.of(wrap(d[0] + d[5])); }));
        ps.add(new P("중앙합 d3+d4", h -> { int[] d = last(h); return Set.of(wrap(d[2] + d[3])); }));
        ps.add(new P("끝수 곱집합", h -> {
            int[] d = last(h); Set<Integer> s = new HashSet<>();
            for (int x : d) s.add(wrap((x % 10) * (x % 10 == 0 ? 1 : x % 10)));
            return s;
        }));
        return ps;
    }

    // ↘(dir=+1)/↙(dir=-1) 토러스 대각선 1개 합 → wrap
    static Set<Integer> diag(List<LottoPatternAnalyzer.LottoDraw> h, int off, int dir) {
        if (h.size() < GRID_ROWS) return Set.of();
        int sum = 0;
        for (int r = 0; r < GRID_ROWS; r++) {
            int c = ((off + dir * r) % 6 + 6) % 6;
            sum += h.get(h.size() - 1 - r).nums[c];
        }
        return Set.of(wrap(sum));
    }

    // 모든 대각선 합의 집합
    static Set<Integer> allDiag(List<LottoPatternAnalyzer.LottoDraw> h, int dir) {
        if (h.size() < GRID_ROWS) return Set.of();
        Set<Integer> s = new HashSet<>();
        for (int k = 0; k < 6; k++) s.addAll(diag(h, k, dir));
        return s;
    }

    // ── 채점 / 선택 ───────────────────────────────────────────────────────
    static final class Scored { Pattern p; double lift; int hits, emitted; }

    // 패턴 p를, 회차 n을 예측하기 직전의 W개 전이([n-W, n-1] 예측)로 채점.
    static Scored score(Pattern p, List<LottoPatternAnalyzer.LottoDraw> history, int n) {
        int hits = 0, emitted = 0;
        for (int j = Math.max(MIN_PREFIX, n - W); j < n; j++) {
            Set<Integer> c = p.apply(history.subList(0, j));
            emitted += c.size();
            Set<Integer> win = history.get(j).getWinningNumbers();
            for (int x : c) if (win.contains(x)) hits++;
        }
        Scored s = new Scored();
        s.p = p; s.hits = hits; s.emitted = emitted;
        s.lift = emitted > 0 ? hits / (emitted * BASELINE) : 0;
        return s;
    }

    // 회차 n 예측을 위한 상위 K개 패턴 (lift 내림차순, 동률은 적중수).
    static List<Scored> selectTopK(List<Pattern> pats, List<LottoPatternAnalyzer.LottoDraw> history, int n) {
        List<Scored> all = new ArrayList<>();
        for (Pattern p : pats) { Scored s = score(p, history, n); if (s.emitted > 0) all.add(s); }
        all.sort((x, y) -> x.lift != y.lift ? Double.compare(y.lift, x.lift) : Integer.compare(y.hits, x.hits));
        return all.subList(0, Math.min(K, all.size()));
    }

    // 선택된 패턴들을 prefix(0,n)에 적용한 후보 합집합 + 번호별 투표수.
    static Map<Integer, Integer> poolVotes(List<Scored> sel, List<LottoPatternAnalyzer.LottoDraw> history, int n) {
        Map<Integer, Integer> votes = new HashMap<>();
        for (Scored s : sel)
            for (int x : s.p.apply(history.subList(0, n)))
                votes.merge(x, 1, Integer::sum);
        return votes;
    }

    // ── 메인 ──────────────────────────────────────────────────────────────
    public static void run(List<LottoPatternAnalyzer.LottoDraw> history) {
        List<Pattern> pats = patterns();
        int size = history.size();
        int latestNo = history.get(size - 1).drawNo;
        int nextNo = latestNo + 1;

        System.out.println("==========================================================");
        System.out.printf ("   커스텀 패턴 발굴기 — 롤링 최근-최고 선택 (W=%d, K=%d)\n", W, K);
        System.out.printf ("   변환 패턴 풀: %d개\n", pats.size());
        System.out.println("==========================================================");

        // 1) 라이브 예측: 다음 회차(nextNo)
        List<Scored> live = selectTopK(pats, history, size);
        System.out.printf("\n[%d회차 예측 — 최근 %d전이 최고 패턴 %d개]\n", nextNo, W, K);
        System.out.println("----------------------------------------------------------");
        System.out.println("선택 패턴            | 최근 lift | 적중/출력 | 적용 후보(→" + latestNo + ")");
        System.out.println("----------------------------------------------------------");
        for (Scored s : live) {
            List<Integer> c = new ArrayList<>(s.p.apply(history));
            Collections.sort(c);
            System.out.printf("%-18s | %7.2f | %5d/%-4d | %s\n",
                    s.p.name(), s.lift, s.hits, s.emitted,
                    c.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        Map<Integer, Integer> votes = poolVotes(live, history, size);
        List<Integer> pool = new ArrayList<>(votes.keySet());
        pool.sort((a, b) -> votes.get(b) != votes.get(a) ? votes.get(b) - votes.get(a) : a - b);
        System.out.printf("\n▶ %d회차 후보군(투표순, 총 %d개): %s\n", nextNo, pool.size(),
                pool.stream().map(n -> votes.get(n) > 1 ? n + "(" + votes.get(n) + "표)" : String.valueOf(n))
                        .collect(Collectors.joining(", ")));

        // 2) walk-forward 백테스트
        backtest(pats, history);
    }

    static void backtest(List<Pattern> pats, List<LottoPatternAnalyzer.LottoDraw> history) {
        int size = history.size();
        int firstN = W + MIN_PREFIX;                 // 선택창+그리드 확보
        int evalCount = Math.min(200, size - firstN);
        if (evalCount <= 0) { System.out.println("\n(백테스트할 회차가 부족합니다.)"); return; }
        int startN = size - evalCount;

        double sHit = 0, sPool = 0, sAny = 0;        // 전략
        double rHit = 0, rPool = 0, rAny = 0;        // 무작위 패턴선택
        double allPatHitRate = 0; int allPatTrials = 0; // 패턴 평균(참고)
        Random rnd = new Random(42);

        for (int n = startN; n < size; n++) {
            Set<Integer> win = history.get(n).getWinningNumbers();

            // 전략: 최근 W전이 최고 K
            List<Scored> sel = selectTopK(pats, history, n);
            Map<Integer, Integer> v = poolVotes(sel, history, n);
            int hit = (int) v.keySet().stream().filter(win::contains).count();
            sHit += hit; sPool += v.size(); sAny += (hit > 0 ? 1 : 0);

            // 무작위 패턴 K선택 (같은 파이프라인, 선택만 무작위)
            List<Pattern> shuffled = new ArrayList<>(pats);
            Collections.shuffle(shuffled, rnd);
            Map<Integer, Integer> rv = new HashMap<>();
            for (int t = 0, taken = 0; t < shuffled.size() && taken < K; t++) {
                Set<Integer> c = shuffled.get(t).apply(history.subList(0, n));
                if (c.isEmpty()) continue;
                for (int x : c) rv.merge(x, 1, Integer::sum);
                taken++;
            }
            int rh = (int) rv.keySet().stream().filter(win::contains).count();
            rHit += rh; rPool += rv.size(); rAny += (rh > 0 ? 1 : 0);
        }

        // 전(全)패턴 1개당 평균 적중률 (참고용 베이스라인)
        for (int n = startN; n < size; n++) {
            Set<Integer> win = history.get(n).getWinningNumbers();
            for (Pattern p : pats) {
                Set<Integer> c = p.apply(history.subList(0, n));
                if (c.isEmpty()) continue;
                allPatTrials++;
                if (c.stream().anyMatch(win::contains)) allPatHitRate++;
            }
        }

        int wk = size - startN;
        System.out.println("\n==========================================================");
        System.out.printf ("   전략 walk-forward 백테스트 (최근 %d회차)\n", wk);
        System.out.println("==========================================================");
        System.out.println("방식                       | 평균 풀크기 | 평균 적중수 | 적중률(≥1개)");
        System.out.println("----------------------------------------------------------");
        System.out.printf ("전략(최근%d 최고%d)          | %9.1f | %9.2f | %7.1f%%\n",
                W, K, sPool / wk, sHit / wk, 100.0 * sAny / wk);
        System.out.printf ("무작위 패턴 %d선택            | %9.1f | %9.2f | %7.1f%%\n",
                K, rPool / wk, rHit / wk, 100.0 * rAny / wk);
        System.out.printf ("무작위 번호(동일 풀크기)     | %9.1f | %9.2f |    -\n",
                sPool / wk, (sPool / wk) * BASELINE);
        System.out.println("----------------------------------------------------------");
        System.out.printf ("참고: 전 패턴 1개당 평균 적중률(≥1개) = %.1f%% (무작위 기대 약 %.1f%%↑, 출력크기 의존)\n",
                100.0 * allPatHitRate / Math.max(1, allPatTrials), 100.0 * BASELINE);
        System.out.println("※ 로또는 독립 난수. '최근 잘 맞던 패턴'이 무작위 패턴선택/무작위 번호를");
        System.out.println("  유의하게 못 이기면, 발굴된 패턴은 노이즈입니다(예측력 없음).");
        System.out.println("==========================================================");
    }
}
