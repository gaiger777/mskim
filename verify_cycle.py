# -*- coding: utf-8 -*-
"""1227회 추천 5게임의 번호들이 1227회 100회 주기 형제 회차(27·127·…·1127)에
나온 적 있는지 검증. '없는 번호'(주기 미출현)를 식별한다."""
import csv, re

TARGET = 1227
CSV = "src/resources/lotto.csv"
RESULT = "out/result.txt"

def load(path):
    d = {}
    with open(path, encoding="utf-8") as f:
        r = csv.reader(f); next(r)
        for row in r:
            if len(row) < 7: continue
            try: d[int(row[0])] = sorted(int(x) for x in row[1:7])
            except ValueError: pass
    return d

def parse_games(path):
    games = []
    with open(path, encoding="utf-8") as f:
        for ln in f:
            m = re.match(r"\[(\w) 게임\]\s*(.+?)\s*\(합", ln)
            if m:
                nums = [int(x) for x in re.findall(r"\d+", m.group(2))]
                games.append((m.group(1), sorted(nums)))
    return games

draws = load(CSV)
games = parse_games(RESULT)
sibs = sorted(s for s in range(TARGET-100, 0, -100))  # 1127,1027,...,27
print(f"대상: {TARGET}회 | 형제 회차({len(sibs)}개): {', '.join(map(str,sibs))}")

# 형제 회차에서 각 번호가 나온 회차 목록
appear = {}  # num -> [draws]
for n in range(1, 46):
    appear[n] = [s for s in sibs if n in draws.get(s, [])]

uniq = sorted({n for _, g in games for n in g})
print(f"\n추천 5게임 고유 번호({len(uniq)}개): {uniq}")
absent = [n for n in uniq if not appear[n]]
present = [n for n in uniq if appear[n]]
print(f"▶ 주기 미출현(형제에 한 번도 안 나온) 번호: {absent if absent else '없음'}")
print(f"▶ 주기 출현 번호: {len(present)}개\n")

print("번호별 형제 회차 출현 내역:")
for n in uniq:
    a = appear[n]
    tag = "  ★미출현" if not a else ""
    print(f"  {n:2d}번 → {', '.join(f'{x}회' for x in a) if a else '(없음)'}{tag}")

print("\n게임별 미출현 번호:")
for label, g in games:
    miss = [n for n in g if not appear[n]]
    print(f"  [{label}] {g} → 미출현: {miss if miss else '없음'}")
