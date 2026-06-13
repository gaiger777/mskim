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
CP = os.path.join(ROOT, "out", "production", "mskim")
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
                      run("1218~1227 각 회차를 직전 데이터로 예측한 패턴 성적 상위 5개 → 그 패턴들의 "
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

    foot = ("※ 1218~1227 사후(in-sample) 과적합 결과 — 62개 패턴 중 이 구간에서 우연히 잘 맞은 컷일 뿐, "
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


def slide_pathit(rows, next_no):
    """상위 패턴(distinct)이 1218~1227 각 회차에 실제로 맞춘 회차·번호."""
    if not rows:
        return None
    sh, fid = [], 2
    sh.append(textbox(fid, "title", 400000, 200000, W - 800000, 650000,
                      run("과거 10회 상위 패턴 — 회차별 적중 상세", 2100, HDR, True))); fid += 1
    sh.append(textbox(fid, "sub", 400000, 800000, W - 800000, 360000,
                      run(f"{next_no}회 예측에 쓰인 상위 패턴들이 1218~1227 각 회차에 실제로 맞춘 번호 (회차:번호)",
                          1100, "808080"))); fid += 1
    cols = [2500000, 8100000]
    bx = (W - sum(cols)) // 2
    trows = [hdr_cells(["패턴", "적중 회차:번호 (회당)"])]
    for pat, hits in rows:
        if hits:
            txt = f"({len(hits)}회)  " + "   ".join(f"{rd}:{','.join(map(str, nums))}" for rd, nums in hits)
        else:
            txt = "-"
        trows.append([
            {"t": pat, "sz": 950, "algn": "l", "bold": True},
            {"t": txt, "sz": 880, "algn": "l", "font": "C00000" if hits else "C0C0C0"},
        ])
    sh.append(gtable(fid, "pht", bx, 1350000, cols, trows, row_h=380000)); fid += 1
    foot = "※ 1218~1227 사후 실측 — 과거 적중일 뿐 미래 적중 보장 아님(로또는 독립 난수)."
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
                      run("1218~1227 각 회차의 ◎번호(반복수≥4)와 실제 당첨된 번호(빨강). "
                          "반복수가 N이면 컷오프 N까지의 ◎에 포함됩니다.", 1100, "808080"))); fid += 1
    cols = [1100000, 7800000, 1700000]
    bx = (W - sum(cols)) // 2
    trows = [hdr_cells(["회차", "◎번호 : 반복수", "적중번호"])]
    for rd, marks, hits in rows:
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
    sh.append(gtable(fid, "ght", bx, 1320000, cols, trows, row_h=330000)); fid += 1
    foot = ("※ 반복수 N = 그 다음간격이 과거 N회 반복. N=4·5·6·7 컷오프 포함 여부가 갈립니다. "
            "1218~1227 사후 실측 — 미래 적중 보장 아님.")
    sh.append(textbox(fid, "foot", 400000, H - 540000, W - 800000, 440000,
                      run(foot, 1000, "A6A6A6"))); fid += 1
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
    ph = slide_pathit(parse_pathit(cr_out), r["next_no"])
    if ph:
        slides += [ph]
    gc = slide_gapcut(gc_info, gc_rows, gc_live)
    if gc:
        slides += [gc]
    gh = slide_gaphit(parse_gaphit(gc_out), r["next_no"])
    if gh:
        slides += [gh]
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
