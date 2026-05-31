#!/usr/bin/env python3
"""로또 패턴 분석 리포트 PPTX 생성기.

LottoPatternAnalyzer 콘솔 출력을 파싱해 분석리포트 PPT를 만든다.
  · 슬라이드1: 패턴별 효과크기(z) 순위 + 순위그룹 분포 + 다음회차 요약
  · 슬라이드2: 1~45 종합 순위 (자동번호에 쓰인 번호 강조)
  · 슬라이드3: 자동 추천 5게임을 로또 용지(7열×7행)에 표기

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
OUT_PPTX = os.path.join(ROOT, "로또_분석리포트.pptx")
CP = os.path.join(ROOT, "out", "production", "mskim")

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
        m = re.search(r"(\d+)위\s*\|\s*(\d+)번\s*\|\s*([\d.]+)점\s*\|\s*(.*)", ln)
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


# ── 2) OOXML 조각 ─────────────────────────────────────────────────────────
def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def cell(text, fill=None, font="333333", bold=False, sz=1100, border=True, algn="ctr"):
    runs = ""
    if text != "":
        runs = (f'<a:r><a:rPr lang="ko-KR" sz="{sz}" b="{1 if bold else 0}">'
                f'<a:solidFill><a:srgbClr val="{font}"/></a:solidFill></a:rPr>'
                f'<a:t>{esc(text)}</a:t></a:r>')
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
                           algn=c.get("algn", "ctr")) for c in row)
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


def slide_ranking(r):
    sh = []
    fid = 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run("1~45 종합 순위 (분석기 가중 합산 점수)", 2200, HDR, True))); fid += 1
    used = set()
    for _, nums in r["games"]:
        used.update(nums)
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 360000,
                      run("노랑 음영 = 자동 추천 5게임에 포함된 번호", 1200, "808080"))); fid += 1

    rank = sorted(r["rank"], key=lambda t: t[0])[:45]
    per = 15  # 3블록 × 15
    blocks = [rank[i:i + per] for i in range(0, len(rank), per)]
    cols = [640000, 760000, 1080000, 1600000]  # 순위, 번호, 점수, 기여패턴(축약)
    bx0, pitch = 400000, 3850000
    for bi, blk in enumerate(blocks):
        rows = [hdr_cells(["순위", "번호", "점수", "기여 패턴"])]
        for (rk, num, sc, tags) in blk:
            t = tags if len(tags) <= 11 else tags[:10] + "…"
            hl = num in used
            rows.append([
                {"t": f"{rk}", "sz": 850, "bold": rk <= 6},
                {"t": f"{num}", "sz": 900, "bold": True,
                 "fill": "FFF2CC" if hl else None, "font": "BF8F00" if hl else "1F3864"},
                {"t": sc, "sz": 850},
                {"t": t, "sz": 750, "algn": "l", "font": "808080"},
            ])
        sh.append(gtable(fid, f"rk{bi}", bx0 + bi * pitch, 1300000, cols, rows, row_h=320000)); fid += 1
    return wrap_slide(sh)


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
                    f"다음간격 = {next_no}회 − 마지막 재출현회차  ·  ◎ = 다음간격 과거 3회↑ 반복 / ○ = 주기보유 / − = 없음")
            sh.append(textbox(fid, "sub", 400000, 740000, W - 800000, 360000,
                              run(note, 1000, "808080"))); fid += 1
            if cands:
                cands_sorted = sorted(cands, key=lambda c: -c[3])
                txt = "  ".join(f"{num}번(순위{rk}·다음간격{g}·과거{pc}회반복)"
                                for (rk, num, g, pc) in cands_sorted)
                banner = f"◎ {next_no}회 재출현 가능성 높음 (다음간격이 과거 3회 이상 반복): {txt}"
                bcolor = "C00000"
            else:
                banner = f"◎ {next_no}회: 다음간격이 과거 3회 이상 반복된 순위 없음 (○ 주기보유 {periodic}개 참고)"
                bcolor = "808080"
            sh.append(textbox(fid, "banner", 400000, 1150000, W - 800000, 360000,
                              run(banner, 1100, bcolor, True))); fid += 1
            ty = 1620000
        else:
            ty = 820000
        trows = [hdr_cells(["순위", "번호", "적중", "간격(차이수) — 차이(전회차→후회차)", "다음간격", "가능성"])]
        for (rk, cnt, ds) in blk:
            hot = cnt >= 5
            zero = cnt == 0
            num = rank2num.get(rk, "")
            gaps_str, nextgap, ch = rank_gaps.get(rk, ("-", "-", "-"))
            poss_font = "C00000" if ch == "◎" else ("1F3864" if ch == "○" else "C0C0C0")
            trows.append([
                {"t": f"{rk}위", "sz": 850, "bold": rk <= 3},
                {"t": f"{num}", "sz": 900, "bold": True,
                 "fill": "FFF2CC" if rk <= 3 else None,
                 "font": "BF8F00" if rk <= 3 else "1F3864"},
                {"t": f"{cnt}", "sz": 850, "bold": True,
                 "font": "C00000" if hot else ("C0C0C0" if zero else "1F3864")},
                {"t": gaps_str, "sz": 700, "algn": "l", "font": "808080"},
                {"t": nextgap, "sz": 850, "bold": True, "font": "1F3864"},
                {"t": ch, "sz": 950, "bold": True,
                 "fill": "FFF2CC" if ch == "◎" else None, "font": poss_font},
            ])
        sh.append(gtable(fid, f"rh{bi}", bx, ty, cols, trows, row_h=300000)); fid += 1
        out_slides.append(wrap_slide(sh))
    return out_slides


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

    # 순위별 재출현 통계 + 간격(차이수) 반복 분석 (최근 100회로 범위 확대)
    hi = r["base_no"]; lo = hi - 99
    rh_out = run_analyzer(["rankhits", str(lo), str(hi)])
    rhwin, rhrows = parse_rankhits(rh_out)
    rank_gaps, rh_cands, rh_periodic = parse_rankgaps(rh_out)
    print(f"순위재출현: {rhwin[2] if rhwin else 0}회 / {len(rhrows)}순위 / "
          f"◎후보 {len(rh_cands)}개 / ○주기 {rh_periodic}개")

    rank2num = {int(t[0]): t[1] for t in r["rank"]}
    slides = [slide_summary(r), slide_ranking(r), slide_games(r)]
    slides += slides_rankhits(rhwin, rhrows, rank2num, r["next_no"],
                              rank_gaps, rh_cands, rh_periodic)
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

    with zipfile.ZipFile(OUT_PPTX, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    print(f"생성 완료: {OUT_PPTX} ({len(slides)} 슬라이드)")


if __name__ == "__main__":
    main()
