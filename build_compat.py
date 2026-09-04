# -*- coding: utf-8 -*-
"""호환성_입력.csv → 엑셀(.xlsx) + 웹(.html) 동시 생성

입력 규칙
  · 릴리스 세트 하나가 한 행
  · 제품은 5번째 열부터. 헤더에 "제품명 (역할)" 형태로 적으면 역할까지 반영
  · 한 칸에 여러 버전은 공백 또는 쉼표로 구분
  · 버전 뒤 * 를 붙이면 단서 조건 있는 버전으로 표시 (※ 마크)
  · 버전 표시 순서는 자동 (내림차순)

사용
  python3 build_compat.py [입력.csv]
"""
import csv
import json
import os
import re
import sys

import xlsxwriter

ROOT = os.path.dirname(os.path.abspath(__file__))
CSV_IN = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "호환성_입력.csv")
XLSX_OUT = os.path.join(ROOT, "제품군_버전호환성_매트릭스.xlsx")
HTML_TPL = os.path.join(ROOT, "compat-matrix.html")
HTML_OUT = HTML_TPL

META_COLS = 4  # 세트ID, 상태, 출시, 지원종료
STATUS_CLASS = {"최신": "latest", "LTS": "lts", "지원중": "active", "지원종료": "eol"}


# ------------------------------------------------------------------ 입력 파싱

def slug(name, used, idx):
    """제품명 → 내부 식별자 (엑셀 명명 범위·JS 키로만 쓰임)

    영문/숫자가 있으면 그대로 쓰고(SDS+ → sds), 한글 전용 이름이면
    열 순서 기반으로 p1, p2 … 를 부여한다."""
    s = re.sub(r"[^0-9a-zA-Z]+", "", name).lower()
    if not s or s[0].isdigit():
        s = "p%d" % idx
    base, i = s, 2
    while s in used:
        s, i = "%s%d" % (base, i), i + 1
    used.add(s)
    return s


def vkey(v):
    """'5.10.2' → (5, 10, 2) — 자연스러운 버전 정렬"""
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-_]", v))


def load(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = [r for r in csv.reader(f) if any(c.strip() for c in r)]
    if len(rows) < 2:
        sys.exit("입력 CSV에 데이터 행이 없습니다: " + path)

    header = [c.strip() for c in rows[0]]
    has_note = header and header[-1].replace(" ", "") == "단서"
    prod_cells = header[META_COLS:-1] if has_note else header[META_COLS:]

    products, used = [], set()
    for i, cell in enumerate(prod_cells, 1):
        m = re.match(r"^(.*?)\s*\((.*)\)\s*$", cell)
        name, role = (m.group(1).strip(), m.group(2).strip()) if m else (cell, "")
        products.append({"id": slug(name, used, i), "name": name, "role": role})

    sets, versions = [], {p["id"]: set() for p in products}
    for row in rows[1:]:
        row = row + [""] * (len(header) - len(row))
        sid, status, released, eol = [c.strip() for c in row[:META_COLS]]
        if not sid:
            continue
        note = row[-1].strip() if has_note else ""

        vmap = {}
        for i, p in enumerate(products):
            entries = []
            for tok in re.split(r"[,\s]+", row[META_COLS + i].strip()):
                if not tok or tok == "—":
                    continue
                cond = tok.endswith("*")
                ver = tok.rstrip("*")
                entries.append({"v": ver, "note": note if cond else None})
                versions[p["id"]].add(ver)
            vmap[p["id"]] = entries

        sets.append({
            "id": sid,
            "statusLabel": status or "지원중",
            "status": STATUS_CLASS.get(status, "active"),
            "released": released,
            "eol": eol,
            "note": note,
            "v": vmap,
        })

    vlist = {pid: sorted(vs, key=vkey, reverse=True) for pid, vs in versions.items()}
    return products, vlist, sets


PRODUCTS, VERSIONS, SETS = load(CSV_IN)
NCOL = len(PRODUCTS)
MAXV = max((len(v) for v in VERSIONS.values()), default=0)
PNAME = {p["id"]: p["name"] for p in PRODUCTS}


def vers(s, pid):
    return [e["v"] for e in s["v"].get(pid, [])]


# ------------------------------------------------------------------ 엑셀 생성

def build_xlsx():
    wb = xlsxwriter.Workbook(XLSX_OUT)

    INK, INK2, INK3 = "#15181F", "#4A5160", "#7A8290"
    LINE, SURF2 = "#D5DAE2", "#EFF1F5"
    BRASS, BRASS_BG, BRASS_LINE = "#6E4B0F", "#F6E7C7", "#D9BC7E"
    GOOD, GOOD_BG, GOOD_LINE = "#18624A", "#DCEFE7", "#8AC5AE"
    F = lambda **k: wb.add_format(k)

    f_title = F(font_name="맑은 고딕", font_size=17, bold=True, font_color=INK)
    f_sub = F(font_name="맑은 고딕", font_size=10, font_color=INK3, text_wrap=True, valign="top")
    f_label = F(font_name="맑은 고딕", font_size=10, bold=True, font_color=INK2,
                align="right", valign="vcenter")
    f_pick = F(font_name="Consolas", font_size=12, bold=True, font_color=BRASS,
               bg_color=BRASS_BG, border=1, border_color=BRASS_LINE,
               align="center", valign="vcenter")
    f_result = F(font_name="Consolas", font_size=11, font_color=GOOD, bg_color=GOOD_BG,
                 border=1, border_color=GOOD_LINE, align="left", valign="vcenter", indent=1)
    f_hint = F(font_name="맑은 고딕", font_size=9, font_color=INK3, italic=True)
    f_h2 = F(font_name="맑은 고딕", font_size=13, bold=True, font_color=INK)
    f_colhead = F(font_name="맑은 고딕", font_size=11, bold=True, font_color=INK,
                  bg_color=SURF2, border=1, border_color=LINE, align="center", valign="vcenter")
    f_colrole = F(font_name="맑은 고딕", font_size=8.5, font_color=INK3, bg_color=SURF2,
                  border=1, border_color=LINE, align="center", valign="vcenter", text_wrap=True)
    f_cell = F(font_name="Consolas", font_size=11, font_color=INK3,
               border=1, border_color=LINE, align="center", valign="vcenter")
    f_th = F(font_name="맑은 고딕", font_size=9.5, bold=True, font_color=INK2, bg_color=SURF2,
             border=1, border_color=LINE, align="center", valign="vcenter", text_wrap=True)
    f_td = F(font_name="Consolas", font_size=10, font_color=INK2,
             border=1, border_color=LINE, align="left", valign="vcenter", indent=1)
    f_td_c = F(font_name="Consolas", font_size=10, font_color=INK2,
               border=1, border_color=LINE, align="center", valign="vcenter")
    f_setid = F(font_name="Consolas", font_size=10.5, bold=True, font_color=INK,
                border=1, border_color=LINE, align="center", valign="vcenter")
    f_note = F(font_name="맑은 고딕", font_size=9.5, font_color=BRASS,
               border=1, border_color=LINE, align="left", valign="vcenter",
               indent=1, text_wrap=True)
    cf_sel = F(bg_color=BRASS_BG, font_color=BRASS, bold=True, border=1, border_color=BRASS_LINE)
    cf_ok = F(bg_color=GOOD_BG, font_color=GOOD, bold=True, border=1, border_color=GOOD_LINE)
    cf_row = F(bg_color=GOOD_BG, font_color=GOOD)

    # --- 목록 (숨김)
    ws_l = wb.add_worksheet("목록")
    ws_l.hide()
    ws_l.write_row(0, 0, ["제품", "범위이름"], f_th)
    for i, p in enumerate(PRODUCTS):
        ws_l.write(1 + i, 0, p["name"])
        ws_l.write(1 + i, 1, "v_" + p["id"])
    for c, p in enumerate(PRODUCTS):
        col = 3 + c
        ws_l.write(0, col, p["name"], f_th)
        for r, v in enumerate(VERSIONS[p["id"]]):
            ws_l.write_string(1 + r, col, v)
        a1 = xlsxwriter.utility.xl_col_to_name(col)
        wb.define_name("v_" + p["id"],
                       "='목록'!${0}$2:${0}${1}".format(a1, 1 + len(VERSIONS[p["id"]])))
    wb.define_name("제품목록", "='목록'!$A$2:$A${}".format(1 + NCOL))

    # --- 데이터 (long format)
    ws_d = wb.add_worksheet("데이터")
    ws_d.write_row(0, 0, ["세트ID", "제품", "버전", "단서 조건", "매칭"], f_th)
    flat = []
    for s in SETS:
        for p in PRODUCTS:
            for e in s["v"].get(p["id"], []):
                flat.append((s["id"], p["name"], e["v"], e["note"] or ""))
    n = len(flat)
    for i, (sid, pname, ver, note) in enumerate(flat):
        r = 1 + i
        ws_d.write_string(r, 0, sid, f_setid)
        ws_d.write_string(r, 1, pname, f_td)
        ws_d.write_string(r, 2, ver, f_td_c)
        ws_d.write_string(r, 3, note, f_note)
        ws_d.write_formula(
            r, 4,
            '=IF(COUNTIFS($A$2:$A${n},$A{r},$B$2:$B${n},호환매트릭스!$C$5,'
            '$C$2:$C${n},호환매트릭스!$C$6)>0,1,0)'.format(n=n + 1, r=r + 1), f_td_c, 0)
    ws_d.set_column("A:A", 11); ws_d.set_column("B:B", 15); ws_d.set_column("C:C", 10)
    ws_d.set_column("D:D", 52); ws_d.set_column("E:E", 8)
    ws_d.freeze_panes(1, 0)
    ws_d.autofilter(0, 0, n, 4)
    DB = "데이터!$B$2:$B${}".format(n + 1)
    DC = "데이터!$C$2:$C${}".format(n + 1)
    DE = "데이터!$E$2:$E${}".format(n + 1)

    # --- 릴리스세트 원장
    ws_s = wb.add_worksheet("릴리스세트")
    ws_s.write(0, 1, "릴리스 세트 원장 — 한 세트 안의 버전들끼리 서로 호환됩니다", f_h2)
    ws_s.write_row(1, 1, ["릴리스 세트", "상태", "출시", "지원 종료"]
                   + [p["name"] for p in PRODUCTS] + ["선택 포함"], f_th)
    for i, s in enumerate(SETS):
        r = 2 + i
        ws_s.write_string(r, 1, s["id"], f_setid)
        ws_s.write_string(r, 2, s["statusLabel"], f_td_c)
        ws_s.write_string(r, 3, s["released"], f_td_c)
        ws_s.write_string(r, 4, s["eol"], f_td_c)
        for c, p in enumerate(PRODUCTS):
            v = vers(s, p["id"])
            ws_s.write_string(r, 5 + c, ", ".join(v) if v else "—", f_td)
        ws_s.write_formula(
            r, 5 + NCOL,
            '=IF(COUNTIFS(데이터!$A$2:$A${n},$B{r},데이터!$B$2:$B${n},호환매트릭스!$C$5,'
            '데이터!$C$2:$C${n},호환매트릭스!$C$6)>0,1,0)'.format(n=n + 1, r=r + 1), f_td_c, 0)
    flag = xlsxwriter.utility.xl_col_to_name(5 + NCOL)
    ws_s.conditional_format(2, 1, 1 + len(SETS), 5 + NCOL,
                            {"type": "formula", "criteria": "=${}3=1".format(flag),
                             "format": cf_row})
    ws_s.set_column("B:B", 13); ws_s.set_column("C:C", 10); ws_s.set_column("D:E", 11)
    ws_s.set_column(5, 4 + NCOL, 22); ws_s.set_column(5 + NCOL, 5 + NCOL, 10)
    ws_s.set_row(1, 30)
    ws_s.freeze_panes(2, 2)

    # --- 호환매트릭스 (조회 화면)
    ws = wb.add_worksheet("호환매트릭스")
    ws.activate()
    ws.write(1, 1, "제품군 버전 호환성 매트릭스", f_title)
    ws.merge_range(2, 1, 2, 1 + NCOL,
                   "제품과 버전을 고르면 함께 쓸 수 있는 버전이 초록으로 표시됩니다. "
                   "호환 기준은 개별 버전 쌍이 아니라 릴리스 세트이며, 한 버전이 여러 세트에 "
                   "걸치면 그 세트들의 합집합이 호환 범위가 됩니다.", f_sub)
    ws.set_row(2, 30)
    ws.write(4, 1, "제품", f_label)
    ws.write(5, 1, "버전", f_label)
    ws.write(6, 1, "매칭 세트", f_label)

    first = PRODUCTS[0]
    ws.write_string(4, 2, first["name"], f_pick)
    ws.write_string(5, 2, VERSIONS[first["id"]][0] if VERSIONS[first["id"]] else "", f_pick)

    concat = "&".join('IF(릴리스세트!${c}{r}=1,릴리스세트!$B{r}&"   ","")'.format(c=flag, r=3 + i)
                      for i in range(len(SETS)))
    ws.merge_range(6, 2, 6, 1 + NCOL, None, f_result)
    ws.write_formula(6, 2, "=TRIM({})".format(concat), f_result, "")
    ws.write(7, 2, "선택한 버전이 속한 릴리스 세트입니다. 여러 개면 합집합이 호환 범위가 됩니다.", f_hint)

    ws.write_formula(0, 12, "=VLOOKUP($C$5,목록!$A:$B,2,0)", None, "v_" + first["id"])
    ws.set_column("M:M", 2)
    ws.data_validation(4, 2, 4, 2, {"validate": "list", "source": "=제품목록",
                                    "input_title": "제품 선택"})
    ws.data_validation(5, 2, 5, 2, {"validate": "list", "source": "=INDIRECT($M$1)",
                                    "input_title": "버전 선택"})

    HR, RR, VR = 9, 10, 11
    for c, p in enumerate(PRODUCTS):
        ws.write_string(HR, 1 + c, p["name"], f_colhead)
        ws.write_string(RR, 1 + c, p["role"], f_colrole)
        vs = VERSIONS[p["id"]]
        for i in range(MAXV):
            ws.write_string(VR + i, 1 + c, vs[i] if i < len(vs) else "", f_cell)
    ws.set_row(HR, 22); ws.set_row(RR, 26)

    rng = (VR, 1, VR + MAXV - 1, NCOL)
    ws.conditional_format(*rng, {
        "type": "formula",
        "criteria": "=AND(B${}=$C$5,B{}=$C$6)".format(HR + 1, VR + 1),
        "format": cf_sel, "stop_if_true": True})
    ws.conditional_format(*rng, {
        "type": "formula",
        "criteria": "=SUMPRODUCT(({b}=B${h})*({c}=B{t})*({e}=1))>0".format(
            b=DB, c=DC, e=DE, h=HR + 1, t=VR + 1),
        "format": cf_ok})

    lg = VR + MAXV + 1
    ws.write(lg, 1, "범례", f_label)
    ws.write_string(lg, 2, "선택", f_pick)
    ws.write_string(lg, 3, "호환", F(font_name="Consolas", font_size=11, bold=True,
                                    font_color=GOOD, bg_color=GOOD_BG, border=1,
                                    border_color=GOOD_LINE, align="center"))
    ws.write_string(lg, 4, "비호환", f_cell)
    ws.write(lg + 1, 1, "데이터를 바꾸려면 호환성_입력.csv 를 수정한 뒤 "
                        "python3 build_compat.py 를 다시 실행하세요.", f_hint)

    ws.set_column("A:A", 3)
    ws.set_column(1, 1 + NCOL, 16)
    ws.freeze_panes(VR, 0)
    ws.hide_gridlines(2)
    wb.close()
    return n


# ------------------------------------------------------------------ HTML 생성

def build_html():
    if not os.path.exists(HTML_TPL):
        print("  (건너뜀) HTML 템플릿 없음:", HTML_TPL)
        return False
    with open(HTML_TPL, encoding="utf-8") as f:
        src = f.read()

    def j(o):
        return json.dumps(o, ensure_ascii=False, indent=2)

    sets_js = []
    for s in SETS:
        v = {p["id"]: [e["v"] if not e["note"] else {"v": e["v"], "note": e["note"]}
                       for e in s["v"].get(p["id"], [])] for p in PRODUCTS}
        sets_js.append({k: s[k] for k in
                        ("id", "released", "eol", "status", "statusLabel")} | {"v": v})

    block = (
        "  /* ===== DATA:BEGIN — build_compat.py 가 이 블록을 자동 생성합니다 ===== */\n\n"
        "  var PRODUCTS = %s;\n\n  var VERSIONS = %s;\n\n  var SETS = %s;\n\n"
        "  /* ===== DATA:END ===== */"
        % (j(PRODUCTS), j(VERSIONS), j(sets_js))
    )

    new, cnt = re.subn(
        r"  /\* ===== DATA:BEGIN.*?DATA:END ===== \*/", block, src, flags=re.S)
    if cnt != 1:
        sys.exit("HTML 템플릿에서 DATA:BEGIN … DATA:END 표식을 찾지 못했습니다.")
    with open(HTML_OUT, "w", encoding="utf-8") as f:
        f.write(new)
    return True


# ------------------------------------------------------------------ 실행

if __name__ == "__main__":
    print("입력:", CSV_IN)
    print("  제품 %d종 · 릴리스 세트 %d개 · 버전 %d개"
          % (NCOL, len(SETS), sum(len(v) for v in VERSIONS.values())))
    for p in PRODUCTS:
        print("   - %-14s %s" % (p["name"], " ".join(VERSIONS[p["id"]])))
    rows = build_xlsx()
    print("\n엑셀 생성:", XLSX_OUT, "(데이터 %d행)" % rows)
    if build_html():
        print("웹  생성:", HTML_OUT)
