import java.util.*;
import java.util.stream.Collectors;

/**
 * 유전 프로그래밍(GP) 기반 커스텀 패턴 자동 발굴기.
 *
 * 표현식 트리(피연산자 = 과거 R회차의 위치값/상수, 연산 = + − × |차| mod)를 진화시켜,
 * "직전 회차들에 적용하면 다음 회차 당첨에 들어가는" 변환을 자동으로 찾아낸다.
 * 출력 정수는 wrap으로 1~45에 매핑(46→1, 47→2 …).
 *
 *  · gp 모드: 학습구간(앞 70%)에서 진화 → 검증구간(뒤 30%, 미학습)에서 적중률을 대비 출력.
 *             검색공간이 커 in-sample은 높게 나오지만 out-of-sample은 ≈무작위로 수렴 →
 *             과적합을 눈으로 확인시키는 게 핵심.
 *  · injectIntoEngine(): 전체 이력으로 진화한 패턴을 CustomPatternMiner 풀에 주입 →
 *             기존 CustomPatternAnalyzer를 통해 엔진/게임/PPT에 자동 반영.
 */
public class GPPatternMiner {

    static final double BASELINE = 6.0 / 45.0;
    static int R = 10;        // 과거 회차 조망(lookback)
    static int POP = 150, GENS = 25, MAXD = 4, KEEP = 15;
    static final int N_OPS = 5; // 0:+ 1:- 2:* 3:|차| 4:mod

    static int applyOp(int o, int a, int b) {
        switch (o) {
            case 0: return a + b;
            case 1: return a - b;
            case 2: return a * b;
            case 3: return Math.abs(a - b);
            default: return b == 0 ? a : a % b;
        }
    }

    static String opSym(int o) { return new String[]{"+", "-", "*", "~", "%"}[o]; }

    // ── 표현식 트리 노드 ──────────────────────────────────────────────────
    abstract static class Node {
        abstract int eval(List<LottoPatternAnalyzer.LottoDraw> h);
        abstract Node copy();
    }
    static final class Const extends Node {
        final int c; Const(int c) { this.c = c; }
        int eval(List<LottoPatternAnalyzer.LottoDraw> h) { return c; }
        Node copy() { return new Const(c); }
        public String toString() { return Integer.toString(c); }
    }
    static final class Pos extends Node {       // t회 전 회차의 i번째 정렬수
        final int t, i; Pos(int t, int i) { this.t = t; this.i = i; }
        int eval(List<LottoPatternAnalyzer.LottoDraw> h) {
            int idx = h.size() - 1 - t;
            return (idx < 0) ? 0 : h.get(idx).nums[i];
        }
        Node copy() { return new Pos(t, i); }
        public String toString() { return "d" + t + "_" + (i + 1); }
    }
    static final class Bin extends Node {
        final int op; Node l, r; Bin(int op, Node l, Node r) { this.op = op; this.l = l; this.r = r; }
        int eval(List<LottoPatternAnalyzer.LottoDraw> h) { return applyOp(op, l.eval(h), r.eval(h)); }
        Node copy() { return new Bin(op, l.copy(), r.copy()); }
        public String toString() { return "(" + l + opSym(op) + r + ")"; }
    }

    // ── 트리 생성/조작 ────────────────────────────────────────────────────
    static Node randTerminal(Random rnd) {
        return rnd.nextDouble() < 0.7 ? new Pos(rnd.nextInt(R), rnd.nextInt(6))
                                      : new Const(1 + rnd.nextInt(9));
    }
    static Node randTree(Random rnd, int depth) {
        if (depth <= 0 || rnd.nextDouble() < 0.4) return randTerminal(rnd);
        return new Bin(rnd.nextInt(N_OPS), randTree(rnd, depth - 1), randTree(rnd, depth - 1));
    }

    static int count(Node n) { return (n instanceof Bin b) ? 1 + count(b.l) + count(b.r) : 1; }

    // 전위순회 target번째 노드를 반환
    static Node nodeAt(Node n, int target, int[] c) {
        if (c[0] == target) { c[0]++; return n; }
        c[0]++;
        if (n instanceof Bin b) {
            Node x = nodeAt(b.l, target, c); if (x != null) return x;
            return nodeAt(b.r, target, c);
        }
        return null;
    }
    // 전위순회 target번째 노드를 sub로 교체한 복사본
    static Node replaceAt(Node n, int target, int[] c, Node sub) {
        if (c[0] == target) { c[0]++; return sub.copy(); }
        c[0]++;
        if (n instanceof Bin b) {
            Node nl = replaceAt(b.l, target, c, sub);
            Node nr = replaceAt(b.r, target, c, sub);
            return new Bin(b.op, nl, nr);
        }
        return n.copy();
    }
    static Node crossover(Node a, Node b, Random rnd) {
        Node donor = nodeAt(b, rnd.nextInt(count(b)), new int[]{0});
        return replaceAt(a, rnd.nextInt(count(a)), new int[]{0}, donor);
    }
    static Node mutate(Node a, Random rnd) {
        return replaceAt(a, rnd.nextInt(count(a)), new int[]{0}, randTree(rnd, 2));
    }

    // ── 적합도: 학습 전이에서 적중 수 ─────────────────────────────────────
    static int fitness(Node t, List<LottoPatternAnalyzer.LottoDraw> hist, int[] trainIdx) {
        int hits = 0;
        for (int j : trainIdx) {
            if (j < R) continue;
            int v = CustomPatternMiner.wrap(t.eval(hist.subList(0, j)));
            if (hist.get(j).getWinningNumbers().contains(v)) hits++;
        }
        return hits;
    }

    // ── 진화 루프 → 상위 KEEP개 (서로 다른 식) ────────────────────────────
    static List<Node> evolve(List<LottoPatternAnalyzer.LottoDraw> hist, int[] trainIdx, long seed) {
        Random rnd = new Random(seed);
        List<Node> pop = new ArrayList<>();
        for (int i = 0; i < POP; i++) pop.add(randTree(rnd, MAXD));

        for (int g = 0; g < GENS; g++) {
            int[] fit = new int[POP];
            for (int i = 0; i < POP; i++) fit[i] = fitness(pop.get(i), hist, trainIdx);
            Integer[] order = sortedByFitness(pop, fit);

            List<Node> next = new ArrayList<>();
            for (int e = 0; e < 4 && e < POP; e++) next.add(pop.get(order[e]).copy()); // 엘리트
            while (next.size() < POP) {
                Node p1 = pop.get(tournament(fit, rnd));
                Node p2 = pop.get(tournament(fit, rnd));
                Node child = crossover(p1, p2, rnd);
                if (rnd.nextDouble() < 0.25) child = mutate(child, rnd);
                if (count(child) > 40) child = randTree(rnd, MAXD); // 비대화 방지
                next.add(child);
            }
            pop = next;
        }
        // 최종 채점 후 상위 KEEP개(중복 식 제거)
        int[] fit = new int[POP];
        for (int i = 0; i < POP; i++) fit[i] = fitness(pop.get(i), hist, trainIdx);
        Integer[] order = sortedByFitness(pop, fit);
        List<Node> best = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int idx : order) {
            String key = pop.get(idx).toString();
            if (seen.add(key)) best.add(pop.get(idx));
            if (best.size() >= KEEP) break;
        }
        return best;
    }

    static Integer[] sortedByFitness(List<Node> pop, int[] fit) {
        Integer[] order = new Integer[pop.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        // 적합도 내림차순, 동률은 작은 트리 우선(파시모니)
        Arrays.sort(order, (a, b) -> fit[a] != fit[b] ? fit[b] - fit[a]
                : count(pop.get(a)) - count(pop.get(b)));
        return order;
    }
    static int tournament(int[] fit, Random rnd) {
        int best = rnd.nextInt(fit.length);
        for (int k = 0; k < 2; k++) { int c = rnd.nextInt(fit.length); if (fit[c] > fit[best]) best = c; }
        return best;
    }

    static int[] range(int lo, int hi) {  // [lo, hi)
        int[] a = new int[Math.max(0, hi - lo)];
        for (int i = 0; i < a.length; i++) a[i] = lo + i;
        return a;
    }

    // 트리를 CustomPatternMiner.Pattern으로 래핑
    static CustomPatternMiner.Pattern toPattern(Node t) {
        final Node tree = t;
        return new CustomPatternMiner.P("GP:" + tree,
                h -> h.size() < R ? Set.of() : Set.of(CustomPatternMiner.wrap(tree.eval(h))));
    }

    // ── 엔진 주입: 과거 구간(엔진 z 측정창 이전)에서만 진화 → z 편향 제거 ──
    // 엔진의 z 가중치는 최근 WF_TRAIN_WEEKS(200)회로 측정된다. 진화를 그 구간과
    // 겹치지 않는 그 이전 구간으로 제한하면, 최근 200회는 패턴에게 out-of-sample이
    // 되어 z가 부풀려지지 않는다(look-ahead 제거). 그 대가로 라이브 패턴은 다소 stale.
    private static boolean injected = false;
    static void injectIntoEngine(List<LottoPatternAnalyzer.LottoDraw> history) {
        if (injected) return;
        injected = true;
        int size = history.size();
        int trainEnd = size - LottoPatternAnalyzer.WF_TRAIN_WEEKS; // 최근 측정창은 진화에서 제외
        int[] trainIdx = range(Math.max(R, trainEnd - 300), trainEnd);
        if (trainIdx.length < 20) return;
        for (Node t : evolve(history, trainIdx, 42L))
            CustomPatternMiner.evolved.add(toPattern(t));
    }

    // ── gp 모드: 학습/검증 분리로 과적합 폭로 + 라이브 후보 ──────────────
    public static void run(List<LottoPatternAnalyzer.LottoDraw> history) {
        int size = history.size();
        int trainEnd = R + (int) ((size - R) * 0.70);
        int[] trainIdx = range(R, trainEnd);
        int[] holdIdx = range(trainEnd, size);

        System.out.println("==========================================================");
        System.out.printf ("   유전 프로그래밍 패턴 발굴 (R=%d, POP=%d, GENS=%d, 깊이≤%d)\n", R, POP, GENS, MAXD);
        System.out.printf ("   학습구간: 회차[%d..%d) (%d개)  검증구간: [%d..%d) (%d개)\n",
                R, trainEnd, trainIdx.length, trainEnd, size, holdIdx.length);
        System.out.println("==========================================================");

        List<Node> best = evolve(history, trainIdx, 7L);

        System.out.println("\n[발굴된 상위 패턴 — in-sample(학습) vs out-of-sample(검증) 적중률]");
        System.out.println("----------------------------------------------------------");
        System.out.println("식                              | 학습적중 | 검증적중 | (식 길이)");
        System.out.println("----------------------------------------------------------");
        int show = Math.min(10, best.size());
        for (int i = 0; i < show; i++) {
            Node t = best.get(i);
            double inS = 100.0 * fitness(t, history, trainIdx) / Math.max(1, trainIdx.length);
            double outS = 100.0 * fitness(t, history, holdIdx) / Math.max(1, holdIdx.length);
            String s = t.toString();
            if (s.length() > 30) s = s.substring(0, 29) + "…";
            System.out.printf("%-31s | %6.1f%% | %6.1f%% | (%d)\n", s, inS, outS, count(t));
        }

        // 풀링: 상위 K개 합집합의 검증구간 적중 vs 무작위
        int K = Math.min(CustomPatternMiner.K, best.size());
        double poolHit = 0, poolSize = 0;
        for (int j : holdIdx) {
            Set<Integer> pool = new HashSet<>();
            for (int k = 0; k < K; k++) pool.add(CustomPatternMiner.wrap(best.get(k).eval(history.subList(0, j))));
            poolSize += pool.size();
            poolHit += pool.stream().filter(history.get(j).getWinningNumbers()::contains).count();
        }
        int hn = holdIdx.length;
        System.out.println("----------------------------------------------------------");
        System.out.printf ("상위 %d개 합집합 검증구간: 평균 풀크기 %.1f / 평균 적중 %.2f개\n",
                K, poolSize / hn, poolHit / hn);
        System.out.printf ("무작위 번호(동일 풀크기) 기대: 평균 적중 %.2f개\n", (poolSize / hn) * BASELINE);
        System.out.println("※ 학습적중은 높아도 검증적중이 무작위 수준이면 과적합(예측력 없음)입니다.");

        // 라이브: 전체 이력으로 진화 → 다음 회차 후보
        int latestNo = history.get(size - 1).drawNo;
        List<Node> live = evolve(history, range(Math.max(R, size - 150), size), 42L);
        Set<Integer> pool = new TreeSet<>();
        for (int k = 0; k < Math.min(CustomPatternMiner.K, live.size()); k++)
            pool.add(CustomPatternMiner.wrap(live.get(k).eval(history)));
        System.out.printf("\n▶ %d회차 GP 후보군(상위 %d식 합집합): %s\n", latestNo + 1,
                Math.min(CustomPatternMiner.K, live.size()),
                pool.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        System.out.println("  (이 패턴들은 엔진/게임/PPT에 '커스텀패턴' 태그로 자동 투입됩니다.)");
        System.out.println("==========================================================");
    }
}
