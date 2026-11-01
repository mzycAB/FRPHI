# -*- coding: utf-8 -*-
"""
PEZ 导出结构校验脚本
复刻 Phira prpr/src/parse/rpe.rs 的 serde 反序列化规则，验证生成 chart.json 能被 Phira 读取。
同时验证 info.txt 的 info_from_txt 解析规则。

导出逻辑（v0.2.x 起）：**不吸附**（时间按精确分数、位置按 posX×675 原样）+
多判定线（每条线自己一份 eventLayers，speedEvents 就是流速变化）+ above/isFake。
本脚本按同一套逻辑造样例，并额外校验"时间/位置往返无损"。
"""
import json
import sys
import zipfile
import io
from math import gcd

FAIL = []

def check(cond, msg):
    if cond:
        print(f"  [OK] {msg}")
    else:
        FAIL.append(msg)
        print(f"  [FAIL] {msg}")

# ---------- 1. 用与 Kotlin 相同的逻辑构造一份示例 chart ----------
# 新导出**不吸附**：beat = ms×bpm/60_000 精确约分成 [i, n, d]（对应 PezExporter.beatTriple）
def beat_triple(ms, bpm):
    ms = max(0, int(ms))
    bpm_milli = int(round(bpm * 1000.0))
    num = ms * bpm_milli
    den = 60_000_000
    g = gcd(num, den) or 1
    num //= g
    den //= g
    return (num // den, num % den, den)

def triple_json(t):
    return f'[{t[0]}, {t[1]}, {t[2]}]'

def triple_value(t):
    return t[0] + t[1] / t[2]

# 位置也不吸附：positionX = posX × 675（保留 3 位小数，对应 trimNum）
def quantize_posx(norm):
    return round(norm * 675.0, 3)


def build_line(name, line_y, speed_kfs, notes, bpm, delay):
    """speed_kfs: [(ms, 流速), ...]（分段常速）；空 = 恒速 1.2"""
    out = []
    out.append('    {')
    out.append(f'      "Name": "{name}",')
    out.append('      "Texture": "line.png",')
    out.append('      "father": -1,')
    out.append('      "rotateWithFather": false,')
    out.append('      "eventLayers": [')
    out.append('        {')
    out.append('          "alphaEvents": [ { "startTime": [0,0,1], "endTime": [100000,0,1], "start": 255.0, "end": 255.0, "easingType": 0 } ],')
    out.append('          "moveXEvents": [ { "startTime": [0,0,1], "endTime": [100000,0,1], "start": 0.0, "end": 0.0, "easingType": 0 } ],')
    out.append(f'          "moveYEvents": [ {{ "startTime": [0,0,1], "endTime": [100000,0,1], "start": {line_y}, "end": {line_y}, "easingType": 0 }} ],')
    if speed_kfs:
        evs = []
        for i, (t, v) in enumerate(speed_kfs):
            st = beat_triple(t, bpm)
            en = beat_triple(speed_kfs[i + 1][0], bpm) if i + 1 < len(speed_kfs) else (100000, 0, 1)
            evs.append(
                '{ "startTime": %s, "endTime": %s, "start": %s, "end": %s, "easingType": 0 }'
                % (triple_json(st), triple_json(en), v, v)
            )
        out.append('          "speedEvents": [ ' + ', '.join(evs) + ' ]')
    else:
        out.append('          "speedEvents": [ { "startTime": [0,0,1], "endTime": [100000,0,1], "start": 1.2, "end": 1.2, "easingType": 0 } ]')
    out.append('        }')
    out.append('      ],')
    out.append('      "notes": [')
    for i, n in enumerate(notes):
        start_ms, end_ms, posx, ntype, above, fake = n
        t0 = beat_triple(max(0, start_ms - delay), bpm)
        t1 = beat_triple(max(0, end_ms - delay), bpm)
        x = quantize_posx(posx)
        out.append('        {')
        out.append(f'          "type": {ntype},')
        out.append(f'          "above": {above},')
        out.append(f'          "startTime": {triple_json(t0)},')
        out.append(f'          "endTime": {triple_json(t1)},')
        out.append(f'          "positionX": {x},')
        out.append('          "yOffset": 0.0,')
        out.append('          "alpha": 255,')
        out.append('          "size": 1.0,')
        out.append('          "speed": 1.0,')
        out.append(f'          "isFake": {fake},')
        out.append('          "visibleTime": 999999.0')
        out.append('        }' + (',' if i != len(notes) - 1 else ''))
    out.append('      ],')
    out.append('      "isCover": 1,')
    out.append('      "isGif": false,')
    out.append('      "zOrder": 0,')
    out.append('      "posControl": [],')
    out.append('      "sizeControl": [],')
    out.append('      "alphaControl": [],')
    out.append('      "yControl": []')
    out.append('    }')
    return out


def build_chart(meta, lines):
    """lines: [(name, line_y, speed_kfs, notes)]"""
    out = []
    out.append('{')
    out.append('  "META": {')
    out.append('    "id": "1",')
    out.append(f'    "name": "{meta["name"]}",')
    out.append(f'    "level": "{meta["level"]}",')
    out.append('    "background": "bg.png",')
    out.append(f'    "charter": "{meta["charter"]}",')
    out.append(f'    "composer": "{meta["composer"]}",')
    out.append('    "illustration": "",')
    out.append('    "song": "music.mp3",')
    out.append('    "offset": 0,')
    out.append('    "RPEVersion": 160')
    out.append('  },')
    out.append('  "BPMList": [')
    out.append(f'    {{ "bpm": {meta["bpm"]}, "startTime": [0, 0, 1] }}')
    out.append('  ],')
    out.append('  "judgeLineList": [')
    body = []
    for i, (name, ly, kfs, notes) in enumerate(lines):
        body.extend(build_line(name, ly, kfs, notes, meta["bpm"], meta["delay"]))
        if i != len(lines) - 1:
            body[-1] = body[-1] + ','
    out.extend(body)
    out.append('  ]')
    out.append('}')
    return '\n'.join(out)


meta = {"name": "Test Song", "composer": "Tester", "charter": "You",
        "level": "12", "bpm": 120.0, "delay": 30}

# (startMs, endMs, posX, type, above, isFake)
notes_main = [
    (0, 0, 0.0, 1, 1, 0),           # Tap 0ms
    (500, 500, 0.5, 3, 1, 0),       # Flick 500ms（正好在格上）
    (1000, 1500, -0.5, 2, 1, 0),    # Hold 1000-1500ms
    (1234, 1234, 0.1234, 1, 1, 0),  # **非格点**时间/位置：不能被吸附
    (2750, 2750, -0.75, 3, 0, 0),   # 反面音符（above=0）
]
notes_second = [
    (2000, 2000, 0.3, 1, 1, 1),     # 假音符（isFake=1，只显示不判定）
    (2600, 2600, -0.3, 4, 1, 0),
]
lines = [
    ("line", (50 - 40) * 9, [], notes_main),                          # 40% → +90
    ("line1", (50 - 70) * 9, [(0, 1.0), (1000, 2.0)], notes_second),  # 70% → -180，流速 1 → 2
]
chart_text = build_chart(meta, lines)
chart = json.loads(chart_text)

print("== 1. RPEJSON 顶层结构（rpe.rs RPEChart）==")
check("META" in chart, "chart.META 存在")
check("BPMList" in chart, "chart.BPMList 存在")
check("judgeLineList" in chart, "chart.judgeLineList 存在")

meta_ = chart["META"]
check(isinstance(meta_.get("offset"), int), f"META.offset 是 int（实际 {type(meta_.get('offset')).__name__}）")
check(isinstance(meta_.get("RPEVersion"), int), "META.RPEVersion 是 int")
check(isinstance(meta_.get("name"), str), "META.name 是 str")
check(isinstance(meta_.get("song"), str), "META.song 是 str")

check(len(chart["BPMList"]) >= 1, "BPMList 非空")
b0 = chart["BPMList"][0]
check(isinstance(b0.get("bpm"), (int, float)), "BPMList[0].bpm 数值")
check(isinstance(b0.get("startTime"), list) and len(b0["startTime"]) == 3, "BPMList[0].startTime 是 [i,n,d]")

print()
print("== 2. judgeLine 结构（RPEJudgeLine）==")
check(len(chart["judgeLineList"]) == 2, "多判定线：两条都写出来了")
jl = chart["judgeLineList"][0]
check(jl.get("Name") is not None, 'judgeLine.Name 存在（大写 N）')
check(jl.get("Texture") is not None, 'judgeLine.Texture 存在')
check("father" in jl, 'judgeLine.father 存在（小写）')
check("eventLayers" in jl and isinstance(jl["eventLayers"], list), "judgeLine.eventLayers 数组")
layers = [l for l in jl.get("eventLayers", []) if l]
check(len(layers) > 0, "eventLayers 至少一个有效层")
if layers:
    ev = layers[0]
    sp = [e for e in ev.get("speedEvents", []) if e]
    mv = [e for e in ev.get("moveYEvents", []) if e]
    check(sp and sp[0]["start"] == 1.2, f"speedEvents 流速 = 1.2（实际 {sp[0]['start'] if sp else None}）")
    check(mv and mv[0]["start"] == 90, f"moveYEvents 判定线位置(40%，靠上) → +90（RPE 向上为正）实际 {mv[0]['start'] if mv else None}")
    check(ev.get("alphaEvents") and isinstance(ev["alphaEvents"], list), "alphaEvents 恒定 255（保证判定线可见）")
    check(ev.get("moveXEvents") and isinstance(ev["moveXEvents"], list), "moveXEvents 恒定 0（水平居中）")

# 第二条线：流速变化（1 → 2）+ 位置 70% → -180
jl2 = chart["judgeLineList"][1]
ev2 = [l for l in jl2["eventLayers"] if l][0]
sp2 = [e for e in ev2["speedEvents"] if e]
check(len(sp2) == 2, f"流速变化：第二条线有 2 段 speedEvents（实际 {len(sp2)}）")
check(sp2[0]["start"] == 1.0 and sp2[1]["start"] == 2.0, "两段流速分别是 1.0 和 2.0")
check([e for e in ev2["moveYEvents"] if e][0]["start"] == -180, "第二条线 70% → -180")

check("notes" in jl and isinstance(jl["notes"], list), "judgeLine.notes 数组")
check(isinstance(jl.get("isCover"), int), "judgeLine.isCover int")
check("zOrder" in jl, "judgeLine.zOrder")

print()
print("== 3. note 结构（RPENote）==")
nt = jl["notes"][0]
for k in ["type", "above", "startTime", "endTime", "positionX", "yOffset", "alpha", "size", "speed", "isFake", "visibleTime"]:
    check(k in nt, f"note.{k} 存在")
check(nt["type"] in (1, 2, 3, 4), f"note.type ∈ {{1,2,3,4}}（实际 {nt['type']}）")
check(isinstance(nt["above"], int) and nt["above"] in (0, 1), "note.above ∈ {0,1}")
check(len(nt["startTime"]) == 3 and len(nt["endTime"]) == 3, "note.startTime/endTime 是 [i,n,d]")
check(isinstance(nt["positionX"], (int, float)), "note.positionX 数值")
check(nt["startTime"][1] < nt["startTime"][2] and nt["startTime"][1] >= 0, "note.startTime 分子 < 分母且 ≥0")

# Hold：endTime >= startTime
hold = [n for n in jl["notes"] if n["type"] == 2][0]
s = triple_value(hold["startTime"])
e = triple_value(hold["endTime"])
check(e >= s, f"Hold endTime({e}拍) >= startTime({s}拍)")

# 反面音符 / 假音符都落到 JSON 里
check(any(n["above"] == 0 for n in jl["notes"]), "反面音符 above=0 写进 chart")
check(any(n["isFake"] == 1 for n in jl2["notes"]), "假音符 isFake=1 写进 chart")

print()
print("== 4. 时间/坐标**不吸附**（往返无损）==")
t = beat_triple(500, 120)
check(triple_value(t) == 1.0, f"500ms@120BPM → {t} = 1.0 拍（实际 {triple_value(t)}）")
t = beat_triple(1000, 120)
check(triple_value(t) == 2.0, f"1000ms@120BPM → {t} = 2.0 拍")
t = beat_triple(1500, 120)
check(triple_value(t) == 3.0, f"1500ms@120BPM → {t} = 3.0 拍")

# 非格点时间：1234ms@120BPM = 2.468 拍，必须精确还原（不能变成 2.5 拍）
t = beat_triple(1234, 120)
check(abs(triple_value(t) - 2.468) < 1e-12, f"1234ms@120BPM → {t} = 2.468 拍（不吸到 2.5）")
check(t == (2, 117, 250), f"精确约分结果 = [2, 117, 250]（实际 {t}）")
back_ms = triple_value(t) * 60_000.0 / 120.0
check(abs(back_ms - 1234.0) < 1e-6, f"往返毫秒 {back_ms} ≈ 1234（差 {abs(back_ms - 1234)}）")

# 三连音类时间也不能被吸到 1/4 拍
t = beat_triple(166, 120)
check(abs(triple_value(t) - 0.332) < 1e-3 and t[2] != 4, f"166ms → {t}（分母不是 4，说明没按 1/4 拍吸附）")

# 带小数 BPM 也要精确
t = beat_triple(1000, 128.5)
check(abs(triple_value(t) - 1285.0 / 600.0) < 1e-12, f"1000ms@128.5BPM → {t} = {triple_value(t)}")

# 位置：非格点 posX 原样写出
check(quantize_posx(0.1234) == 83.295, f"posX 0.1234 → {quantize_posx(0.1234)}（应 83.295，不吸到格子）")
check(quantize_posx(0.0) == 0.0, f"posX 0.0（中心）→ {quantize_posx(0.0)}（应 0）")
check(quantize_posx(-1.0) == -675.0, f"posX -1.0（最左）→ {quantize_posx(-1.0)}（应 -675）")
check(quantize_posx(1.0) == 675.0, f"posX 1.0（最右）→ {quantize_posx(1.0)}（应 675）")
check(quantize_posx(0.5) == 337.5, f"posX 0.5（右半中）→ {quantize_posx(0.5)}（应 337.5）")

print()
print("== 5. info.txt 格式（fs.rs info_from_txt）==")
info_txt = '\n'.join([
    '#',
    'Name: Test Song',
    'Path: 1',
    'Song: music.mp3',
    'Picture: bg.png',
    'Chart: chart.json',
    'Level: 12',
    'Composer: Tester',
    'Charter: You',
    'Illustration: ',
    ''
])
it_lines = [l for l in info_txt.splitlines() if l.strip()]
check(it_lines[0] == '#', "info.txt 第一行是 '#'（info_from_txt 强制要求）")
kvs = {}
for l in it_lines[1:]:
    if ': ' in l:
        k, v = l.split(': ', 1)
        kvs[k] = v
check("Name" in kvs and "Chart" in kvs and "Song" in kvs and "Picture" in kvs, "info.txt 含 Name/Chart/Song/Picture")
check("Path" in kvs and kvs["Path"].strip() != "", "info.txt Path 非空（Phira 用于 id）")

print()
print("== 6. PEZ 打包（zip 结构）==")
buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('chart.json', chart_text)
    z.writestr('info.txt', info_txt)
    z.writestr('music.mp3', b'\x00' * 100)
    z.writestr('bg.png', b'\x00' * 100)
with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as z:
    names = z.namelist()
    check(len(names) == 4, f"zip 内文件数 = 4（实际 {len(names)}）")
    check(all('/' not in n for n in names), "所有文件在 zip 根目录（无子目录）")
    for need in ['chart.json', 'info.txt', 'music.mp3', 'bg.png']:
        check(need in names, f"zip 含 {need}")

print()
if FAIL:
    print(f"共 {len(FAIL)} 项失败：")
    for f in FAIL:
        print(" -", f)
    sys.exit(1)
else:
    print("全部校验通过 ✓")
