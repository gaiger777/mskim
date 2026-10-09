#!/usr/bin/env python3
"""로또 패턴 분석 리포트 PPTX 생성기.

LottoPatternAnalyzer 콘솔 출력을 파싱해 분석리포트 PPT를 만든다.
  · 슬라이드1: 패턴별 효과크기(z) 순위 + 순위그룹 분포 + 다음회차 요약
  · 슬라이드2: 1~45 종합 순위 (자동번호에 쓰인 번호 강조)
  · 슬라이드3: 자동 추천 5게임을 로또 용지(7열×7행)에 표기
  · 마지막 슬라이드: 다음회차 동반 출현 통계(번호 N이 나온 다음 회차 상위 3) + 다음회차 적용
                    이월수 통계(번호 N 이월 → 다음 회차 상위 3) + 적용 + 이월 회차 단위 순위
                    보너스 통계(번호 N 보너스 → 다음 회차 당첨 상위 3) + 적용
                    타이밍 통계(패턴별 미출현 기간 → 다음 회차 출현 확률) 요약·표·구간별 확률
  ※ 보너스(번호7)는 당첨번호가 아니다. 모든 집계는 번호1~6만 당첨번호로 쓰고,
    보너스는 '다음 회차 당첨번호로 나오는지'를 보는 조건으로만 사용한다.

외부 라이브러리 없이 stdlib(zipfile)만으로 OOXML(.pptx)을 직접 작성한다.

사용:  python3 make_lotto_ppt.py
출력:  로또_분석리포트.pptx (프로젝트 루트)
"""
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
CP = os.path.join(ROOT, "out", "production", "mskim")
CSV_PATH = os.path.join(ROOT, "src", "resources", "lotto.csv")
GAP_REPEAT_WINDOW = 195

W, H = 12192000, 6858000  # 16:9 슬라이드 (EMU)


# ── 1) Java 실행 → 콘솔 분석리포트 파싱 ───────────────────────────────────
def run_analyzer(extra=None):
    p = subprocess.run(["java", "-cp", CP, "LottoPatternAnalyzer"] + (extra or []),
                       cwd=ROOT, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"Java 실행 실패:\n{p.stderr}")
    return p.stdout


def parse_rankhits(out):
    """rankhits 모드 출력 → (window, [(순위, 적중수, 회차리스트)...])
    재출현 회차 표(파이프 2개)만 파싱. 간격 분석 표(파이프 5개)는 parse_rankgaps가 담당."""
    win, rows = None, []
    for ln in out.splitlines():
        m = re.search(r"대상 (\d+)~(\d+),\s*(\d+)회", ln)
        if m:
            win = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
            continue
        if ln.count("|") != 2:
            continue
        m = re.match(r"\s*(\d+)위\s*\|\s*(\d+)\s*\|\s*(.+)", ln)
        if m:
            draws = [int(x) for x in re.findall(r"\d+", m.group(3))]
            rows.append((int(m.group(1)), int(m.group(2)), draws))
    return win, rows


def parse_rankgaps(out):
    """rankhits의 간격(차이수) 반복 분석 출력 →
       (rank_gaps {순위:(간격문자열, 다음간격, 가능성기호)}, candidates [(순위,번호,다음간격,과거반복수)], 주기보유수)"""
    rank_gaps = {}
    for ln in out.splitlines():
        if ln.count("|") != 5:
            continue
        parts = [p.strip() for p in ln.split("|")]
        m = re.match(r"(\d+)위", parts[0])
        if not m:
            continue
        rk = int(m.group(1))
        poss = parts[5]
        ch = "◎" if poss.startswith("◎") else ("○" if poss.startswith("○") else "-")
        rank_gaps[rk] = (parts[2], parts[4], ch)
    cands = []
    for ln in out.splitlines():
        m = re.search(r"(\d+)위\(번호\s*(\d+)\):\s*다음간격\s*(\d+).*과거\s*(\d+)회", ln)
        if m:
            cands.append((int(m[1]), int(m[2]), int(m[3]), int(m[4])))
    m = re.search(r"주기 보유, 총 (\d+)개", out)
    periodic = int(m[1]) if m else 0
    return rank_gaps, cands, periodic


def parse_patpred(out):
    """customrank의 다음회차 예측 섹션(##NEXT##/행@@/##POOL##) → 지표별 블록 리스트.
       각 블록: {next, metric, rows:[(순위,패턴,적중회차,회당평균,lift,[후보])], pool:[(번호,투표수)]}"""
    blocks, cur = [], None
    for ln in out.splitlines():
        m = re.search(r"##NEXT## (\d+)회 예측 — 지표:(\S+)", ln)
        if m:
            cur = {"next": int(m[1]), "metric": m[2], "rows": [], "pool": []}
            blocks.append(cur)
            continue
        if cur is not None and "@@" in ln:
            p = ln.split("@@")
            if len(p) == 6 and p[0].strip().isdigit():
                cands = [int(x) for x in p[5].split()] if p[5].strip() else []
                cur["rows"].append((int(p[0]), p[1].strip(), p[2].strip(),
                                    p[3].strip(), p[4].strip(), cands))
            continue
        m = re.search(r"##POOL## (.+)", ln)
        if m and cur is not None:
            cur["pool"] = [(int(a), int(b)) for a, b in
                           (t.split(":") for t in m.group(1).split())]
    return blocks


def parse_gapcut(out):
    """gapcut 출력 → (info, rows[{cut,total,hit,avg,rate}], live{cut:[번호]})."""
    info, rows, live, sec = {}, [], {}, None
    for ln in out.splitlines():
        m = re.search(r"##GAPCUT## 대상 (\d+)~(\d+), 창 (\d+)회, 예측회차 (\d+), 무작위기대 ([\d.]+)%", ln)
        if m:
            info = {"lo": int(m[1]), "hi": int(m[2]), "win": int(m[3]),
                    "next": int(m[4]), "rand": m[5]}
            sec = "bt"
            continue
        if ln.startswith("##GAPCUTLIVE##"):
            sec = "live"
            continue
        if ln.startswith("##GAPHIT##"):
            break  # 회차별 상세는 parse_gaphit가 처리
        if "@@" not in ln:
            continue
        p = ln.split("@@")
        if not p[0].strip().isdigit():
            continue
        if sec == "bt" and len(p) == 5:
            rows.append({"cut": int(p[0]), "total": int(p[1]), "hit": int(p[2]),
                         "avg": p[3].strip(), "rate": p[4].strip()})
        elif sec == "live":
            live[int(p[0])] = [] if p[1].strip() == "-" else [int(x) for x in p[1].split(",") if x]
    return info, rows, live


def parse_pathit(out):
    """customrank ##PATHIT## → [(패턴, [(회차, [번호...]) ...]) ...]."""
    rows, insec = [], False
    for ln in out.splitlines():
        if ln.startswith("##PATHIT##"):
            insec = True
            continue
        if not insec:
            continue
        if "@@" not in ln:
            if ln.startswith("##"):
                break
            continue
        pat, detail = ln.split("@@", 1)
        hits = []
        if detail.strip() != "-":
            for tok in detail.split():
                rd, nums = tok.split(":")
                hits.append((int(rd), [int(x) for x in nums.split(",")]))
        rows.append((pat.strip(), hits))
    return rows


def parse_gaphit(out):
    """gapcut ##GAPHIT## → [(회차, [(번호, 반복수) ...], [적중번호 ...]) ...]."""
    rows, insec = [], False
    for ln in out.splitlines():
        if ln.startswith("##GAPHIT##"):
            insec = True
            continue
        if not insec or "@@" not in ln:
            continue
        p = ln.split("@@")
        if not p[0].strip().isdigit():
            continue
        marks = [] if p[1].strip() == "-" else [(int(a), int(b)) for a, b in
                                                (t.split(":") for t in p[1].split(","))]
        hits = [] if p[2].strip() == "-" else [int(x) for x in p[2].split(",")]
        rows.append((int(p[0]), marks, hits))
    return rows


def parse(out):
    r = {}
    m = re.search(r"기준 회차:\s*(\d+)회", out)
    r["base_no"] = int(m.group(1)) if m else None
    r["next_no"] = (r["base_no"] + 1) if r["base_no"] else None

    r["z"] = []  # (순위, 패턴, z, 정확도%, 적중, 예측, 반영)
    for ln in out.splitlines():
        m = re.match(r"\s*(\d+)위\s*\|\s*(.+?)\s*\|\s*([+-][\d.]+)\s*\|\s*"
                     r"([\d.]+)%\s*\|\s*\(\s*(\d+)/\s*(\d+)\)\s*\|\s*(\S+)", ln)
        if m:
            r["z"].append((int(m[1]), m[2], m[3], m[4], int(m[5]), int(m[6]), m[7]))

    r["dist"] = []  # (그룹, 횟수, 비율%)
    for ln in out.splitlines():
        m = re.match(r"(\d+-\d+위)\s*\|\s*(\d+)\s*\|\s*([\d.]+)%", ln)
        if m:
            r["dist"].append((m[1], int(m[2]), m[3]))

    r["rank"] = []  # (순위, 번호, 점수, 태그)
    for ln in out.splitlines():
        # 점수는 음수일 수 있다 — 제외수(ExclusionPattern veto)는 -10000점대로 찍힌다.
        # 마이너스를 빼먹으면 그 행이 통째로 누락되어 랭킹표가 44행만 나온다.
        m = re.search(r"(\d+)위\s*\|\s*(\d+)번\s*\|\s*(-?[\d.]+)점\s*\|\s*(.*)", ln)
        if m:
            r["rank"].append((int(m[1]), int(m[2]), m[3], m[4].strip()))

    m = re.search(r"필출 확률 UP:\s*'([^']+)'[^0-9]*([\d.]+)%", out)
    r["must"] = (m[1], m[2]) if m else None
    m = re.search(r"제외 확률 UP:\s*'([^']+)'[^0-9]*([\d.]+)%", out)
    r["excl"] = (m[1], m[2]) if m else None
    m = re.search(r"최근 (\d+)회차 추천 순위별", out)
    r["dist_weeks"] = m[1] if m else "?"

    r["games"] = []  # (라벨, [번호6])
    for ln in out.splitlines():
        m = re.search(r"\[(.) 게임\]\s*([\d,\s]+?)\s*\(", ln)
        if m:
            r["games"].append((m[1], sorted(int(x) for x in m[2].split(","))))
    return r


def load_draws():
    """lotto.csv → {회차: {본번호6}}. 보너스는 분석기와 동일하게 제외."""
    import csv
    draws = {}
    with open(CSV_PATH, encoding="utf-8") as f:
        rd = csv.reader(f)
        next(rd, None)
        for row in rd:
            try:
                draws[int(row[0])] = {int(x) for x in row[1:7]}
            except (ValueError, IndexError):
                continue
    return draws


def load_bonus():
    """lotto.csv → {회차: 보너스번호}. 보너스는 당첨번호가 아니며,
    '다음 회차 당첨번호로 나오는지'를 보는 조건으로만 사용한다."""
    import csv
    bonus = {}
    with open(CSV_PATH, encoding="utf-8") as f:
        rd = csv.reader(f)
        next(rd, None)
        for row in rd:
            try:
                bonus[int(row[0])] = int(row[7])
            except (ValueError, IndexError):
                continue
    return bonus


def rank_counter(counter, top=3):
    """Counter → 경쟁순위(동률 묶음) [(순위, [번호...], 횟수)] — 순위 top 이내만."""
    groups = []  # [(횟수, [번호])] 내림차순
    for m, k in sorted(counter.items(), key=lambda x: (-x[1], x[0])):
        if groups and groups[-1][0] == k:
            groups[-1][1].append(m)
        else:
            groups.append((k, [m]))
    ranked, pos = [], 1
    for k, ms in groups:
        if pos > top:
            break
        ranked.append((pos, ms, k))
        pos += len(ms)
    return ranked


def carried(draws, t):
    """t회 이월수 = t-1회와 t회에 연속 당첨된 번호."""
    return draws[t] & draws.get(t - 1, set())


def calc_followers(draws, base_no, carry_only=False, bonus=None):
    """번호 N 조건 → t+1회 당첨번호 집계 (t+1 ≤ base_no).
    기본: N이 t회에 당첨 / carry_only: N이 t회에 이월(t-1·t 연속 당첨) /
    bonus({회차: 보너스}): N이 t회 보너스 번호.
    반환: [(N, 조건 충족 횟수, [(순위, [번호...], 횟수), ...상위 3순위], N이 t+1회 당첨된 횟수)]"""
    from collections import Counter
    cnt = {n: Counter() for n in range(1, 46)}
    base = Counter()
    for t in draws:
        nxt = draws.get(t + 1)
        if nxt is None or t + 1 > base_no:
            continue
        if bonus is not None:
            keys = {bonus[t]} if t in bonus else set()
        else:
            keys = carried(draws, t) if carry_only else draws[t]
        for n in keys:
            base[n] += 1
            cnt[n].update(nxt)
    return [(n, base[n], rank_counter(cnt[n]), cnt[n][n]) for n in range(1, 46)]


def calc_carry_draws(draws, base_no):
    """이월이 있었던 회차 t를 한 단위로 보고 t+1회 당첨번호를 1회씩 집계.
    반환: (이월회차 수, [(순위, 번호, 횟수, 비율%, 기준대비 배수)] 45행)
    기준 = 2~base_no회 전체에서 그 번호가 나온 비율."""
    from collections import Counter
    ev, cnt, allc = 0, Counter(), Counter()
    for t in draws:
        nxt = draws.get(t + 1)
        if nxt is None or t + 1 > base_no:
            continue
        allc.update(nxt)
        if carried(draws, t):
            ev += 1
            cnt.update(nxt)
    n_all = sum(1 for t in draws if t + 1 in draws and t + 1 <= base_no)
    rows, prev, rk = [], None, 0
    for i, m in enumerate(sorted(range(1, 46), key=lambda x: (-cnt[x], x)), 1):
        if cnt[m] != prev:
            rk, prev = i, cnt[m]
        rate = cnt[m] / ev if ev else 0
        lift = rate / (allc[m] / n_all) if allc[m] else 0
        rows.append((rk, m, cnt[m], rate * 100, lift))
    return ev, rows


# ── 타이밍 통계: 패턴이 k회 연속 미출현일 때 다음 회차 출현 확률 ──────────
TWINS = {11, 22, 33, 44}
MIRROR_PAIRS = [(12, 21), (13, 31), (14, 41), (23, 32), (24, 42), (34, 43)]
MIRROR_MAP = {a: b for x, y in MIRROR_PAIRS for a, b in ((x, y), (y, x))}
PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43}
BANDS = [("단번대(1-10)", 1, 10), ("10번대(11-20)", 11, 20), ("20번대(21-30)", 21, 30),
         ("30번대(31-40)", 31, 40), ("40번대(41-45)", 41, 45)]
TIMING_MIN_N = 15  # 해당 미출현 길이의 표본이 이보다 적으면 '≥k'로 넓혀서 추정


def _has_run(nums, k):
    s = sorted(nums)
    run = 1
    for a, b in zip(s, s[1:]):
        run = run + 1 if b == a + 1 else 1
        if run >= k:
            return True
    return False


def _band_cnt(nums, lo, hi):
    return sum(lo <= x <= hi for x in nums)


def timing_events(draws, bonus):
    """타이밍 대상 패턴 [(구분, 이름, f(t)->bool)]. 당첨번호는 번호1~6만, 보너스는 '전회 보너스 당첨' 조건에만."""
    from collections import Counter
    d = draws
    prev = lambda t: d.get(t - 1, set())
    ld = lambda t: Counter(x % 10 for x in d[t])
    ev = [
        ("구조", "쌍수(11·22·33·44)", lambda t: bool(d[t] & TWINS)),
        ("구조", "쌍수 2개 이상", lambda t: len(d[t] & TWINS) >= 2),
        ("구조", "연번(2연속)", lambda t: _has_run(d[t], 2)),
        ("구조", "3연번", lambda t: _has_run(d[t], 3)),
        ("구조", "연번 2쌍 이상", lambda t: sum(1 for x in d[t] if x + 1 in d[t]) >= 2),
        ("구조", "거울수 쌍(12↔21 등 동시)", lambda t: any(a in d[t] and b in d[t] for a, b in MIRROR_PAIRS)),
        ("구조", "거울수 이월(전회 번호의 거울수)", lambda t: any(MIRROR_MAP.get(x) in d[t] for x in prev(t))),
        ("구조", "이월수 1개 이상", lambda t: bool(d[t] & prev(t))),
        ("구조", "이월수 2개 이상", lambda t: len(d[t] & prev(t)) >= 2),
        ("구조", "이웃수(전회 번호 ±1)", lambda t: any(x - 1 in d[t] or x + 1 in d[t] for x in prev(t))),
        ("구조", "전회 보너스 → 당첨", lambda t: bonus.get(t - 1) in d[t]),
        ("구조", "동끝수 3개 이상", lambda t: max(ld(t).values()) >= 3),
        ("구조", "동끝수 2쌍 이상", lambda t: sum(1 for v in ld(t).values() if v >= 2) >= 2),
        ("구조", "홀짝 극단(홀 0~1·5~6개)", lambda t: sum(x % 2 for x in d[t]) in (0, 1, 5, 6)),
        ("구조", "고저 극단(≤22가 0~1·5~6개)", lambda t: sum(x <= 22 for x in d[t]) in (0, 1, 5, 6)),
        ("구조", "소수 3개 이상", lambda t: len(d[t] & PRIMES) >= 3),
        ("구조", "합계 100 미만", lambda t: sum(d[t]) < 100),
        ("구조", "합계 176 이상", lambda t: sum(d[t]) >= 176),
        ("구조", "멸구간 2개 이상", lambda t: sum(_band_cnt(d[t], lo, hi) == 0 for _, lo, hi in BANDS) >= 2),
    ]
    for nm, lo, hi in BANDS:
        ev.append(("번호대", f"{nm} 출현", lambda t, lo=lo, hi=hi: _band_cnt(d[t], lo, hi) >= 1))
    for nm, lo, hi in BANDS:
        ev.append(("번호대", f"{nm} 3개 이상", lambda t, lo=lo, hi=hi: _band_cnt(d[t], lo, hi) >= 3))
    for k in range(10):
        ev.append(("끝수", f"끝수 {k} 출현", lambda t, k=k: any(x % 10 == k for x in d[t])))
    return ev


def _streak_stats(series):
    """series(회차순 bool) → (hz {미출현길이 k: [다음회차 출현, 표본]}, 현재 미출현, 미출현 구간 길이 목록)."""
    hz, s = {}, 0
    for i in range(len(series) - 1):
        s = 0 if series[i] else s + 1
        v = hz.setdefault(s, [0, 0])
        v[1] += 1
        v[0] += series[i + 1]
    cur = 0
    for x in reversed(series):
        if x:
            break
        cur += 1
    runs, r = [], 0
    for x in series:
        if x:
            if r:
                runs.append(r)
            r = 0
        else:
            r += 1
    return hz, cur, runs


def _hazard_at(hz, k):
    """미출현 k회 뒤 다음 회차 출현 확률. 표본이 TIMING_MIN_N 미만이면 '≥k'로 넓히고,
    그래도 부족하면 기준 k를 낮춰 표본을 확보한다. 반환 (확률, 표본, 근거 문자열)."""
    hit, n = hz.get(k, [0, 0])
    if n >= TIMING_MIN_N:
        return hit / n, n, f"{k}회"
    kk = k
    while kk >= 0:
        hit = sum(v[0] for j, v in hz.items() if j >= kk)
        n = sum(v[1] for j, v in hz.items() if j >= kk)
        if n >= TIMING_MIN_N:
            return hit / n, n, f"{kk}회+"
        kk -= 1
    return 0.0, 0, "-"


def _verdict(lift, n, cur, longest, p=1.0):
    if p < 0.03:  # 평소 출현율 3% 미만 희귀 패턴은 타이밍 판정에서 제외(표본 부족)
        return "희귀"
    if longest and cur >= longest:
        return "기록경신"
    if n >= 20 and lift >= 1.2:
        return "◎ 타이밍"
    if lift >= 1.05 or (longest and cur >= 0.8 * longest):
        return "○ 관심"
    if lift < 0.9:
        return "▽ 쉬어감"
    return "△ 보통"


def calc_timing(draws, bonus, base_no):
    """패턴별 타이밍 통계 (2회~base_no회). 반환 rows:
    (구분, 이름, 출현율, 평균미출현, 최장미출현, 현재미출현, 확률, 표본, 근거, 기준대비, 판정, hz)"""
    ts = [t for t in sorted(draws) if 2 <= t <= base_no]
    rows = []
    for g, name, f in timing_events(draws, bonus):
        ser = [f(t) for t in ts]
        p = sum(ser) / len(ser)
        hz, cur, runs = _streak_stats(ser)
        prob, n, basis = _hazard_at(hz, cur)
        lift = prob / p if p else 0
        longest = max(runs) if runs else 0
        avg = sum(runs) / len(runs) if runs else 0
        rows.append((g, name, p, avg, longest, cur, prob, n, basis, lift,
                     _verdict(lift, n, cur, longest, p), hz))
    return rows


def backtest_timing(draws, bonus, base_no, weeks=100):
    """최근 weeks회 동안 매 회차 그 시점까지의 데이터만으로 '◎ 타이밍' 패턴을 고르고,
    다음 회차 실제 출현률을 그 패턴들의 평소 출현율(기대치)과 비교."""
    ts = [t for t in sorted(draws) if 2 <= t <= base_no]
    evs = timing_events(draws, bonus)
    full = [[f(t) for t in ts] for _, _, f in evs]
    picks = hits = 0
    expect = 0.0
    for end in range(len(ts) - weeks, len(ts)):
        for ser in full:
            pre = ser[:end]  # ts[end-1]회까지
            p = sum(pre) / len(pre)
            hz, cur, runs = _streak_stats(pre)
            prob, n, _ = _hazard_at(hz, cur)
            lift = prob / p if p else 0
            longest = max(runs) if runs else 0
            if _verdict(lift, n, cur, longest, p) == "◎ 타이밍":
                picks += 1
                hits += ser[end]
                expect += p
    return picks, hits, expect


# ── 2) OOXML 조각 ─────────────────────────────────────────────────────────
def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def cell(text, fill=None, font="333333", bold=False, sz=1100, border=True, algn="ctr", runs_xml=None):
    if runs_xml is not None:        # 멀티런(부분 색상) 셀
        runs = runs_xml
    elif text != "":
        runs = (f'<a:r><a:rPr lang="ko-KR" sz="{sz}" b="{1 if bold else 0}">'
                f'<a:solidFill><a:srgbClr val="{font}"/></a:solidFill></a:rPr>'
                f'<a:t>{esc(text)}</a:t></a:r>')
    else:
        runs = ""
    bd = ('<a:lnL w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnL>'
          '<a:lnR w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnR>'
          '<a:lnT w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnT>'
          '<a:lnB w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnB>') if border else ""
    fl = f'<a:solidFill><a:srgbClr val="{fill}"/></a:solidFill>' if fill else ""
    return (f'<a:tc><a:txBody>'
            f'<a:bodyPr lIns="9525" rIns="9525" tIns="6350" bIns="6350" anchor="ctr"/><a:lstStyle/>'
            f'<a:p><a:pPr algn="{algn}"/>{runs}</a:p></a:txBody>'
            f'<a:tcPr anchor="ctr" marL="0" marR="0" marT="0" marB="0">{bd}{fl}</a:tcPr></a:tc>')


def graphic_frame(fid, name, x, y, cx, cy, tbl_inner):
    return (f'<p:graphicFrame><p:nvGraphicFramePr>'
            f'<p:cNvPr id="{fid}" name="{name}"/><p:cNvGraphicFramePr/><p:nvPr/>'
            f'</p:nvGraphicFramePr><p:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{cy}"/></p:xfrm>'
            f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">'
            f'{tbl_inner}</a:graphicData></a:graphic></p:graphicFrame>')


def gtable(fid, name, x, y, col_w, rows, row_h=210000):
    """일반 데이터 표. rows: 행 리스트, 각 행 = 셀 dict 리스트({t,fill,font,bold,sz,algn})."""
    grid = "".join(f'<a:gridCol w="{w}"/>' for w in col_w)
    trs = ""
    for row in rows:
        tcs = "".join(cell(c.get("t", ""), fill=c.get("fill"), font=c.get("font", "333333"),
                           bold=c.get("bold", False), sz=c.get("sz", 900),
                           algn=c.get("algn", "ctr"), runs_xml=c.get("runs")) for c in row)
        trs += f'<a:tr h="{row_h}">{tcs}</a:tr>'
    tbl = f'<a:tbl><a:tblPr firstRow="1" bandRow="1"/><a:tblGrid>{grid}</a:tblGrid>{trs}</a:tbl>'
    return graphic_frame(fid, name, x, y, sum(col_w), row_h * len(rows), tbl)


def grid_table(fid, name, x, y, chosen):
    """7열×7행 로또 용지 그리드."""
    cw = rh = 300000
    cols = "".join(f'<a:gridCol w="{cw}"/>' for _ in range(7))
    trs = ""
    for r in range(7):
        tcs = ""
        for c in range(7):
            num = r * 7 + c + 1
            if num > 45:
                tcs += cell("", fill="EFEFEF")
            elif num in chosen:
                tcs += cell(num, fill="C00000", font="FFFFFF", bold=True)
            else:
                tcs += cell(num, fill="FFFFFF", font="333333")
        trs += f'<a:tr h="{rh}">{tcs}</a:tr>'
    tbl = f'<a:tbl><a:tblPr firstRow="0" bandRow="0"/><a:tblGrid>{cols}</a:tblGrid>{trs}</a:tbl>'
    return graphic_frame(fid, name, x, y, cw * 7, rh * 7, tbl)


def textbox(fid, name, x, y, cx, cy, runs, algn="l", anchor="t"):
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{fid}" name="{name}"/>'
            f'<p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square" anchor="{anchor}"/><a:lstStyle/>'
            f'<a:p><a:pPr algn="{algn}"/>{runs}</a:p></p:txBody></p:sp>')


def run(text, sz, color="000000", bold=False):
    return (f'<a:r><a:rPr lang="ko-KR" sz="{sz}" b="{1 if bold else 0}">'
            f'<a:solidFill><a:srgbClr val="{color}"/></a:solidFill></a:rPr>'
            f'<a:t>{esc(text)}</a:t></a:r>')


def gap_runs(gaps_str, nextgap, sz=700):
    """간격(차이수) 문자열을 토큰별 run으로 쪼개, 다음간격과 같은 차이수(=반복된 간격)만 빨강·굵게."""
    if not gaps_str or gaps_str == "-":
        return run(gaps_str or "-", sz, "808080")
    out = []
    for i, tok in enumerate(gaps_str.split(",")):
        m = re.match(r"\s*(\d+)\(", tok)
        rep = bool(m) and m.group(1) == str(nextgap)
        if i:
            out.append(run(",", sz, "808080"))
        out.append(run(tok, sz, "C00000" if rep else "808080", bold=rep))
    return "".join(out)


def wrap_slide(shapes):
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
        '<p:cSld><p:spTree>'
        '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>'
        '<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
        f'{"".join(shapes)}'
        '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>'
    )


# ── 3) 슬라이드 빌더 ───────────────────────────────────────────────────────
HDR = "1F3864"  # 표 헤더/제목 색


def hdr_cells(labels, widths_unused=None, sz=900):
    return [{"t": t, "fill": HDR, "font": "FFFFFF", "bold": True, "sz": sz} for t in labels]


def slide_summary(r):
    sh = []
    fid = 2
    title = f"{r['next_no']}회차 로또 패턴 분석 리포트" if r["next_no"] else "로또 패턴 분석 리포트"
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run(title, 2400, HDR, True))); fid += 1
    sub = f"기준 {r['base_no']}회 · 학습창 200주 · 분석기 가중치 = 효과크기 z (소표본 보정)"
    sh.append(textbox(fid, "sub", 400000, 820000, W - 800000, 400000,
                      run(sub, 1200, "808080"))); fid += 1

    # 좌: 패턴별 효과크기(z) 순위
    sh.append(textbox(fid, "zt", 400000, 1300000, 5800000, 320000,
                      run("패턴별 효과크기(z·가중치) 순위", 1400, "C00000", True))); fid += 1
    zcols = [620000, 1500000, 760000, 820000, 1100000, 700000]
    zrows = [hdr_cells(["순위", "패턴", "z", "정확도", "적중/예측", "반영"])]
    for (rk, nm, z, acc, h, p, refl) in r["z"]:
        used = refl.startswith("○")
        zrows.append([
            {"t": f"{rk}", "sz": 850},
            {"t": nm, "sz": 850, "algn": "l", "font": "333333" if used else "AAAAAA"},
            {"t": z, "sz": 850, "bold": used, "font": "C00000" if used else "AAAAAA"},
            {"t": f"{acc}%", "sz": 850},
            {"t": f"{h}/{p}", "sz": 850},
            {"t": "○" if used else "×", "sz": 850, "font": "1F3864" if used else "C0C0C0"},
        ])
    sh.append(gtable(fid, "ztbl", 400000, 1660000, zcols, zrows, row_h=196000)); fid += 1

    # 우: 순위그룹 분포
    rx = 6700000
    sh.append(textbox(fid, "dt", rx, 1300000, 5000000, 320000,
                      run(f"추천 순위그룹별 당첨 분포 (최근 {r['dist_weeks']}주)", 1400, "C00000", True))); fid += 1
    dcols = [1500000, 1100000, 1100000]
    drows = [hdr_cells(["순위 그룹", "당첨 횟수", "비율"])]
    maxpct = max((float(p) for _, _, p in r["dist"]), default=1)
    for (g, c, p) in r["dist"]:
        top = float(p) >= maxpct - 1e-9
        drows.append([
            {"t": g, "sz": 950, "bold": top, "font": "C00000" if top else "333333"},
            {"t": f"{c}", "sz": 950},
            {"t": f"{p}%", "sz": 950, "bold": top},
        ])
    sh.append(gtable(fid, "dtbl", rx, 1660000, dcols, drows, row_h=300000)); fid += 1

    # 우하단: 다음회차 요약
    y2 = 1660000 + 300000 * (len(r["dist"]) + 1) + 250000
    lines = []
    if r["must"]:
        lines.append(run(f"▶ 필출 확률 UP: {r['must'][0]} 그룹 ({r['must'][1]}%)", 1200, "1F3864", True))
    if r["excl"]:
        lines.append(f'<a:br/>' + run(f"▶ 제외 확률 UP: {r['excl'][0]} 그룹 ({r['excl'][1]}%)", 1200, "808080"))
    note = ('<a:br/>' + run("※ 로또는 독립 난수 — 본 리포트는 통계적 참고용이며 적중을 보장하지 않습니다.",
                            1000, "A6A6A6"))
    body = (f'<p:sp><p:nvSpPr><p:cNvPr id="{fid}" name="summary"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="{rx}" y="{y2}"/><a:ext cx="5000000" cy="1600000"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square"/><a:lstStyle/>'
            f'<a:p>{"".join(lines)}{note}</a:p></p:txBody></p:sp>')
    sh.append(body)
    return wrap_slide(sh)


def slides_ranking(r):
    """1~45 종합 순위. 기여패턴을 축약(…) 없이 전부 표시하기 위해 패턴 칸을 가로 전폭
    단일 블록으로 키우고 45순위를 15개씩 여러 페이지로 분할한다(좁은 3블록 축약 폐지)."""
    used = set()
    for _, nums in r["games"]:
        used.update(nums)
    rank = sorted(r["rank"], key=lambda t: t[0])[:45]
    per = 15
    blocks = [rank[i:i + per] for i in range(0, len(rank), per)]
    npage = len(blocks)
    cols = [700000, 720000, 1100000, 8000000]  # 순위, 번호, 점수, 기여 패턴(전체)
    cw = sum(cols)
    bx = (W - cw) // 2
    out_slides = []
    for bi, blk in enumerate(blocks):
        sh = []
        fid = 2
        page = f"  ({bi + 1}/{npage})" if npage > 1 else ""
        sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                          run(f"1~45 종합 순위 (분석기 가중 합산 점수){page}", 2200, HDR, True))); fid += 1
        if bi == 0:
            sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 360000,
                              run("노랑 음영 = 자동 추천 5게임에 포함된 번호", 1200, "808080"))); fid += 1
            ty = 1300000
        else:
            ty = 820000
        rows = [hdr_cells(["순위", "번호", "점수", "기여 패턴"])]
        for (rk, num, sc, tags) in blk:
            hl = num in used
            rows.append([
                {"t": f"{rk}", "sz": 850, "bold": rk <= 6},
                {"t": f"{num}", "sz": 900, "bold": True,
                 "fill": "FFF2CC" if hl else None, "font": "BF8F00" if hl else "1F3864"},
                {"t": sc, "sz": 850},
                {"t": tags, "sz": 750, "algn": "l", "font": "808080"},
            ])
        sh.append(gtable(fid, f"rk{bi}", bx, ty, cols, rows, row_h=320000)); fid += 1
        out_slides.append(wrap_slide(sh))
    return out_slides


def slide_games(r):
    sh = []
    fid = 2
    title = f"{r['next_no']}회차 자동 추천 5게임 · 로또 용지 표기" if r["next_no"] else "자동 추천 5게임"
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run(title, 2200, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 820000, W - 800000, 360000,
                      run("로또 용지(7열×7행) · 빨강 = 선택 번호 · 세로열 최대 2개", 1200, "808080"))); fid += 1

    margin = 400000
    gw = gh = 300000 * 7
    pitch = (W - 2 * margin - gw) // 4
    gy = 1750000
    for i, (label, nums) in enumerate(r["games"]):
        x = margin + i * pitch
        sh.append(textbox(fid, f"lbl{label}", x, gy - 400000, gw, 360000,
                          run(f"{label} 게임", 1600, "C00000", True), algn="ctr")); fid += 1
        sh.append(grid_table(fid, f"grid{label}", x, gy, set(nums))); fid += 1
        total, odd = sum(nums), sum(1 for n in nums if n % 2)
        sh.append(textbox(fid, f"num{label}", x, gy + gh + 100000, gw, 400000,
                          run(" ".join(f"{n:02d}" for n in nums), 1100, "1F3864", True), algn="ctr")); fid += 1
        sh.append(textbox(fid, f"meta{label}", x, gy + gh + 520000, gw, 360000,
                          run(f"합 {total} · 홀{odd}:짝{6 - odd}", 1000, "808080"), algn="ctr")); fid += 1
    return wrap_slide(sh)


def slides_rankhits(win, rows, rank2num, next_no, rank_gaps, cands, periodic):
    """순위별 재출현 통계 + 간격(차이수·회차) 분석.
    간격 문자열에 회차정보(차이(전회차→후회차))가 들어가 폭이 길어졌으므로, 간격 칸을
    가로 전폭(단일 블록)으로 키우고 45순위를 15개씩 여러 페이지로 분할한다(좁은 칸 줄바꿈 폭주 방지)."""
    out_slides = []
    n = win[2] if win else 0
    rng = f"{win[0]}~{win[1]}회" if win else ""
    per = 15
    blocks = [rows[i:i + per] for i in range(0, len(rows), per)]
    npage = len(blocks)
    # 순위,번호,적중,간격(차이수·회차), 다음간격, 가능성 — 간격 칸을 전폭으로
    cols = [600000, 620000, 600000, 8000000, 700000, 700000]
    cw = sum(cols)
    bx = (W - cw) // 2
    for bi, blk in enumerate(blocks):
        sh = []
        fid = 2
        page = f"  ({bi + 1}/{npage})" if npage > 1 else ""
        sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                          run(f"순위별 당첨 재출현 + 간격(차이수·회차) 분석 (최근 {n}회 · {rng}){page}",
                              1700, HDR, True))); fid += 1
        if bi == 0:
            note = (f"적중 = 그 순위 추천번호가 당첨이었던 횟수  ·  간격(차이수) 형식: 차이(전회차→후회차)  ·  "
                    f"다음간격 = {next_no}회 − 마지막 재출현회차  ·  ◎ = 다음간격이 과거 5회 이상 재출현(반복) / − = 아님")
            sh.append(textbox(fid, "sub", 400000, 740000, W - 800000, 360000,
                              run(note, 1000, "808080"))); fid += 1
            if cands:
                cands_sorted = sorted(cands, key=lambda c: -c[3])
                # 번호만 한 줄로 압축(순위·간격·반복 상세는 아래 표에서 확인). 배너가 표와 겹치지 않도록.
                nums_only = ", ".join(str(num) for (rk, num, g, pc) in cands_sorted)
                banner = f"◎ {next_no}회 재출현 유력 번호 ({len(cands_sorted)}개, 다음간격 과거 5회↑ 반복): {nums_only}"
                bcolor = "C00000"
            else:
                banner = f"◎ {next_no}회: 다음간격이 과거 5회 이상 반복된 순위 없음 (○ 주기보유 {periodic}개 참고)"
                bcolor = "808080"
            sh.append(textbox(fid, "banner", 400000, 1170000, W - 800000, 500000,
                              run(banner, 1000, bcolor, True))); fid += 1
            ty = 1740000
        else:
            ty = 820000
        trows = [hdr_cells(["순위", "번호", "적중", "간격(차이수) — 차이(전회차→후회차)", "다음간격", "가능성"])]
        for (rk, cnt, ds) in blk:
            hot = cnt >= 5
            zero = cnt == 0
            num = rank2num.get(rk, "")
            gaps_str, nextgap, ch = rank_gaps.get(rk, ("-", "-", "-"))
            # 다음간격이 과거 5회 이상 재출현(반복)한 순위만 ◎(빨강), 나머지(주기보유 ○ 포함)는 −.
            mark = "◎" if ch == "◎" else "−"
            poss_font = "C00000" if mark == "◎" else "C0C0C0"
            trows.append([
                {"t": f"{rk}위", "sz": 850, "bold": rk <= 3},
                {"t": f"{num}", "sz": 900, "bold": True,
                 "fill": "FFF2CC" if rk <= 3 else None,
                 "font": "BF8F00" if rk <= 3 else "1F3864"},
                {"t": f"{cnt}", "sz": 850, "bold": True,
                 "font": "C00000" if hot else ("C0C0C0" if zero else "1F3864")},
                {"runs": gap_runs(gaps_str, nextgap, sz=700), "sz": 700, "algn": "l"},
                {"t": nextgap, "sz": 850, "bold": True, "font": "1F3864"},
                {"t": mark, "sz": 950, "bold": True,
                 "fill": "FFF2CC" if mark == "◎" else None, "font": poss_font},
            ])
        sh.append(gtable(fid, f"rh{bi}", bx, ty, cols, trows, row_h=300000)); fid += 1
        out_slides.append(wrap_slide(sh))
    return out_slides


def slide_patpred(blocks):
    """과거 10회(1218~1227) 패턴별 성적 상위 5개 → 다음회차 예측. 두 지표(적중회차·lift)를 좌우로."""
    if not blocks:
        return None
    sh, fid = [], 2
    next_no = blocks[0]["next"]
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run(f"{next_no}회 예측 — 과거 10회 최고 적중 패턴 기준", 2100, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 360000,
                      run(f"{next_no-10}~{next_no-1} 각 회차를 직전 데이터로 예측한 패턴 성적 상위 5개 → 그 패턴들의 "
                          f"{next_no}회 후보 (괄호=여러 패턴이 공통 출력한 번호)", 1100, "808080"))); fid += 1

    metric_label = {"적중회차비율": "① 적중회차 비율 기준 (10회 중 ≥1개 적중한 회차 수)",
                    "lift": "② lift(효율) 기준 (후보 1개당 적중률, 1.0 = 무작위)"}
    xs = [400000, 6300000]
    tblw = [500000, 1700000, 900000, 700000, 1750000]
    for bi, blk in enumerate(blocks[:2]):
        x = xs[bi]
        sh.append(textbox(fid, f"h{bi}", x, 1300000, sum(tblw), 320000,
                          run(metric_label.get(blk["metric"], blk["metric"]), 1200, "C00000", True))); fid += 1
        rows = [hdr_cells(["순위", "패턴", "적중회차", "lift", f"{next_no}후보"], sz=800)]
        for (rk, pat, hr, avg, lift, cands) in blk["rows"]:
            rows.append([
                {"t": str(rk), "sz": 800, "bold": rk <= 2},
                {"t": pat, "sz": 800, "algn": "l"},
                {"t": hr, "sz": 800},
                {"t": lift, "sz": 800, "bold": True, "font": "1F3864"},
                {"t": " ".join(str(n) for n in cands), "sz": 800, "algn": "l", "bold": True, "font": "C00000"},
            ])
        sh.append(gtable(fid, f"t{bi}", x, 1680000, tblw, rows, row_h=300000)); fid += 1
        py = 1680000 + 300000 * (len(blk["rows"]) + 1) + 220000
        pool_txt = ", ".join(f"{n}({v})" if v > 1 else str(n) for n, v in blk["pool"])
        sh.append(textbox(fid, f"p{bi}", x, py, sum(tblw), 1000000,
                          run("▶ 상위 패턴 후보 풀: ", 1050, "1F3864", True)
                          + run(pool_txt, 1050, "333333"))); fid += 1

    foot = (f"※ {next_no-10}~{next_no-1} 사후(in-sample) 과적합 결과 — 62개 패턴 중 이 구간에서 우연히 잘 맞은 컷일 뿐, "
            f"{next_no}회 적중을 보장하지 않습니다(로또는 독립 난수).")
    sh.append(textbox(fid, "foot", 400000, H - 620000, W - 800000, 500000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


def slide_gapcut(info, rows, live):
    """◎(간격반복) 임계 N을 4~7로 스윕한 과거 10회 적중률 백테스트. 분모까지 표기."""
    if not rows:
        return None
    sh, fid = [], 2
    next_no = info.get("next", "")
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run(f"{next_no}회 ◎ 반복임계 스윕 — 과거 10회 적중률 백테스트", 2000, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 430000,
                      run(f"◎ 기준 = 다음간격이 과거 N회 이상 반복. N을 4~7로 바꿔 {info['lo']}~{info['hi']} 각 회차를 "
                          f"직전 {info['win']}회 창으로 예측 → ◎ 번호가 실제 당첨에 든 비율(번호 기준). "
                          f"무작위 기대 {info['rand']}%", 1100, "808080"))); fid += 1

    cols = [1400000, 1700000, 1100000, 1700000, 3100000]
    bx = (W - sum(cols)) // 2
    trows = [hdr_cells(["반복 N회", "◎ 총수(분모)", "적중", "적중률", f"{next_no}회 ◎ 예측번호"])]
    valid = [r for r in rows if r["total"] >= 5]
    best_rate = max((float(r["rate"]) for r in valid), default=None)
    for r in rows:
        small = r["total"] < 5
        is_best = (not small) and best_rate is not None and abs(float(r["rate"]) - best_rate) < 1e-9
        rate_txt = "표본부족" if small else f"{r['rate']}%"
        ln = live.get(r["cut"], [])
        trows.append([
            {"t": f"{r['cut']}회", "sz": 1050, "bold": True},
            {"t": str(r["total"]), "sz": 1050, "font": "C0C0C0" if small else "333333"},
            {"t": str(r["hit"]), "sz": 1050},
            {"t": rate_txt, "sz": 1050, "bold": is_best,
             "font": "C00000" if is_best else ("C0C0C0" if small else "333333"),
             "fill": "FFF2CC" if is_best else None},
            {"t": ", ".join(str(n) for n in ln) if ln else "-", "sz": 950, "algn": "l", "font": "1F3864"},
        ])
    sh.append(gtable(fid, "gct", bx, 1380000, cols, trows, row_h=380000)); fid += 1

    cy = 1380000 + 380000 * 5 + 250000
    if valid:
        b = [r for r in valid if abs(float(r["rate"]) - best_rate) < 1e-9][0]
        msg = (f"▶ 분모 5건 이상 중 최고 적중률: N={b['cut']}회 ({b['hit']}/{b['total']} = {b['rate']}%)  ·  "
               f"{next_no}회 해당 ◎ 예측번호: {', '.join(str(n) for n in live.get(b['cut'], [])) or '-'}")
    else:
        msg = "▶ 모든 컷오프의 ◎ 표본이 5건 미만 — 적중률 비교 불가(표본부족)"
    sh.append(textbox(fid, "msg", bx, cy, sum(cols), 500000, run(msg, 1150, "1F3864", True))); fid += 1

    foot = ("※ n=10 사후(in-sample) 스윕 — 4개 중 '최고'를 고르는 건 다중비교 과적합이고, 분모가 작은 컷오프의 "
            f"높은 %는 우연입니다. {next_no}회 적중을 보장하지 않습니다(로또는 독립 난수).")
    sh.append(textbox(fid, "foot", 400000, H - 620000, W - 800000, 500000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


def slide_pathit(rows, patpred, next_no):
    """상위 패턴(distinct)의 1228 예측번호 + 1218~1227 각 회차에 실제로 맞춘 회차·번호."""
    if not rows:
        return None
    pred_of = {}
    for b in patpred:
        for (rk, pat, hr, avg, lift, cands) in b["rows"]:
            pred_of.setdefault(pat, cands)
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run("과거 10회 상위 패턴 — 회차별 적중 상세", 2100, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 360000,
                      run(f"상위 패턴의 {next_no}회 예측번호 + {next_no-10}~{next_no-1} 각 회차에 실제로 맞춘 번호 (회차:번호)",
                          1100, "808080"))); fid += 1
    cols = [2400000, 1700000, 6400000]
    bx = (W - sum(cols)) // 2
    trows = [hdr_cells(["패턴", f"{next_no}예측", "적중 회차:번호 (회당)"])]
    for pat, hits in rows:
        if hits:
            txt = f"({len(hits)}회)  " + "   ".join(f"{rd}:{','.join(map(str, nums))}" for rd, nums in hits)
        else:
            txt = "-"
        cands = pred_of.get(pat, [])
        trows.append([
            {"t": pat, "sz": 950, "algn": "l", "bold": True},
            {"t": ", ".join(map(str, cands)) if cands else "-", "sz": 950, "bold": True, "font": "1F3864"},
            {"t": txt, "sz": 880, "algn": "l", "font": "C00000" if hits else "C0C0C0"},
        ])
    sh.append(gtable(fid, "pht", bx, 1350000, cols, trows, row_h=380000)); fid += 1
    foot = f"※ {next_no-10}~{next_no-1} 사후 실측 — 과거 적중일 뿐 미래 적중 보장 아님(로또는 독립 난수)."
    sh.append(textbox(fid, "foot", 400000, H - 560000, W - 800000, 460000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


def slide_gaphit(rows, next_no):
    """◎ 반복임계 — 1218~1227 회차별 ◎번호(반복수)와 실제 적중번호. 적중은 빨강."""
    if not rows:
        return None
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run("◎ 반복임계 — 회차별 ◎번호·반복수·적중", 2000, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                      run(f"{next_no-10}~{next_no-1} 각 회차의 ◎번호(반복수≥4)와 실제 당첨된 번호(빨강). "
                          "반복수가 N이면 컷오프 N까지의 ◎에 포함됩니다.", 1100, "808080"))); fid += 1
    cols = [1300000, 7600000, 1700000]
    bx = (W - sum(cols)) // 2
    trows = [hdr_cells(["회차", "◎번호 : 반복수", "적중번호"])]
    # 다음회차(next_no) 예측행을 맨 위에 강조 배치.
    pred, hist = None, []
    for rd, marks, hits in rows:
        if rd == next_no:
            pred = (rd, marks)
        else:
            hist.append((rd, marks, hits))
    if pred:
        rd, marks = pred
        rr = []
        for i, (n, c) in enumerate(marks):
            if i:
                rr.append(run(", ", 800, "1F3864"))
            rr.append(run(f"{n}:{c}", 800, "1F3864", bold=True))
        runs_xml = "".join(rr) if marks else run("-", 800, "808080")
        trows.append([
            {"t": f"{rd} 예측", "sz": 900, "bold": True, "fill": "FFF2CC", "font": "C00000"},
            {"runs": runs_xml, "sz": 800, "algn": "l", "fill": "FFF2CC"},
            {"t": "(예측)", "sz": 900, "font": "1F3864", "fill": "FFF2CC"},
        ])
    for rd, marks, hits in hist:
        hset = set(hits)
        rr = []
        for i, (n, c) in enumerate(marks):
            if i:
                rr.append(run(", ", 800, "808080"))
            red = n in hset
            rr.append(run(f"{n}:{c}", 800, "C00000" if red else "333333", bold=red))
        runs_xml = "".join(rr) if marks else run("-", 800, "808080")
        trows.append([
            {"t": f"{rd}", "sz": 900, "bold": True},
            {"runs": runs_xml, "sz": 800, "algn": "l"},
            {"t": ", ".join(map(str, hits)) if hits else "-", "sz": 950, "bold": True,
             "font": "C00000" if hits else "C0C0C0"},
        ])
    sh.append(gtable(fid, "ght", bx, 1320000, cols, trows, row_h=300000)); fid += 1
    foot = ("※ 반복수 N = 그 다음간격이 과거 N회 반복. N=4·5·6·7 컷오프 포함 여부가 갈립니다. "
            f"{next_no-10}~{next_no-1} 사후 실측 — 미래 적중 보장 아님.")
    sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


def _follow_cells(ranked, b, sz=800, max_nums=8):
    """경쟁순위 상위 3 → 1·2·3위 칸. 동률이 다음 순위를 덮으면 그 칸은 '–'.
    동률 번호가 max_nums개를 넘으면 앞쪽만 쓰고 '외 n개'로 줄인다(칸 넘침 방지)."""
    cells = [{"t": "–", "sz": sz, "font": "C0C0C0"} for _ in range(3)]
    for pos, ms, k in ranked:
        shown = ", ".join(map(str, ms[:max_nums]))
        if len(ms) > max_nums:
            shown += f" 외 {len(ms) - max_nums}개"
        txt = f"{shown} ({k}회·{k / b * 100:.1f}%)" if b else "-"
        if len(ms) > 1:
            txt += " 공동"
        cells[pos - 1] = {"t": txt, "sz": sz, "bold": pos == 1,
                          "font": "C00000" if pos == 1 else "333333"}
    return cells


def slides_followers(stats, base_no, highlight, mode="all", self_rate=None):
    """번호 N 조건 → 다음 회차 상위 3 번호 (1~45, 2페이지).
    mode: "all"(N 당첨, 노랑 = 최근 회차 번호) / "carry"(N 이월, 노랑 = 최근 이월수) /
          "bonus"(N이 보너스, 노랑 = 최근 회차 보너스 — '다음회차 당첨' 칸 추가).
    self_rate: bonus 모드에서 (보너스가 다음 회차 당첨된 횟수, 전체 회차 수)."""
    pages = [stats[:23], stats[23:]]
    if mode == "bonus":
        cols = [700000, 800000, 1150000, 2850000, 2850000, 2850000]
    else:
        cols = [800000, 1000000, 3150000, 3150000, 3150000]
    bx = (W - sum(cols)) // 2
    if mode == "bonus":
        hit, tot = self_rate or (0, 0)
        title = "보너스 통계 — 번호 N이 보너스로 나온 다음 회차 상위 3"
        sub = (f"1~{base_no}회 · N이 t회 보너스 → t+1회 당첨번호(번호1~6) 집계 · "
               f"보너스가 다음 회차 당첨번호로 나온 비율 {hit}/{tot} = {hit / tot * 100 if tot else 0:.1f}% "
               f"(기본 13.3%) · 노랑 = {base_no}회 보너스")
        cnt_label = "보너스"
        lo = min(st[1] for st in stats)
        hi = max(st[1] for st in stats)
        foot = ("※ 보너스 번호는 당첨번호가 아니며, '다음 회차 당첨번호로 나오는지'를 보는 조건으로만 사용합니다. "
                f"번호당 표본 {lo}~{hi}건 — 참고용 통계.")
    elif mode == "carry":
        lo = min(st[1] for st in stats)
        hi = max(st[1] for st in stats)
        title = "이월수 통계 — 번호 N이 이월(연속 2회 당첨)된 다음 회차 상위 3"
        sub = (f"1~{base_no}회 · N이 t-1·t회 연속 당첨 → t+1회 본번호 6개 집계(보너스 제외) · "
               f"(횟수·N이월 대비%) · 노랑 = {base_no}회 이월수")
        cnt_label = "이월"
        foot = (f"※ 이월 표본은 번호당 {lo}~{hi}건으로 작아 공동 순위가 많고 비율 변동이 큽니다. "
                "기본 확률 6/45 ≈ 13.3% — 참고용 통계.")
    else:
        title = "다음회차 동반 출현 통계 — 번호 N이 나온 다음 회차 상위 3"
        sub = (f"1~{base_no}회 · N이 당첨된 회차의 바로 다음 회차 본번호 6개 집계(보너스 제외) · "
               f"(횟수·N출현 대비%) · 노랑 = {base_no}회 당첨번호")
        cnt_label = "출현"
        foot = ("※ 특정 번호가 다음 회차에 나올 기본 확률은 6/45 ≈ 13.3%. 45개 중 최댓값을 고르므로 "
                "무작위여도 1위는 18~20% 수준이 나옵니다 — 참고용 통계.")
    out_slides = []
    for pi, blk in enumerate(pages):
        sh, fid = [], 2
        sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                          run(f"{title}  ({pi + 1}/2)", 2000, HDR, True))); fid += 1
        sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                          run(sub, 1100, "808080"))); fid += 1
        hdr = ["N", cnt_label] + (["다음회차 당첨"] if mode == "bonus" else []) + ["1위", "2위", "3위"]
        rows = [hdr_cells(hdr)]
        for n, b, ranked, self_hit in blk:
            hl = n in highlight
            row = [{"t": f"{n}", "sz": 900, "bold": True,
                    "fill": "FFF2CC" if hl else None, "font": "BF8F00" if hl else HDR},
                   {"t": f"{b}", "sz": 800, "font": "808080"}]
            if mode == "bonus":
                row.append({"t": f"{self_hit}회 ({self_hit / b * 100:.0f}%)" if b else "-", "sz": 800,
                            "font": "C00000" if b and self_hit / b >= 0.2 else "333333"})
            rows.append(row + _follow_cells(ranked, b, max_nums=6 if mode == "bonus" else 8))
        sh.append(gtable(fid, f"fl{pi}", bx, 1250000, cols, rows, row_h=200000)); fid += 1
        sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                          run(foot, 1000, "A6A6A6"))); fid += 1
        out_slides.append(wrap_slide(sh))
    return out_slides


def slide_follow_apply(stats, base_no, keys, next_no, mode="all"):
    """최근 회차 번호(이월수·보너스) 각각의 '다음회차 상위 3'을 모아 다음회차 후보로 정리."""
    by_n = {st[0]: (st[1], st[2]) for st in stats}
    what = {"carry": "이월수", "bonus": "보너스"}.get(mode, "당첨번호")
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run(f"{next_no}회차 적용 — {base_no}회 {what} 기준 다음회차 후보", 2000, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                      run(f"{base_no}회 {what}: " + ", ".join(map(str, sorted(keys))) +
                          " · 오른쪽 표 = 여러 번호의 상위 3에 겹쳐 등장한 후보", 1100, "808080"))); fid += 1

    lcols = [1000000, 1880000, 1880000, 1880000]
    rcols = [900000, 900000, 2550000]
    gap = 300000
    bx = (W - sum(lcols) - gap - sum(rcols)) // 2
    lrows = [hdr_cells([f"{base_no}회 {what if mode != 'all' else '번호'}", "1위", "2위", "3위"])]
    agg = {}  # 후보 → [(N, 횟수)]
    for n in sorted(keys):
        b, ranked = by_n[n]
        lrows.append([{"t": f"{n}", "sz": 1000, "bold": True, "fill": "FFF2CC", "font": "BF8F00"}]
                     + _follow_cells(ranked, b, sz=800, max_nums=5))
        for pos, ms, k in ranked:
            for m in ms:
                agg.setdefault(m, []).append((n, k))
    sh.append(gtable(fid, "fa_l", bx, 1350000, lcols, lrows, row_h=380000)); fid += 1

    cands = sorted(agg.items(), key=lambda x: (-len(x[1]), -sum(k for _, k in x[1]), x[0]))[:10]
    rrows = [hdr_cells(["후보", "겹침", "근거 (N→횟수)"])]
    for m, src in cands:
        multi = len(src) >= 2
        rrows.append([
            {"t": f"{m}", "sz": 1000, "bold": True, "font": "C00000" if multi else HDR},
            {"t": f"{len(src)}", "sz": 900, "bold": multi},
            {"t": ", ".join(f"{n}→{k}" for n, k in src), "sz": 800, "algn": "l", "font": "595959"},
        ])
    sh.append(gtable(fid, "fa_r", bx + sum(lcols) + gap, 1350000, rcols, rrows, row_h=300000)); fid += 1
    foot = (f"※ 겹침 = {base_no}회 {what} 중 몇 개의 '다음회차 상위 3'에 들어갔는지(빨강 = 2개 이상). "
            "과거 출현 빈도이며 적중을 보장하지 않습니다.")
    sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


def slide_carry_draws(ev, rows, base_no, next_no, latest_carry):
    """이월 회차 단위 — 이월이 있었던 회차의 다음 회차에 나온 번호 1~45 순위 (15행×3블록)."""
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run("이월 회차 단위 통계 — 이월이 있었던 회차의 다음 회차 출현 순위", 2000, HDR, True))); fid += 1
    if latest_carry:
        note = (f"{base_no}회 이월수 {', '.join(map(str, sorted(latest_carry)))} → "
                f"{next_no}회는 이 통계의 적용 대상")
    else:
        note = f"{base_no}회는 이월수 없음 → {next_no}회에는 직접 적용되지 않음"
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                      run(f"1~{base_no}회 중 이월수가 1개 이상인 회차 {ev}회 · 다음 회차 본번호를 회차당 1번씩 집계 · "
                          f"기준대비 = 전체 회차 출현율 대비 배수 · {note}", 1050, "808080"))); fid += 1
    cols = [600000, 650000, 750000, 850000, 900000]
    gap = 250000
    bw = sum(cols)
    bx = (W - bw * 3 - gap * 2) // 2
    for bi in range(3):
        trows = [hdr_cells(["순위", "번호", "횟수", "비율", "기준대비"], sz=850)]
        for rk, m, k, rate, lift in rows[bi * 15:(bi + 1) * 15]:
            top = rk <= 3
            trows.append([
                {"t": f"{rk}", "sz": 850, "bold": top},
                {"t": f"{m}", "sz": 950, "bold": True, "font": "C00000" if top else HDR,
                 "fill": "FFF2CC" if top else None},
                {"t": f"{k}", "sz": 850},
                {"t": f"{rate:.1f}%", "sz": 850},
                {"t": f"{lift:.2f}", "sz": 850, "bold": lift >= 1.1,
                 "font": "C00000" if lift >= 1.1 else ("808080" if lift < 1 else "333333")},
            ])
        sh.append(gtable(fid, f"cd{bi}", bx + bi * (bw + gap), 1350000, cols, trows, row_h=270000)); fid += 1
    foot = ("※ 비율 = 이월 회차 중 다음 회차에 그 번호가 나온 비율(기본 6/45 ≈ 13.3%). "
            "기준대비 1.00 = 평소와 같음, 빨강 = 1.10배 이상 — 참고용 통계.")
    sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


VERDICT_STYLE = {"◎ 타이밍": ("C00000", "FCE4E4"), "기록경신": ("7030A0", "EDE2F6"),
                 "○ 관심": ("BF8F00", "FFF2CC"), "△ 보통": ("595959", None), "▽ 쉬어감": ("808080", "F2F2F2"),
                 "희귀": ("A6A6A6", None)}


def slides_timing(rows, base_no, next_no):
    """패턴별 타이밍 표 (2페이지)."""
    half = (len(rows) + 1) // 2
    pages = [rows[:half], rows[half:]]
    cols = [650000, 2550000, 800000, 900000, 900000, 900000, 1750000, 800000, 1000000]
    bx = (W - sum(cols)) // 2
    out = []
    for pi, blk in enumerate(pages):
        sh, fid = [], 2
        sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                          run(f"타이밍 통계 — 패턴별 미출현 기간과 {next_no}회 출현 확률  ({pi + 1}/2)",
                              2000, HDR, True))); fid += 1
        sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                          run(f"2~{base_no}회 실측 · 현재 미출현 = {base_no}회 기준 연속 미출현 회차(0 = {base_no}회에 출현) · "
                              f"다음회차 확률 = 과거 같은 미출현 길이 뒤 실제 출현 비율(근거: 미출현 길이·표본) · "
                              f"기준대비 = 평소 출현율 대비 배수", 1000, "808080"))); fid += 1
        trows = [hdr_cells(["구분", "패턴", "출현율", "평균 미출현", "최장 미출현", "현재 미출현",
                            "다음회차 확률(근거)", "기준대비", "판정"], sz=850)]
        for g, name, p, avg, longest, cur, prob, n, basis, lift, vd, _ in blk:
            fc, fill = VERDICT_STYLE[vd]
            near = longest and cur >= 0.8 * longest
            trows.append([
                {"t": g, "sz": 800, "font": "808080"},
                {"t": name, "sz": 800, "algn": "l", "bold": vd in ("◎ 타이밍", "기록경신")},
                {"t": f"{p * 100:.1f}%", "sz": 800},
                {"t": f"{avg:.1f}", "sz": 800},
                {"t": f"{longest}", "sz": 800},
                {"t": f"{cur}", "sz": 850, "bold": cur > avg,
                 "font": "C00000" if near else ("1F3864" if cur > avg else "333333")},
                {"t": f"{prob * 100:.1f}% ({basis}·{n})" if n else "표본 없음", "sz": 800},
                {"t": f"{lift:.2f}", "sz": 800, "bold": lift >= 1.2,
                 "font": "C00000" if lift >= 1.2 else ("808080" if lift < 0.9 else "333333")},
                {"t": vd, "sz": 800, "bold": True, "font": fc, "fill": fill},
            ])
        sh.append(gtable(fid, f"tm{pi}", bx, 1300000, cols, trows, row_h=210000)); fid += 1
        foot = ("※ 판정: ◎ 기준대비 1.2배↑(표본 20↑) · ○ 1.05배↑ 또는 최장 미출현의 80%↑ · △ 보통 · ▽ 0.9배 미만 · "
                "기록경신 = 현재 미출현이 역대 최장 이상 · 희귀 = 평소 3% 미만(판정 제외). 현재 미출현이 빨강 = 최장의 80% 이상.")
        sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                          run(foot, 950, "A6A6A6"))); fid += 1
        out.append(wrap_slide(sh))
    return out


TIMING_CURVE_KEYS = ["쌍수(11·22·33·44)", "연번(2연속)", "거울수 쌍(12↔21 등 동시)", "거울수 이월(전회 번호의 거울수)",
                     "이월수 1개 이상", "이월수 2개 이상", "동끝수 3개 이상", "40번대(41-45) 출현",
                     "합계 100 미만", "합계 176 이상", "전회 보너스 → 당첨"]


def slide_timing_curve(rows, base_no):
    """주요 패턴: 미출현 0~9회·10회+ 뒤 다음 회차 출현 확률 표. 현재 미출현 칸 강조."""
    by = {r[1]: r for r in rows}
    ks = list(range(10))
    cols = [2600000] + [760000] * 10 + [900000]
    bx = (W - sum(cols)) // 2
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run("미출현 기간별 다음 회차 출현 확률 — \"몇 회 안 나오면 다음에 나오나\"", 2000, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                      run(f"2~{base_no}회 실측 · 칸 = k회 연속 미출현 뒤 다음 회차 출현 비율(표본 15 미만은 회색) · "
                          f"노랑 테두리 칸 = {base_no}회 기준 현재 위치 · 맨 오른쪽 = 평소 출현율", 1000, "808080"))); fid += 1
    trows = [hdr_cells(["패턴"] + [f"{k}회" for k in ks] + ["10회+", "평소"], sz=850)]
    for key in TIMING_CURVE_KEYS:
        r = by.get(key)
        if not r:
            continue
        _, name, p, _, _, cur, _, _, _, _, _, hz = r
        row = [{"t": name, "sz": 800, "algn": "l", "bold": True}]
        for k in ks + [10]:
            if k < 10:
                hit, n = hz.get(k, [0, 0])
            else:
                hit = sum(v[0] for j, v in hz.items() if j >= 10)
                n = sum(v[1] for j, v in hz.items() if j >= 10)
            here = (cur == k) or (k == 10 and cur >= 10)
            if n == 0:
                c = {"t": "-", "sz": 750, "font": "C0C0C0"}
            else:
                pr = hit / n
                c = {"t": f"{pr * 100:.0f}%", "sz": 800,
                     "font": "C0C0C0" if n < TIMING_MIN_N else ("C00000" if pr >= p * 1.2 else
                                                                 ("808080" if pr < p * 0.9 else "333333")),
                     "bold": pr >= p * 1.2 and n >= TIMING_MIN_N}
            if here:
                c["fill"] = "FFF2CC"
                c["bold"] = True
            row.append(c)
        row.append({"t": f"{p * 100:.0f}%", "sz": 800, "font": HDR, "bold": True})
        trows.append(row)
    sh.append(gtable(fid, "tc", bx, 1350000, cols, trows, row_h=330000)); fid += 1
    foot = ("※ 빨강 = 평소보다 1.2배↑, 회색 글자 = 평소의 0.9배 미만 또는 표본 부족. "
            "독립 추첨이라면 모든 칸이 '평소' 근처여야 하며, 차이는 대부분 표본 흔들림입니다.")
    sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


def slide_timing_summary(rows, base_no, next_no, bt):
    """다음 회차 타이밍 요약: 나올 타이밍(◎·기록경신·○) / 쉬어갈 패턴(▽) + 백테스트."""
    order = {"기록경신": 0, "◎ 타이밍": 1, "○ 관심": 2}
    hot = sorted([r for r in rows if r[10] in order], key=lambda r: (order[r[10]], -r[9]))[:12]
    cold = sorted([r for r in rows if r[10] == "▽ 쉬어감"], key=lambda r: r[9])[:12]
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run(f"{next_no}회차 타이밍 요약 — 나올 타이밍 vs 쉬어갈 패턴", 2000, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 400000,
                      run(f"{base_no}회까지의 미출현 기간 기준 · 확률 = 과거 같은 미출현 길이 뒤 실제 출현 비율", 1100, "808080"))); fid += 1
    cols = [2550000, 700000, 1000000, 800000, 900000]
    gap = 350000
    bx = (W - 2 * sum(cols) - gap) // 2
    for side, (lst, label, color) in enumerate([(hot, "나올 타이밍", "C00000"), (cold, "쉬어갈 패턴", "808080")]):
        x = bx + side * (sum(cols) + gap)
        sh.append(textbox(fid, f"lbl{side}", x, 1230000, sum(cols), 330000,
                          run(label, 1400, color, True))); fid += 1
        trows = [hdr_cells(["패턴", "현재", "다음회차", "평소", "판정"], sz=850)]
        for _, name, p, avg, longest, cur, prob, n, basis, lift, vd, _ in lst:
            fc, fill = VERDICT_STYLE[vd]
            trows.append([
                {"t": name, "sz": 800, "algn": "l", "bold": True},
                {"t": f"{cur}회", "sz": 800},
                {"t": f"{prob * 100:.1f}%", "sz": 850, "bold": True, "font": fc},
                {"t": f"{p * 100:.1f}%", "sz": 800, "font": "808080"},
                {"t": vd, "sz": 800, "bold": True, "font": fc, "fill": fill},
            ])
        if len(trows) == 1:
            trows.append([{"t": "해당 없음", "sz": 800, "font": "808080"}] + [{"t": ""} for _ in range(4)])
        sh.append(gtable(fid, f"ts{side}", x, 1580000, cols, trows, row_h=270000)); fid += 1
    picks, hits, expect = bt
    if picks:
        btxt = (f"백테스트(최근 100회, 각 시점 데이터만 사용): '◎ 타이밍' 선정 {picks}건 중 다음 회차 실제 출현 {hits}건 "
                f"({hits / picks * 100:.1f}%) · 같은 패턴들의 평소 출현율 기준 기대 {expect:.1f}건 "
                f"({expect / picks * 100:.1f}%) → 실제/기대 {hits / expect:.2f}배")
    else:
        btxt = "백테스트(최근 100회): '◎ 타이밍' 선정 사례 없음"
    sh.append(textbox(fid, "bt", 400000, H - 900000, W - 800000, 380000,
                      run(btxt, 1050, "1F3864", True))); fid += 1
    sh.append(textbox(fid, "foot", 400000, H - 520000, W - 800000, 420000,
                      run("※ 로또는 독립 추첨이라 '오래 안 나왔으니 나온다'는 보장되지 않습니다. 실측 경향을 보는 참고용 통계.",
                          1000, "A6A6A6"))); fid += 1
    return wrap_slide(sh)


# ── 4) 정적 패키지 파트 ────────────────────────────────────────────────────
def content_types(n_slides):
    slides = "".join(
        f'<Override PartName="/ppt/slides/slide{i}.xml" '
        f'ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
        for i in range(1, n_slides + 1))
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
        '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
        '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
        '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
        '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>'
        f'{slides}</Types>'
    )


RELS_ROOT = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>'
    '</Relationships>'
)


def presentation(n_slides):
    sld_ids = "".join(f'<p:sldId id="{256 + i}" r:id="rId{10 + i}"/>' for i in range(n_slides))
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
        '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
        f'<p:sldIdLst>{sld_ids}</p:sldIdLst>'
        f'<p:sldSz cx="{W}" cy="{H}" type="screen16x9"/>'
        '<p:notesSz cx="6858000" cy="9144000"/></p:presentation>'
    )


def presentation_rels(n_slides):
    slides = "".join(
        f'<Relationship Id="rId{10 + i}" '
        f'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" '
        f'Target="slides/slide{i + 1}.xml"/>'
        for i in range(n_slides))
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>'
        '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps" Target="presProps.xml"/>'
        f'{slides}</Relationships>'
    )


PRESPROPS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:presentationPr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>'
)

SLIDE_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
    '</Relationships>'
)

SLIDE_LAYOUT = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1">'
    '<p:cSld name="Blank"><p:spTree>'
    '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
    '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>'
    '<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
    '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>'
)

SLIDE_LAYOUT_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>'
    '</Relationships>'
)

SLIDE_MASTER = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
    '<p:cSld><p:spTree>'
    '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
    '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>'
    '<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
    '</p:spTree></p:cSld>'
    '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" '
    'accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>'
    '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>'
    '<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>'
)

SLIDE_MASTER_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>'
    '</Relationships>'
)


def theme_xml():
    acc = "".join(f'<a:accent{i+1}><a:srgbClr val="{v}"/></a:accent{i+1}>'
                  for i, v in enumerate(["4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47"]))
    font = '<a:latin typeface="Calibri"/><a:ea typeface="Malgun Gothic"/><a:cs typeface="Calibri"/>'
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">'
        '<a:themeElements><a:clrScheme name="Office">'
        '<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>'
        '<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>'
        '<a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>'
        f'{acc}<a:hlink><a:srgbClr val="0563C1"/></a:hlink>'
        '<a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
        f'<a:fontScheme name="Office"><a:majorFont>{font}</a:majorFont><a:minorFont>{font}</a:minorFont></a:fontScheme>'
        '<a:fmtScheme name="Office">'
        '<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>'
        '<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
        '<a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
        '<a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>'
        '<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>'
        '<a:effectStyle><a:effectLst/></a:effectStyle>'
        '<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>'
        '<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>'
        '</a:fmtScheme></a:themeElements></a:theme>'
    )


def main():
    out = run_analyzer()
    r = parse(out)
    if not r["games"]:
        sys.exit("게임 파싱 실패")
    print(f"파싱: {r['next_no']}회 / z {len(r['z'])}행 / 분포 {len(r['dist'])}행 / "
          f"순위 {len(r['rank'])}행 / 게임 {len(r['games'])}")

    # 순위별 재출현 통계 + 간격(차이수) 반복 분석
    hi = r["base_no"]; lo = hi - GAP_REPEAT_WINDOW + 1
    rh_out = run_analyzer(["rankhits", str(lo), str(hi)])
    rhwin, rhrows = parse_rankhits(rh_out)
    rank_gaps, rh_cands, rh_periodic = parse_rankgaps(rh_out)
    print(f"순위재출현: {rhwin[2] if rhwin else 0}회 / {len(rhrows)}순위 / "
          f"◎후보 {len(rh_cands)}개 / ○주기 {rh_periodic}개")

    # 과거 10회(1218~1227) 패턴별 성적 상위 → 다음회차 예측
    base = r["base_no"]
    cr_out = run_analyzer(["customrank", str(base - 9), str(base)])
    patpred = parse_patpred(cr_out)
    print(f"패턴예측: {len(patpred)}블록 / "
          + " · ".join(f"{b['metric']} {len(b['rows'])}패턴·풀{len(b['pool'])}" for b in patpred))

    # ◎ 반복임계(4~7) 스윕 적중률 백테스트
    gc_out = run_analyzer(["gapcut", str(base - 9), str(base)])
    gc_info, gc_rows, gc_live = parse_gapcut(gc_out)
    print("간격컷스윕: " + " ".join(f"N{x['cut']}={x['hit']}/{x['total']}" for x in gc_rows))

    rank2num = {int(t[0]): t[1] for t in r["rank"]}
    slides = [slide_summary(r)]
    slides += slides_ranking(r)
    slides += [slide_games(r)]
    slides += slides_rankhits(rhwin, rhrows, rank2num, r["next_no"],
                              rank_gaps, rh_cands, rh_periodic)
    pp = slide_patpred(patpred)
    if pp:
        slides += [pp]
    ph = slide_pathit(parse_pathit(cr_out), patpred, r["next_no"])
    if ph:
        slides += [ph]
    gc = slide_gapcut(gc_info, gc_rows, gc_live)
    if gc:
        slides += [gc]
    gh = slide_gaphit(parse_gaphit(gc_out), r["next_no"])
    if gh:
        slides += [gh]

    # 다음회차 동반 출현 통계 (CSV 직접 집계)
    draws = load_draws()
    if base in draws:
        fstats = calc_followers(draws, base)
        slides += slides_followers(fstats, base, draws[base])
        slides += [slide_follow_apply(fstats, base, draws[base], r["next_no"])]
        print(f"동반출현: 1~{base}회 집계 / 45행 + {base}회 적용")

        # 이월수 통계: 번호별(N 이월 → 다음 회차 상위 3) + 이월 회차 단위
        cstats = calc_followers(draws, base, carry_only=True)
        latest_carry = carried(draws, base)
        slides += slides_followers(cstats, base, latest_carry, mode="carry")
        if latest_carry:
            slides += [slide_follow_apply(cstats, base, latest_carry, r["next_no"], mode="carry")]
        ev, crows = calc_carry_draws(draws, base)
        slides += [slide_carry_draws(ev, crows, base, r["next_no"], latest_carry)]
        print(f"이월통계: 번호별 45행 / 이월회차 {ev}회 / {base}회 이월수 {sorted(latest_carry)}")

        # 보너스 통계: 보너스는 당첨번호가 아니므로 '다음 회차 당첨번호로 나오는지' 조건으로만 사용
        bonus = load_bonus()
        bstats = calc_followers(draws, base, bonus=bonus)
        pairs = [t for t in bonus if t + 1 in draws and t + 1 <= base]
        bhit = sum(1 for t in pairs if bonus[t] in draws[t + 1])
        slides += slides_followers(bstats, base, {bonus[base]} if base in bonus else set(),
                                   mode="bonus", self_rate=(bhit, len(pairs)))
        if base in bonus:
            slides += [slide_follow_apply(bstats, base, {bonus[base]}, r["next_no"], mode="bonus")]
        print(f"보너스통계: 다음회차 당첨 {bhit}/{len(pairs)} / {base}회 보너스 {bonus.get(base)}")

        # 타이밍 통계: 패턴별 미출현 기간 → 다음 회차 출현 확률
        trows = calc_timing(draws, bonus, base)
        bt = backtest_timing(draws, bonus, base)
        slides += [slide_timing_summary(trows, base, r["next_no"], bt)]
        slides += slides_timing(trows, base, r["next_no"])
        slides += [slide_timing_curve(trows, base)]
        print(f"타이밍: {len(trows)}패턴 / 백테스트 ◎ {bt[1]}/{bt[0]} (기대 {bt[2]:.1f})")

    parts = {
        "[Content_Types].xml": content_types(len(slides)),
        "_rels/.rels": RELS_ROOT,
        "ppt/presentation.xml": presentation(len(slides)),
        "ppt/_rels/presentation.xml.rels": presentation_rels(len(slides)),
        "ppt/presProps.xml": PRESPROPS,
        "ppt/theme/theme1.xml": theme_xml(),
        "ppt/slideMasters/slideMaster1.xml": SLIDE_MASTER,
        "ppt/slideMasters/_rels/slideMaster1.xml.rels": SLIDE_MASTER_RELS,
        "ppt/slideLayouts/slideLayout1.xml": SLIDE_LAYOUT,
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels": SLIDE_LAYOUT_RELS,
    }
    for i, xml in enumerate(slides, 1):
        parts[f"ppt/slides/slide{i}.xml"] = xml
        parts[f"ppt/slides/_rels/slide{i}.xml.rels"] = SLIDE_RELS

    out_name = f"로또_{r['next_no']}회차_분석리포트.pptx" if r["next_no"] else "로또_분석리포트.pptx"
    out_pptx = os.path.join(ROOT, out_name)
    with zipfile.ZipFile(out_pptx, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    print(f"생성 완료: {out_pptx} ({len(slides)} 슬라이드)")


if __name__ == "__main__":
    main()
