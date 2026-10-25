# PEZ 谱面包格式规范（由 Phira + rpe-next 源码交叉验证）

> 来源：`src_ref/phira/prpr/src/parse/rpe.rs`（解析端，权威）
> `src_ref/phira/prpr/src/fs.rs`（info 文件读取，权威）
> `src_ref/rpe-next/src/platform/files.mjs`（打包端）
> `src_ref/rpe-next/src/platform/archive.mjs`（ZIP 写入）

## 1. PEZ 本质

PEZ = ZIP 压缩包（普通 zip，非 zip64，无分卷，无加密）。
文件全部放在**根目录**（不嵌套子文件夹）。压缩方式 0(store) 或 8(deflate) 均可，Phira 两者都支持。

内部文件：
```
info.txt      # 谱面信息（RPE 标准）
chart.json    # 谱面数据（RPEJSON，文件名可自定义，info.txt 的 Chart 字段指定）
music.mp3     # 音乐（Song 字段指定，支持 mp3/ogg/wav/flac/aac）
bg.png        # 封面（Picture 字段指定，支持 png/jpg/jpeg/bmp/gif/webp/avif）
extra.json    # 可选，着色器事件（本项目不需要）
```
找不到 info 时 Phira 会**按扩展名自动推断** chart(json/pec)、music(mp3/ogg/...)、bg(png/...)。

## 2. info.txt 格式

第一行必须是 `#`，之后 `Key: Value`，每行一对，冒号后有一个空格：

```
#
Name: 曲名
Path: 数值id或字符串(1~80字符，不能有 / \ : * ? " < > |)
Song: music.mp3
Picture: bg.png
Chart: chart.json
Level: 难度(如 15)
Composer: 曲师
Charter: 谱师
Illustration: 画师
```

字段映射（`info_from_txt`）：Name/Music(Song)/Chart/Image(Picture)/Level/Illustrator(曲绘)
/Artist(Composer|Musician)/Charter(Designer)。未知 key 忽略，不报错。

## 3. RPEJSON（chart.json）结构

```json
{
  "META": {
    "id": "chart-id",
    "name": "曲名",
    "level": "15",
    "background": "bg.png",
    "charter": "谱师",
    "composer": "曲师",
    "illustration": "",
    "song": "music.mp3",
    "offset": 0,
    "RPEVersion": 160
  },
  "BPMList": [ { "bpm": 120.0, "startTime": [0, 0, 1] } ],
  "judgeLineList": [
    {
      "Name": "line",
      "Texture": "line.png",
      "father": -1,
      "rotateWithFather": false,
      "eventLayers": [ { "speedEvents": [{ "startTime":[0,0,1], "endTime":[2147483647,0,1], "start":1.0, "end":1.0, "easingType":0 }] } ],
      "extended": {"textEvents": [], "scaleXEvents": [], "scaleYEvents": [], "colorEvents": [], "paintEvents": [], "gifEvents": [], "inclineEvents": []},
      "notes": [],
      "isCover": 1,
      "isGif": false,
      "zOrder": 0,
      "posControl": [],
      "sizeControl": [],
      "alphaControl": [],
      "yControl": []
    }
  ]
}
```

要点（对照 rpe.rs 反序列化器）：
- 顶层字段：`META`(对象)、`BPMList`(数组)、`judgeLineList`(数组)。
- `META.offset`：**毫秒**整数，Phira 解析为 `offset as f32 / 1000.0` 秒。正数=音乐在谱面后播放。本项目固定 0。
- `META.RPEVersion`：int，缺省 160。>=170 启用新速度积分算法，别填 170+，用 160。
- `BPMList[i]`：`{ bpm: f64, startTime: Triple }`。Triple = `[i,n,d]` 表示 `i + n/d` 拍。起始 BPM 的 startTime 固定 `[0,0,1]`。
- judgeLine 的 `Name` 是**大写 N**（serde rename），`Texture` 大写开头。`father` 是**小写**（父线索引，-1=无）。
- `eventLayers` 数组元素可为 null；每层结构 `{alphaEvents, moveXEvents, moveYEvents, rotateEvents, speedEvents}`（camelCase）。
- `notes` 未提供则默认为空（`parse_notes` 对 None 用 empty）。

## 4. Note（音符）对象

```json
{
  "type": 1,
  "above": 1,
  "startTime": [i, n, d],
  "endTime": [i, n, d],
  "positionX": 0.0,
  "yOffset": 0.0,
  "alpha": 255,
  "size": 1.0,
  "speed": 1.0,
  "isFake": 0,
  "visibleTime": 1000000.0,
  "hitsound": null
}
```

- `type`：`1=Tap(Click) 2=Hold 3=Flick 4=Drag`（rpe.rs `parse_notes` 的 match：1→Click, 2→Hold{end_time}, 3→Flick, 4→Drag，其余报错）。
- `above`：u8，`note.above == 1` 为正面（上面掉落）；0 为反面。默认 1。
- `startTime`/`endTime`：Triple，Hold 用 endTime-startTime 表示按住时长；其余两者一致。
- `positionX`：**RPE 坐标**，范围约 [-675, 675]（屏幕半宽 1350/2=675），解析时 `position_x / 675` 归一化。判定线默认即水平中间 0 轴，音符 positionX 决定在判定线上的左右位置。
- `yOffset`：相对判定线的 y 偏移（像素 RPE 坐标，乘以 2/900 归一化）。默认 0。
- `alpha`：u16，0~255；>=255 直接显示，否则按 visibleTime 之前淡入。
- `size`：音符大小倍率，1.0 默认。`speed`：流速倍率，1.0 默认。
- `isFake`：0/1 假音符。`visibleTime`：可见提前时间（秒），满足 `visibleTime >= time` 则 alpha 直接生效，用很大值。

## 5. 时间换算（BpmList）

- Triple `[i,n,d]` = `i + n/d`（拍）。
- 单 BPM 下：`seconds = beats * 60 / bpm`（`time_beats`: `time + (beats - start_beats) * 60/bpm`）。
- 多 BPM：BPMList 按 startTime 拍排序，分段累加时间（`BpmList::new` 在 parse_rpe 时 `(start_time.beats(), bpm)` 收集）。
- 录制时：拿到音乐播放的秒数 t → `beat = t * bpm / 60` → 量化到 `1/横线数` 格 → 转 Triple。
- Triple 转换：`beat = k / H`（k 为整数格数）：`i = floor(beat)`，`n = k % H`，`d = H`。注意 n<d 恒成立（H 是横线格数，k 已整除）。

## 6. 单条判定线布局（本项目的谱面)

- `judgeLineList` 只含 1 条线。
- 线 `Texture: "line.png"` → Phira 视为 Normal 线（内置贴图，无需资源文件）。
- 所有 note `above: 1`，上下同屏（单线单面）。
- 竖线数 V：positionX 量化格。V 格 → positionX ∈ { -675 + 675*2/V * k }, k=0..V，居中为 0？合理取法：positionX = round(x_raw * V) / V 映射到 [-675,675]，即 positionX = -675 + (1350 * k / V)，k=0..V。这样最左 -675、最右 675，中心 0。