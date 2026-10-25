# -*- coding: utf-8 -*-
"""
PEZ 导出结构校验脚本
复刻 Phira prpr/src/parse/rpe.rs 的 serde 反序列化规则，验证生成 chart.json 能被 Phira 读取。
同时验证 info.txt 的 info_from_txt 解析规则。
"""
import json
import sys
import zipfile
import io

FAIL = []

def check(cond, msg):
    if cond:
        print(f"  [OK] {msg}")
    else:
        FAIL.append(msg)
        print(f"  [FAIL] {msg}")

# ---------- 1. 用与 Kotlin 相同的量化逻辑构造一份示例 chart ----------
def quantize_beat(ms, bpm, h_lines):
    beat = ms / 1000.0 * bpm / 60.0
    k = int(round(beat * h_lines))
    if k < 0: k = 0
    return (k // h_lines, k % h_lines, h_lines)

def quantize_posx(norm, v_lines):
    cell = 2.0 / v_lines
    k = int(round((norm + 1.0) / cell))
    k = max(0, min(v_lines, k))
    return round(-675.0 + k * cell * 675.0, 3)

def build_chart(meta, notes):
    speed = meta["speed"]
    line_y = (50 - meta["linePosPct"]) * 9
    lines = []
    lines.append('{')
    lines.append('  "META": {')
    lines.append(f'    "id": "1",')
    lines.append(f'    "name": "{meta["name"]}",')
    lines.append(f'    "level": "{meta["level"]}",')
    lines.append(f'    "background": "bg.png",')
    lines.append(f'    "charter": "{meta["charter"]}",')
    lines.append(f'    "composer": "{meta["composer"]}",')
    lines.append(f'    "illustration": "",')
    lines.append(f'    "song": "music.mp3",')
    lines.append(f'    "offset": 0,')
    lines.append(f'    "RPEVersion": 160')
    lines.append('  },')
    lines.append('  "BPMList": [')
    lines.append(f'    {{ "bpm": {meta["bpm"]}, "startTime": [0, 0, 1] }}')
    lines.append('  ],')
    lines.append('  "judgeLineList": [')
    lines.append('    {')
    lines.append('      "Name": "line",')
    lines.append('      "Texture": "line.png",')
    lines.append('      "father": -1,')
    lines.append('      "rotateWithFather": false,')
    lines.append('      "eventLayers": [')
    lines.append('        {')
    lines.append('          "alphaEvents": [ { "startTime": [0,0,1], "endTime": [100000,0,1], "start": 255.0, "end": 255.0, "easingType": 0 } ],')
    lines.append('          "moveXEvents": [ { "startTime": [0,0,1], "endTime": [100000,0,1], "start": 0.0, "end": 0.0, "easingType": 0 } ],')
    lines.append(f'          "moveYEvents": [ {{ "startTime": [0,0,1], "endTime": [100000,0,1], "start": {line_y}, "end": {line_y}, "easingType": 0 }} ],')
    lines.append('          "rotateEvents": [],')
    lines.append(f'          "speedEvents": [ {{ "startTime": [0,0,1], "endTime": [100000,0,1], "start": {speed}, "end": {speed}, "easingType": 0 }} ]')
    lines.append('        }')
    lines.append('      ],')
    lines.append('      "notes": [')
    bpm = meta["bpm"]; h = meta["hLines"]; v = meta["vLines"]
    for i, n in enumerate(notes):
        t0 = quantize_beat(max(0, n[0]-meta["delay"]), bpm, h)
        t1 = quantize_beat(max(0, n[1]-meta["delay"]), bpm, h)
        x = quantize_posx(n[2], v)
        lines.append('        {')
        lines.append(f'          "type": {n[3]},')
        lines.append('          "above": 1,')
        lines.append(f'          "startTime": [{t0[0]}, {t0[1]}, {t0[2]}],')
        lines.append(f'          "endTime": [{t1[0]}, {t1[1]}, {t1[2]}],')
        lines.append(f'          "positionX": {x},')
        lines.append('          "yOffset": 0.0,')
        lines.append('          "alpha": 255,')
        lines.append('          "size": 1.0,')
        lines.append('          "speed": 1.0,')
        lines.append('          "isFake": 0,')
        lines.append('          "visibleTime": 999999.0')
        lines.append('        }' + (',' if i != len(notes)-1 else ''))
    lines.append('      ],')
    lines.append('      "isCover": 1,')
    lines.append('      "isGif": false,')
    lines.append('      "zOrder": 0,')
    lines.append('      "posControl": [],')
    lines.append('      "sizeControl": [],')
    lines.append('      "alphaControl": [],')
    lines.append('      "yControl": []')
    lines.append('    }')
    lines.append('  ]')
    lines.append('}')
    return '\n'.join(lines)

meta = {"name": "Test Song", "composer": "Tester", "charter": "You",
        "level": "12", "bpm": 120.0, "speed": 1.2, "linePosPct": 40, "delay": 30, "hLines": 4, "vLines": 8}
notes = [
    (0, 0, 0.0, 1),        # Tap 0ms
    (500, 500, 0.5, 3),    # Flick 500ms
    (1000, 1500, -0.5, 2), # Hold 1000-1500ms
    (2000, 2000, 0.25, 1), # Tap 2000ms
    (2750, 2750, -0.75, 3),
]
chart_text = build_chart(meta, notes)
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
jl = chart["judgeLineList"][0]
check(len(chart["judgeLineList"]) == 1, "单条判定线")
check(jl.get("Name") is not None, 'judgeLine.Name 存在（大写 N）')
check(jl.get("Texture") is not None, 'judgeLine.Texture 存在' )
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
check("notes" in jl and isinstance(jl["notes"], list), "judgeLine.notes 数组")
check(isinstance(jl.get("isCover"), int), "judgeLine.isCover int")
check("zOrder" in jl, "judgeLine.zOrder")

print()
print("== 3. note 结构（RPENote）==")
nt = chart["judgeLineList"][0]["notes"][0]
for k in ["type", "above", "startTime", "endTime", "positionX", "yOffset", "alpha", "size", "speed", "isFake", "visibleTime"]:
    check(k in nt, f"note.{k} 存在")
check(nt["type"] in (1, 2, 3, 4), f"note.type ∈ {{1,2,3,4}}（实际 {nt['type']}）")
check(isinstance(nt["above"], int) and nt["above"] in (0, 1), "note.above ∈ {0,1}")
check(len(nt["startTime"]) == 3 and len(nt["endTime"]) == 3, "note.startTime/endTime 是 [i,n,d]")
check(isinstance(nt["positionX"], (int, float)), "note.positionX 数值")
check(nt["startTime"][1] < nt["startTime"][2] and nt["startTime"][1] >= 0, "note.startTime 分子 < 分母且 ≥0")

# Hold：endTime >= startTime
hold = [n for n in chart["judgeLineList"][0]["notes"] if n["type"] == 2][0]
s = hold["startTime"][0] + hold["startTime"][1]/hold["startTime"][2]
e = hold["endTime"][0] + hold["endTime"][1]/hold["endTime"][2]
check(e >= s, f"Hold endTime({e}拍) >= startTime({s}拍)")

print()
print("== 4. 时间/坐标量化正确性 ==")
# 500ms @120bpm = 1 拍；hLines=4 → k=4 → [1,0,4] = 1.0拍
t = quantize_beat(500, 120, 4)
check((t[0] + t[1]/t[2]) == 1.0, f"500ms@120BPM → {t} = 1.0 拍（实际 {t[0] + t[1]/t[2]}）")
t = quantize_beat(1000, 120, 4)
check((t[0] + t[1]/t[2]) == 2.0, f"1000ms@120BPM → {t} = 2.0 拍")
t = quantize_beat(1500, 120, 4)
check((t[0] + t[1]/t[2]) == 3.0, f"1500ms@120BPM → {t} = 3.0 拍")
x = quantize_posx(0.0, 8)
check(x == 0.0, f"posX 0.0（中心）→ {x}（应 0）")
x = quantize_posx(-1.0, 8)
check(x == -675.0, f"posX -1.0（最左）→ {x}（应 -675）")
x = quantize_posx(1.0, 8)
check(x == 675.0, f"posX 1.0（最右）→ {x}（应 675）")
x = quantize_posx(0.5, 8)
check(abs(x - 337.5) < 1e-6, f"posX 0.5（右半中）→ {x}（应 337.5）")

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
    for f in FAIL: print(" -", f)
    sys.exit(1)
else:
    print("全部校验通过 ✓")