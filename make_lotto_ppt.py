#!/usr/bin/env python3
"""로또 자동 추천 5게임을 '로또 용지' 그리드에 표기한 PPTX를 생성한다.

- LottoPatternAnalyzer 를 실행해 그 회차의 5게임([A~E 게임] 줄)을 파싱한다.
- 1~45를 7열×7행(행 우선: 1~7 / 8~14 / … / 43~45)으로 배열한 용지 그리드 5개(A~E)를
  한 슬라이드에 나란히 그리고, 각 게임의 6개 번호 칸을 빨강으로 채워 표기한다.
- 외부 라이브러리 없이 stdlib(zipfile)만으로 OOXML(.pptx)을 직접 작성한다.

사용:  python3 make_lotto_ppt.py
출력:  로또_자동번호_5게임.pptx (프로젝트 루트)
"""
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT_PPTX = os.path.join(ROOT, "로또_자동번호_5게임.pptx")
CP = os.path.join(ROOT, "out", "production", "mskim")

EMU = 914400  # per inch


# ── 1) Java 실행 → 5게임 + 회차 파싱 ──────────────────────────────────────
def run_analyzer():
    proc = subprocess.run(
        ["java", "-cp", CP, "LottoPatternAnalyzer"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"Java 실행 실패:\n{proc.stderr}")
    return proc.stdout


def parse(out):
    base = re.search(r"기준 회차:\s*(\d+)회", out)
    next_no = (int(base.group(1)) + 1) if base else None
    games = []
    for line in out.splitlines():
        m = re.search(r"\[(.) 게임\]\s*([\d,\s]+?)\s*\(", line)
        if m:
            nums = sorted(int(x) for x in m.group(2).split(","))
            games.append((m.group(1), nums))
    return next_no, games


# ── 2) OOXML 조각 ─────────────────────────────────────────────────────────
def esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def cell(text, fill=None, font="000000", bold=False, border=True):
    """표 셀(a:tc) XML. fill=None이면 채움 없음."""
    runs = ""
    if text != "":
        runs = (f'<a:r><a:rPr lang="ko-KR" sz="1100" b="{1 if bold else 0}">'
                f'<a:solidFill><a:srgbClr val="{font}"/></a:solidFill></a:rPr>'
                f'<a:t>{esc(text)}</a:t></a:r>')
    ln = ('<a:lnL w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnL>'
          '<a:lnR w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnR>'
          '<a:lnT w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnT>'
          '<a:lnB w="6350"><a:solidFill><a:srgbClr val="BBBBBB"/></a:solidFill></a:lnB>') if border else ""
    fillxml = f'<a:solidFill><a:srgbClr val="{fill}"/></a:solidFill>' if fill else ""
    return (f'<a:tc><a:txBody>'
            f'<a:bodyPr lIns="9525" rIns="9525" tIns="9525" bIns="9525" anchor="ctr"/><a:lstStyle/>'
            f'<a:p><a:pPr algn="ctr"/>{runs}</a:p></a:txBody>'
            f'<a:tcPr anchor="ctr" marL="0" marR="0" marT="0" marB="0">{ln}{fillxml}</a:tcPr></a:tc>')


def grid_table(frame_id, name, x, y, chosen):
    """7열×7행 로또 용지 그리드(a:tbl)를 graphicFrame으로."""
    cw, rh = 300000, 300000
    cols = "".join(f'<a:gridCol w="{cw}"/>' for _ in range(7))
    rows = ""
    for r in range(7):
        cells = ""
        for c in range(7):
            num = r * 7 + c + 1
            if num > 45:
                cells += cell("", fill="EFEFEF")
            elif num in chosen:
                cells += cell(num, fill="C00000", font="FFFFFF", bold=True)
            else:
                cells += cell(num, fill="FFFFFF", font="333333")
        rows += f'<a:tr h="{rh}">{cells}</a:tr>'
    return (f'<p:graphicFrame><p:nvGraphicFramePr>'
            f'<p:cNvPr id="{frame_id}" name="{name}"/><p:cNvGraphicFramePr/><p:nvPr/>'
            f'</p:nvGraphicFramePr>'
            f'<p:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cw*7}" cy="{rh*7}"/></p:xfrm>'
            f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">'
            f'<a:tbl><a:tblPr firstRow="0" bandRow="0"/><a:tblGrid>{cols}</a:tblGrid>{rows}</a:tbl>'
            f'</a:graphicData></a:graphic></p:graphicFrame>')


def textbox(sp_id, name, x, y, cx, cy, runs, algn="ctr", anchor="t"):
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sp_id}" name="{name}"/>'
            f'<p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square" anchor="{anchor}"/><a:lstStyle/>'
            f'<a:p><a:pPr algn="{algn}"/>{runs}</a:p></p:txBody></p:sp>')


def run(text, sz, color="000000", bold=False):
    return (f'<a:r><a:rPr lang="ko-KR" sz="{sz}" b="{1 if bold else 0}">'
            f'<a:solidFill><a:srgbClr val="{color}"/></a:solidFill></a:rPr>'
            f'<a:t>{esc(text)}</a:t></a:r>')


def build_slide(next_no, games):
    margin = 400000
    cw = rh = 300000
    grid_w = cw * 7
    grid_h = rh * 7
    pitch = (12192000 - 2 * margin - grid_w) // 4  # 5개 그리드 중심간 간격(좌우 여백 균등)
    grid_y = 1700000
    shapes = []
    sid = 10

    title = f"{next_no}회차 자동 추천 5게임" if next_no else "자동 추천 5게임"
    shapes.append(textbox(2, "title", margin, 200000, 12192000 - 2 * margin, 700000,
                          run(title, 2400, "1F3864", True)))
    shapes.append(textbox(3, "subtitle", margin, 900000, 12192000 - 2 * margin, 500000,
                          run("로또 용지(7열×7행) 표기 · 빨강 = 선택 번호 · 세로열 최대 2개", 1300, "808080")))

    for i, (label, nums) in enumerate(games):
        x = margin + i * pitch
        # 게임 라벨
        shapes.append(textbox(sid, f"lbl{label}", x, grid_y - 420000, grid_w, 380000,
                              run(f"{label} 게임", 1600, "C00000", True)))
        sid += 1
        # 그리드
        shapes.append(grid_table(sid, f"grid{label}", x, grid_y, set(nums)))
        sid += 1
        # 번호 + 합계
        total = sum(nums)
        odd = sum(1 for n in nums if n % 2)
        numtxt = " ".join(f"{n:02d}" for n in nums)
        shapes.append(textbox(sid, f"num{label}", x, grid_y + grid_h + 100000, grid_w, 400000,
                              run(numtxt, 1100, "1F3864", True), algn="ctr"))
        sid += 1
        shapes.append(textbox(sid, f"meta{label}", x, grid_y + grid_h + 520000, grid_w, 360000,
                              run(f"합 {total} · 홀{odd}:짝{6-odd}", 1000, "808080"), algn="ctr"))
        sid += 1

    sp_xml = "".join(shapes)
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
        '<p:cSld><p:spTree>'
        '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>'
        '<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
        f'{sp_xml}'
        '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>'
    )


# ── 3) 정적 패키지 파트(테마/마스터/레이아웃 등) ───────────────────────────
CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
    '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
    '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
    '<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
    '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
    '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>'
    '</Types>'
)

RELS_ROOT = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>'
    '</Relationships>'
)

PRESENTATION = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
    '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
    '<p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>'
    '<p:sldSz cx="12192000" cy="6858000" type="screen16x9"/>'
    '<p:notesSz cx="6858000" cy="9144000"/>'
    '</p:presentation>'
)

PRESENTATION_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>'
    '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>'
    '<Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps" Target="presProps.xml"/>'
    '</Relationships>'
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
    '<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>'
    '</p:sldMaster>'
)

SLIDE_MASTER_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>'
    '</Relationships>'
)


def theme_xml():
    accents = ["4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47"]
    acc = "".join(
        f'<a:{("accent%d" % (i + 1))}><a:srgbClr val="{v}"/></a:{("accent%d" % (i + 1))}>'
        for i, v in enumerate(accents))
    font = ('<a:latin typeface="Calibri"/><a:ea typeface="Malgun Gothic"/>'
            '<a:cs typeface="Calibri"/>')
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">'
        '<a:themeElements><a:clrScheme name="Office">'
        '<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>'
        '<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>'
        '<a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>'
        f'{acc}'
        '<a:hlink><a:srgbClr val="0563C1"/></a:hlink>'
        '<a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
        '<a:fontScheme name="Office">'
        f'<a:majorFont>{font}</a:majorFont><a:minorFont>{font}</a:minorFont></a:fontScheme>'
        '<a:fmtScheme name="Office">'
        '<a:fillStyleLst>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>'
        '<a:lnStyleLst>'
        '<a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
        '<a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
        '<a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>'
        '<a:effectStyleLst>'
        '<a:effectStyle><a:effectLst/></a:effectStyle>'
        '<a:effectStyle><a:effectLst/></a:effectStyle>'
        '<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>'
        '<a:bgFillStyleLst>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>'
        '</a:fmtScheme></a:themeElements></a:theme>'
    )


def main():
    out = run_analyzer()
    next_no, games = parse(out)
    if len(games) == 0:
        sys.exit("게임을 파싱하지 못했습니다.")
    print(f"파싱: {next_no}회 / {len(games)}게임")
    for label, nums in games:
        print(f"  {label}: {nums}")

    parts = {
        "[Content_Types].xml": CONTENT_TYPES,
        "_rels/.rels": RELS_ROOT,
        "ppt/presentation.xml": PRESENTATION,
        "ppt/_rels/presentation.xml.rels": PRESENTATION_RELS,
        "ppt/presProps.xml": PRESPROPS,
        "ppt/theme/theme1.xml": theme_xml(),
        "ppt/slideMasters/slideMaster1.xml": SLIDE_MASTER,
        "ppt/slideMasters/_rels/slideMaster1.xml.rels": SLIDE_MASTER_RELS,
        "ppt/slideLayouts/slideLayout1.xml": SLIDE_LAYOUT,
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels": SLIDE_LAYOUT_RELS,
        "ppt/slides/slide1.xml": build_slide(next_no, games),
        "ppt/slides/_rels/slide1.xml.rels": SLIDE_RELS,
    }

    with zipfile.ZipFile(OUT_PPTX, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    print(f"생성 완료: {OUT_PPTX}")


if __name__ == "__main__":
    main()
