# FRPHI — Android 平台 Phira 谱面制作工具

FRPHI 是运行于 Android 平台的 Phira / RPE 谱面制谱器。应用将"打歌"与"制谱"合为一体：导入音频与背景图、填写曲目信息后，用户随音乐在屏幕上以触控手势录入音符，系统自动将手势转换为 Tap / Hold / Flick / Drag 四类音符，最终一键导出 **.pez** 谱面包，可直接由 **Phira** 读取游玩，或在 **Re:PhiEdit** 中继续编辑。

全套流程在设备本地完成：无需连接电脑、无需注册账号，除「AI 辅助」外全部功能离线可用；音频与谱面素材不离开设备。

---

## 目录

- [支持的谱面格式](#支持的谱面格式)
- [Phigros 官谱导入](#phigros-官谱导入)
- [PEC 文本谱导入](#pec-文本谱导入)
- [噪域支持](#噪域支持)
- [核心编辑能力](#核心编辑能力)
- [AI 辅助](#ai-辅助)
- [性能特性](#性能特性)
- [导出格式说明](#导出格式说明)
- [构建](#构建)
- [签名](#签名)
- [验证](#验证)
- [许可与致谢](#许可与致谢)

---

## 支持的谱面格式

| 格式 | 形态 | 说明 |
|---|---|---|
| **RPE**（`.pez`） | zip 压缩包 | Phira 标准谱面包：`chart.json` + 音频 + 出处图 |
| **Phigros 官谱**（Official JSON） | 单个 `.json`，或带音频的 zip | `formatVersion` 1 / 3，官谱与自制谱均适用 |
| **PEC 文本谱** | `.json` / `.pec` / `.txt` | 按内容嗅探，无需依赖扩展名 |
| `chart.json` 裸文件 | 单个 `.json` | 非压缩包亦可直接导入 |

格式判定与 Phira 的 `infer_chart_format` 一致：**包含 `META` 的按 RPE 处理，不含 `META` 但含 `judgeLineList` 的按官谱处理**。选择文件时无需手动指定格式，应用自动识别。

## Phigros 官谱导入

官谱与 RPE 在结构上存在根本差异，FRPHI 的解析逻辑与 Phira `prpr/src/parse/pgr.rs` 及 Phigros 自制谱规范对齐：

- **判定线自带 BPM**：官谱无全局 BPMList，时间单位为 `60 / 32 / bpm` 秒（三十二分音符）；各线可按自身 BPM 将音符时间换算为绝对毫秒，同谱多线可携带不同 BPM。
- **单轨道结构**：每条判定线仅含 move / rotate / disappear / speed 四类事件，无 eventLayers、无缓动（均为线性）、无 ctrl 对象、无父线，导入后呈现为纯色普通判定线。
- **单位差异**：`offset` 单位为秒（RPE 为毫秒）；`positionX` 归一化系数为 `2 × 9 / 160 = 0.1125`（与 Phira `parse_notes` 一致，并非 RPE 的 `/675`，两者量纲相差 76 倍，误用会导致音符整体集中于判定线中央）；`formatVersion 1` 的移动事件将整数同时用作 x / y（`x*1000 + y`），解析时已拆分。
- **判定线换算**：`start2` / `end2` 为绝对位置、向上为正，导入时写入相对 `posPct` 的残量（t=0 恒为 0）；官谱旋转约定为逆时针正，直接沿用；流速统一乘 **4.5** 完成官谱单位到 RPE 单位的换算。
- **动画语义**：官谱事件间线性插值、段边界跳变显式保留（边界前补 1ms 帧），首尾相接且值连续的段合并为一个关键帧点，避免产生数十万级冗余关键帧。
- **空轨道默认值**：未写入 `judgeLineDisappearEvents` 时判定线完全不透明；未写入 `speedEvents` 时流速取官谱单位 1。音符级 `speed` 支持 `Infinity` 瞬移效果，导入时夹取至 50 倍上限。

**键型映射**（官谱 type 编号与 RPE 不同）：

| 官谱 `type` | 含义 | FRPHI |
|---|---|---|
| 1 | Tap | 1 蓝 |
| 2 | Drag | 4 黄 |
| 3 | Hold | 2 长条 |
| 4 | Flick | 3 红 |

所有转换规则均有 `PgrImporterTest` 用例钉死，覆盖键型映射、坐标系数、单位换算、关键帧生成与回归表现。

## PEC 文本谱导入

PEC 谱常以 `.json` / `.pec` / `.txt` 形态分发，内容为 `bp` / `n1`–`n4` / `cp` / `cm` 等文本行。FRPHI 按内容嗅探识别（首行为偏移毫秒或出现 `bp`），不再因扩展名误报。解析语义与 Phira `prpr/src/parse/pec.rs` 对齐：

- 首行偏移：`offset = 值 / 1000 - 0.15`（秒）；
- `bp` 定义 BPM 分段；`n1`–`n4` 对应 Tap / Hold / Flick / Drag，`x / 1024` 归一化，`above` 与 `fake` 保留，`#` 与 `&` 后缀的属性一并读取；
- `cp/cd/ca/cv` 为位置 / 旋转 / 透明度 / 流速瞬时值，`cm/cr/cf` 为过渡，旋转取反、流速经 `5.85` 与 `4.5` 两级换算；
- tween 类型统一近似为线性（音符时间与坐标准确，缓动曲线形状略有差异，导入时给出提示）；
- 素材按 `info.txt` 的 `Song` / `Picture` 抽取。

> 谱面内容仅做解析，不写入 FRPHI 源码或资源目录。

## 噪域支持

支持 RPE 1.5.0+ 的 `blockAreaList` 噪域：**数据可编辑、玩法完整**——可读取、显示、编辑、导出，Autoplay 中按 Phigros 规则对真实手指断触。

- **编辑入口**：右侧窗口「噪域」页签，长按行或画布上的噪域框进入编辑；长按页脚「＋ 新建噪域」后直接在屏幕上拖出矩形；底部四角按钮与右上角坐标输入框配合调整。
- **关键帧**：编辑结果为矩形关键帧（毫秒 → 左/下/右/上），帧间线性插值；导出时换算为官方 `moveXEvents / moveYEvents / scaleXEvents / scaleYEvents`；显示 / 深浅等偏好存入私有字段 `_frphi`，其他播放器忽略、FRPHI 读回时以其为准。
- **数据保留**：导出时原样写回整块原始 JSON，未知字段不丢失；存在噪域时 `META.RPEVersion` 自动升为 `220`。
- **Autoplay 表现**：激活期间渲染深红底 + 跳动数据块 + 红色辉光；手指落入激活中的噪域即失效直至抬起（划出区域不恢复），长条即时断连；断触伴随合成干扰音与音乐短暂压低。合乎"垂直（时间）判定"规则，Autoplay 的自动判定不被噪域遮挡影响。
- **撤销覆盖**：拖角、打/删关键帧、显示切换、新建/复制/删除、换层排序全部进入撤销栈。

数据结构参考 Re:PhiEdit Next（`src/core/noise-areas.mjs` 等，PolyForm Noncommercial 1.0.0），以 Kotlin 独立重写读取与绘制；署名与许可证见应用内「版权声明」面板。

`NoiseRuntime.kt` 为纯 JVM 实现，`NoiseRuntimeTest` 以 11 个用例覆盖断触状态机、映射换算与预警强度。

### 官谱噪域

官谱 4.0.0 起亦携带 `blockAreaList`，但与 RPE 结构完全不同（区域为 `bottomLeftPercentage` / `topRightPercentage` 屏幕百分比、时间为秒、无 `activeIntervals`、事件轨道不同、`isSubtract` 对应 `isInvert`），FRPHI 按有无 `bottomLeftPercentage` 字段自动分流解析器。换算规则：百分比 x 线性映射至 ±675、y 取反映射至 ±450。

### 大噪域性能（实测）

以 `ハテ` AT（66 线 / 1397 音符 / **14355 块**）为例：

| 环节 | 结果 |
|---|---|
| 噪域解析（含事件轨道） | 134 ms |
| `NoiseTimeIndex` 构建（14k 块排序） | 9 ms |
| 编辑页每帧查询 | **4.06 µs**（原全量遍历全部块） |
| 同一时刻实际可见块数 | 平均 105，最多 1400 |
| 打歌命中检测 | **8 µs**（原全量扫描约 105 µs） |

实现手段：`NoiseTimeIndex`（按 `appearMs` 排序 + 消失时间前缀最大值二分）、编辑页单帧绘制上限 `MAX_NOISE_DRAWN = 240` 块、`DashPathEffect` 缓存复用、草稿编码缓存。

## 核心编辑能力

- **四阶段录入**：第一次 Tap（蓝）/ 第二次 Drag（黄）/ 第三次 Flick（红）/ 第四次 Hold（长条，拖放或长按）。多指同时录入，同一次按下在同一时刻仅生成一个音符。
- **多步撤销 / 重做**：两栈模型，每步记录增量（音符增删改、判定线姿态与关键帧、噪域快照、剪贴板、BPM/偏移/线条元数据），最多 200 步；长按「撤销」执行重做；有新版改动或整谱替换时自动清空重做栈。
- **音符属性面板**：覆盖类型、正反面、真假、起止时间、X 坐标、速度、大小、透明度、纵向偏移、可见时间、判定范围、音符颜色、特效颜色、打击音效；与 Phiedit `NoteEditPanel` 一一对应，支持多选批量「设为 / 增减」。
- **判定线编辑**：最多 32 条判定线；移动 / 旋转 / 流速 / 透明度 / 逐关键帧缩放，关键帧左侧时间轴点选，支持 ctrl 对象（yControl / posControl / sizeControl / alphaControl）、incline 倾斜、father 父线等 RPE 扩展语义。
- **图片线 / 文字线**：素材判定线（Texture / textEvents），支持拖动、旋转、关键帧、父线、框选、撤销与复制；命名遵循 Phiedit / Phira 约定，本地路径不落盘。
- **双押贴图**：`*_mh` 贴图比例与 Phira `note.rs` 对齐（宽比 ≈1.1503），保证双押音符与普通音符视觉同大；打歌界面始终使用 mh 贴图。
- **Autoplay 预览**：音符到线即判定，附带打击特效与音效，展示分数与 Combo；判定窗口与 Phira `judge.rs` 对齐（PERFECT ≤80ms 淡黄特效、GOOD ≤220ms 淡蓝特效，黄/红键恒为 PERFECT）。
- **局域网联机**：同一局域网内建立房间，多方共同编辑同一份谱面，改动实时同步；联机期间撤销停用以保证两端一致。配套 Minecraft 模组 FRPHIMC（Fabric 1.20.4）可实现手机端与游戏端互通。
- **视频提取音轨**：选取视频作为音频源时自动探测并提取音轨，后台解码 + 进度条弹窗，支持取消。
- **日期校准**：跟随节拍点击测出设备输出延迟，中位数抗离群，支持负值。

## AI 辅助

编辑界面的「AI」抽屉与主界面共用同一接口与聊天记录：

- **一句话写谱 / 改谱**：以描述性语言生成或修改谱面（如"写一份 12 级谱，蓝键多点"），回复按 JSON Lines 实时流式套用，可「撤回」回退上一版（保留 5 步）；`ChartStyle` 会真正解析"蓝键多点 / 更密"等口语化要求并强制按目标占比调整。
- **指令协议**：支持项目信息（meta）、单个音符（fix）、判定线关键帧增改删（kf / kfdel）、浏览器与搜索（search / fetch）、BPM 检测（bpm）；编辑页每轮对话均附带当前谱面摘要与协议，模型可随时知悉谱面状态。
- **本地 BPM 检测**：不依赖网络与第三方库，仅使用系统 MediaCodec 解码；流程为 PCM → 能量包络 → 60~200 BPM 候选梳状滤波打分 → 先验与倍频校正；提供「一键应用 BPM / 连偏移一起应用」。
- **人设自定义**：接口列表中长按某项可编辑自定义系统提示词，按接口保存。

未配置 AI 接口时，BPM 等关键词走本地兜底检索，其余功能不受影响。

## 性能特性

针对大谱面（万级音符 / 万级噪域块）的实测优化：

| 场景 | 优化前 | 优化后 |
|---|---|---|
| 双押检测（10000 音符/帧） | 468.8 ms | 0.459 ms（×1021，时间排序 + 滑窗） |
| 判定线姿态插值（6000 次/帧） | 3.06 ms | 0.42 ms（关键帧排序缓存 + 二分） |
| 草稿编码（1 万音符） | 25.5 ms | 5.0 ms（DraftCodec 逐行文本） |
| Autoplay / 调试热路径 | 全量 `notes` 扫描 | 排序索引 + 二分查找（O(log n)） |

帧循环内各热路径均为零分配或极小分配（复用 RectF、缓存 Filter 与 PathEffect）。音频解码使用系统 API，不引入第三方库；APK 零第三方依赖。

## 导出格式说明

`chart.json`（RPEJSON：META + BPMList + 判定线列表）+ `info.txt`（Name / Song / Picture / Chart / Level / Composer / Charter）+ 音频与背景图原格式打包。音符类型 `1=Tap 2=Hold 3=Flick 4=Drag`，坐标 `positionX ∈ [-675, 675]`，时间按 BPM 换算并量化到 `1/横线数` 拍；支持多 BPM 分段导出与延迟校正。格式已与 Phira `prpr/src/parse/rpe.rs` 交叉验证。

## 构建

本机离线构建（JDK 17 + Gradle 8.13 + AGP 8.13.2 + SDK platform 35 已缓存）：

```bash
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"
./gradlew --offline :app:assembleRelease --console=plain --no-daemon
```

产物：`app/build/outputs/apk/release/app-release.apk`。

## 签名

APK 使用项目签名密钥（`piez-key.jks` + `keystore.properties`，均已被 `.gitignore` 排除，不入仓库）。文件缺失时 `assembleRelease` 仍可出包但产物未签名。更换密钥签名的 APK 无法覆盖安装旧版本。

## 验证

- `docs/verify_pez.py`：按 Phira `rpe.rs` 的 serde 规则逐字段校验 chart.json / info.txt / zip 结构；
- `app/src/test/`：涵盖导入器、草稿编解码、撤销、判定线换算、噪域运行时、AI 协议与校准算法等全部核心逻辑；
- `demo_Test_Beat.pez`：示例谱面（120BPM 合成测试音 + 渐变背景），可直接投入 Phira 验证。

## 许可与致谢

- FRPHI 原创代码以 **GNU GPL-3.0** 开源。应用中部分借鉴 Phira / Re:PhiEdit / 噪域等项目的算法思路，均为 Kotlin 独立实现，不构成对上游项目的衍生作品；各上游项目自身的许可证见应用内「版权声明 · Credits」面板（长按左侧「日志」打开）。
- BPM 检测算法参考 SoundTouch `BPMDetect`（LGPL-2.1）、aubio `tempo`（GPL-3.0）与 librosa `beat.tempo`（ISC）的实现思路，以 Kotlin 独立重写，不引入或分发其 C/C++ 源码。
- 谱面中使用的音乐、曲绘等素材版权归各自权利人所有；本工具仅可用于制作合法合规的谱面。