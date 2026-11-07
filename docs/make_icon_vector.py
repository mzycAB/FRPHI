"""生成 FRPHI 高清启动图标（矢量自适应图标 + 各密度 legacy PNG）。

为什么重做：之前只有 legacy PNG（最大 192px），Android 8+ 的启动器会拿这张图
自己加遮罩、放大（应用信息页/最近任务/12+ 启动画面能放到 288dp≈1000px），
于是"糊"。现在主体用 VectorDrawable 画（数学路径，任何尺寸都锐利）：

  drawable/ic_launcher_background.xml   108dp 矢量渐变底
  drawable/ic_launcher_foreground.xml   108dp 矢量字标（Arial Bold 轮廓 → pathData）
  drawable/ic_launcher_monochrome.xml   Android 13+ 主题图标用的单色层
  mipmap-anydpi-v26/ic_launcher.xml     adaptive-icon 声明（含 monochrome）
  mipmap-anydpi-v26/ic_launcher_round.xml
  mipmap-*/ic_launcher[_round].png      API 24~25 的 legacy 位图
                                       （满幅方形、无透明圆角，统一 512×512）

用法: python make_icon_vector.py
依赖: pillow, fonttools
"""
import os
import re

from PIL import Image, ImageDraw, ImageFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res"))

TEXT = "FRPHI"
TOP = (21, 101, 192)      # #1565C0
BOTTOM = (30, 136, 229)   # #1E88E5
TRACKING = -0.01          # 字距（em），负值收紧一点，更像 wordmark

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\arialbd.ttf",
    r"C:\Windows\Fonts\segoeuib.ttf",
    r"C:\Windows\Fonts\seguisb.ttf",
]

LEGACY = {"mdpi": 512, "hdpi": 512, "xhdpi": 512, "xxhdpi": 512, "xxxhdpi": 512}
SS = 8                    # 位图超采样倍数
LEGACY_TEXT_RATIO = 0.86  # legacy 图标（无遮罩）字标占边长（与旧版一致）
ADAPTIVE_TEXT_DP = 60.0   # 自适应图标字标宽度（108dp 画布内，四角落在 66dp 安全圆内并留 2dp 余量）


# --------------------------------------------------------------------- 字体轮廓

def find_font():
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            return p
    raise SystemExit("找不到可用的粗体字体（arialbd.ttf / segoeuib.ttf）")


def glyph_runs(font_path):
    """返回 (glyphset, [(glyphName, offsetX)], bounds, upem)，坐标仍为字体单位（y 向上）"""
    font = TTFont(font_path, fontNumber=0)
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upem = font["head"].unitsPerEm

    runs, x = [], 0.0
    for ch in TEXT:
        gname = cmap[ord(ch)]
        runs.append((gname, x))
        x += gs[gname].width + TRACKING * upem

    box = None
    for gname, ox in runs:
        bp = BoundsPen(gs)
        gs[gname].draw(TransformPen(bp, (1, 0, 0, 1, ox, 0)))
        if bp.bounds:
            bb = bp.bounds
            box = bb if box is None else (min(box[0], bb[0]), min(box[1], bb[1]),
                                          max(box[2], bb[2]), max(box[3], bb[3]))
    if box is None:
        raise SystemExit("字体里没有字形轮廓")
    return gs, runs, box, upem


def _tidy(cmd, nd=2):
    """把浮点噪声（4184.5599999999995）收成两位小数，顺便缩短 pathData"""
    def fix(m):
        v = round(float(m.group(0)), nd)
        s = ("%." + str(nd) + "f") % v
        s = s.rstrip("0").rstrip(".")
        return s if s not in ("-0", "") else "0"
    return re.sub(r"-?\d+\.\d+", fix, cmd)


def path_data(gs, runs):
    """字形 → VectorDrawable pathData（y 轴翻转到屏幕坐标）"""
    parts = []
    for gname, ox in runs:
        sp = SVGPathPen(gs)
        gs[gname].draw(TransformPen(sp, (1, 0, 0, -1, ox, 0)))
        cmd = sp.getCommands()
        if cmd:
            parts.append(_tidy(cmd))
    return " ".join(parts)


def fmt(v):
    s = "%.4f" % v
    s = s.rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def vector_wordmark(font_path, target_w, viewport=108.0, color="#FFFFFFFF"):
    gs, runs, box, _ = glyph_runs(font_path)
    data = path_data(gs, runs)
    w = box[2] - box[0]
    h = box[3] - box[1]
    s = target_w / w
    cx = (box[0] + box[2]) / 2.0
    cy = (box[1] + box[3]) / 2.0
    tx = viewport / 2.0 - cx * s
    ty = viewport / 2.0 + cy * s          # 翻转过 y，所以是加
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 docs/make_icon_vector.py 生成：字标「{TEXT}」矢量轮廓（{os.path.basename(font_path)}） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="{fmt(s)}"
        android:scaleY="{fmt(s)}"
        android:translateX="{fmt(tx)}"
        android:translateY="{fmt(ty)}">
        <path
            android:fillColor="{color}"
            android:pathData="{data}" />
    </group>
</vector>
'''
    # 安全区自检：字标四角到画布中心的最大距离要落在 66dp 安全圆内（半径 33）
    half_w, half_h = target_w / 2.0, h * s / 2.0
    corner = (half_w ** 2 + half_h ** 2) ** 0.5
    print("  字标 %.1f×%.1fdp，四角距中心 %.1fdp（安全半径 33dp）%s"
          % (target_w, h * s, corner, "" if corner <= 33 else "  ← 超出，需缩小"))
    return xml


def vector_background(viewport=108.0):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 docs/make_icon_vector.py 生成：108dp 满幅渐变底（自适应图标背景层） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="0"
                android:endY="108"
                android:startColor="#FF{TOP[0]:02X}{TOP[1]:02X}{TOP[2]:02X}"
                android:endColor="#FF{BOTTOM[0]:02X}{BOTTOM[1]:02X}{BOTTOM[2]:02X}" />
        </aapt:attr>
    </path>
</vector>
'''


def adaptive_xml(round_icon=False):
    name = "ic_launcher_round" if round_icon else "ic_launcher"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 docs/make_icon_vector.py 生成：Android 8.0+ 自适应图标 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


# --------------------------------------------------------------------- legacy 位图

def legacy_image(size, font_path, round_icon=False):
    """满幅方形 legacy 图标（四角不透明，不留圆形/圆角透明区）。

    Android 8+ 用自适应图标由系统遮罩裁形状；API 24~25 直接显示这张方图。
    round_icon 参数仅为兼容旧调用，方形图对 roundIcon 槽位同样可用。
    """
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    grad = Image.new("RGBA", (1, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        t = y / max(1, S - 1)
        gd.point((0, y), fill=(
            int(TOP[0] + (BOTTOM[0] - TOP[0]) * t),
            int(TOP[1] + (BOTTOM[1] - TOP[1]) * t),
            int(TOP[2] + (BOTTOM[2] - TOP[2]) * t),
            255))
    grad = grad.resize((S, S), Image.BILINEAR)
    img.alpha_composite(grad)          # 满幅铺底：四角也是实心渐变

    # 字标：按宽度自适应字号，垂直按 cap-height 视觉居中
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    target_w = S * LEGACY_TEXT_RATIO
    fs = int(S * 0.6)
    font = ImageFont.truetype(font_path, fs)
    while fs > 8:
        font = ImageFont.truetype(font_path, fs)
        if d.textlength(TEXT, font=font) <= target_w:
            break
        fs -= 1
    bbox = d.textbbox((0, 0), TEXT, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text(((S - tw) / 2 - bbox[0], (S - th) / 2 - bbox[1]), TEXT, font=font,
           fill=(255, 255, 255, 255))
    img.alpha_composite(layer)
    return img.resize((size, size), Image.LANCZOS)


# --------------------------------------------------------------------- 预览图

LABEL_FONTS = [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc",
               r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\arial.ttf"]


def label_font(size):
    for p in LABEL_FONTS:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


def vertical_gradient(S):
    g = Image.new("RGBA", (1, S))
    gd = ImageDraw.Draw(g)
    for y in range(S):
        t = y / max(1, S - 1)
        gd.point((0, y), fill=(int(TOP[0] + (BOTTOM[0] - TOP[0]) * t),
                               int(TOP[1] + (BOTTOM[1] - TOP[1]) * t),
                               int(TOP[2] + (BOTTOM[2] - TOP[2]) * t), 255))
    return g.resize((S, S), Image.BILINEAR)


def adaptive_preview(font_path, px, circle):
    """模拟自适应图标：满幅渐变 + 62/108 字标，裁到可见区 72/108 再套遮罩"""
    S = px * 4
    canvas = vertical_gradient(S)

    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    target = S * (ADAPTIVE_TEXT_DP / 108.0)
    fs = int(S * 0.4)
    font = ImageFont.truetype(font_path, fs)
    while fs > 8:
        font = ImageFont.truetype(font_path, fs)
        if d.textlength(TEXT, font=font) <= target:
            break
        fs -= 1
    bb = d.textbbox((0, 0), TEXT, font=font)
    d.text(((S - (bb[2] - bb[0])) / 2 - bb[0], (S - (bb[3] - bb[1])) / 2 - bb[1]),
           TEXT, font=font, fill=(255, 255, 255, 255))
    canvas.alpha_composite(layer)

    vis = int(S * 72 / 108)
    off = (S - vis) // 2
    canvas = canvas.crop((off, off, off + vis, off + vis))

    mask = Image.new("L", (vis, vis), 0)
    md = ImageDraw.Draw(mask)
    if circle:
        md.ellipse([0, 0, vis - 1, vis - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, vis - 1, vis - 1], radius=int(vis * 0.26), fill=255)
    canvas.putalpha(mask)
    return canvas.resize((px, px), Image.LANCZOS)


def preview(font_path, out_path):
    """对照图：legacy 512 + 两种自适应遮罩 + 真实像素尺寸一排"""
    W, H = 1180, 910
    sheet = Image.new("RGBA", (W, H), (245, 247, 250, 255))
    d = ImageDraw.Draw(sheet)
    small = label_font(19)

    sheet.alpha_composite(legacy_image(512, font_path), (48, 56))
    d.text((48, 584), "legacy 512px（API 24–25 与商店）", font=small, fill=(70, 80, 95, 255))

    sheet.alpha_composite(adaptive_preview(font_path, 240, True), (640, 56))
    d.text((640, 306), "自适应 · 圆形遮罩", font=small, fill=(70, 80, 95, 255))
    sheet.alpha_composite(adaptive_preview(font_path, 240, False), (920, 56))
    d.text((920, 306), "自适应 · 方圆形遮罩", font=small, fill=(70, 80, 95, 255))

    d.text((640, 360), "Android 8+ 使用矢量前景，任意尺寸都锐利", font=small, fill=(120, 130, 145, 255))

    x = 48
    y = 664
    for px in (48, 72, 96, 144, 192):
        sheet.alpha_composite(legacy_image(px, font_path), (x, y))
        d.text((x, y + px + 12), "%dpx" % px, font=small, fill=(70, 80, 95, 255))
        x += px + 44
    d.text((48, 622), "启动器里的实际像素（mdpi → xxxhdpi）", font=small, fill=(70, 80, 95, 255))

    sheet.convert("RGB").save(out_path, "PNG")
    print("wrote", out_path)


# ------------------------------------------------------- 矢量自检（把 pathData 真跑一遍）

def _flatten(path_data, scale, tx, ty, steps=14):
    """把 VectorDrawable pathData 展平成多边形（M/L/H/V/Q/C/Z），并套上 group 变换"""
    toks = re.findall(r"[MLHVQCZ]|-?\d*\.?\d+(?:e-?\d+)?", path_data)
    polys, cur, i = [], [], 0
    px = py = 0.0
    cmd = None

    def tf(x, y):
        return (x * scale + tx, y * scale + ty)

    def quad(p0, p1, p2):
        return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
                 (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
                for t in [k / steps for k in range(1, steps + 1)]]

    def cubic(p0, p1, p2, p3):
        out = []
        for k in range(1, steps + 1):
            t = k / steps
            mt = 1 - t
            out.append((mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
                        mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1]))
        return out

    while i < len(toks):
        t = toks[i]
        if t in "MLHVQCZ":
            cmd = t
            i += 1
            if cmd == "Z":
                if cur:
                    polys.append(cur)
                    cur = []
            continue
        if cmd == "M":
            px, py = float(toks[i]), float(toks[i + 1])
            i += 2
            if cur:
                polys.append(cur)
            cur = [tf(px, py)]
            cmd = "L"
        elif cmd == "L":
            px, py = float(toks[i]), float(toks[i + 1])
            i += 2
            cur.append(tf(px, py))
        elif cmd == "H":
            px = float(toks[i])
            i += 1
            cur.append(tf(px, py))
        elif cmd == "V":
            py = float(toks[i])
            i += 1
            cur.append(tf(px, py))
        elif cmd == "Q":
            cx, cy, ex, ey = (float(toks[i]), float(toks[i + 1]),
                              float(toks[i + 2]), float(toks[i + 3]))
            i += 4
            pts = quad((px, py), (cx, cy), (ex, ey))
            cur.extend(tf(a, b) for a, b in pts)
            px, py = ex, ey
        elif cmd == "C":
            x1, y1, x2, y2, ex, ey = [float(v) for v in toks[i:i + 6]]
            i += 6
            pts = cubic((px, py), (x1, y1), (x2, y2), (ex, ey))
            cur.extend(tf(a, b) for a, b in pts)
            px, py = ex, ey
        else:
            i += 1
    if cur:
        polys.append(cur)
    return polys


def _fill_nonzero(size, polys):
    """按 Android 的 nonzero 填充规则光栅化（正确挖出 R/P 的孔洞）"""
    mask = Image.new("L", (size, size), 0)
    px = mask.load()

    rows = [[] for _ in range(size)]
    for poly in polys:
        n = len(poly)
        if n < 3:
            continue
        for i in range(n):
            x0, y0 = poly[i]
            x1, y1 = poly[(i + 1) % n]
            if y0 == y1:
                continue
            ylo = max(0, int(min(y0, y1)))
            yhi = min(size - 1, int(max(y0, y1)))
            for y in range(ylo, yhi + 1):
                rows[y].append((x0, y0, x1, y1))

    for y in range(size):
        edges = rows[y]
        if not edges:
            continue
        yc = y + 0.5
        xs = []
        for (x0, y0, x1, y1) in edges:
            if (y0 <= yc < y1) or (y1 <= yc < y0):
                xs.append((x0 + (yc - y0) / (y1 - y0) * (x1 - x0), 1 if y1 > y0 else -1))
        if not xs:
            continue
        xs.sort()
        wind = 0
        start = 0.0
        for x, d in xs:
            prev = wind
            wind += d
            if prev == 0 and wind != 0:
                start = x
            elif prev != 0 and wind == 0:
                a = max(0, int(start + 0.5))
                b = min(size, int(x + 0.5))
                for xi in range(a, b):
                    px[xi, y] = 255
    return mask


def vector_check(fg_path, out_path, px=432):
    """按 Android 的方式把生成的矢量真渲染一遍，确认没镜像/没跑偏/孔洞正确"""
    xml = open(fg_path, encoding="utf-8").read()
    scale = float(re.search(r'android:scaleX="([-\d.]+)"', xml).group(1))
    tx = float(re.search(r'android:translateX="([-\d.]+)"', xml).group(1))
    ty = float(re.search(r'android:translateY="([-\d.]+)"', xml).group(1))
    data = re.search(r'android:pathData="([^"]+)"', xml).group(1)

    SSF = 4
    S = px * SSF
    k = S / 108.0
    img = vertical_gradient(S)
    polys = _flatten(data, scale * k, tx * k, ty * k)

    white = Image.new("RGBA", (S, S), (255, 255, 255, 255))
    img.paste(white, (0, 0), _fill_nonzero(S, polys))

    # 安全圆参考线
    d = ImageDraw.Draw(img)
    r = (66 / 108.0) * S / 2
    d.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r], outline=(255, 255, 255, 90), width=2)

    img = img.resize((px, px), Image.LANCZOS).convert("RGB")
    img.save(out_path, "PNG")
    print("wrote", out_path, "(矢量自检，含 66dp 安全圆)")



# --------------------------------------------------------------------- main

def main():
    font_path = find_font()
    print("font:", font_path)

    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)
    v26 = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(v26, exist_ok=True)

    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write(vector_background())
    with open(os.path.join(drawable, "ic_launcher_foreground.xml"), "w", encoding="utf-8") as f:
        f.write(vector_wordmark(font_path, ADAPTIVE_TEXT_DP))
    with open(os.path.join(drawable, "ic_launcher_monochrome.xml"), "w", encoding="utf-8") as f:
        f.write(vector_wordmark(font_path, ADAPTIVE_TEXT_DP))
    print("wrote drawable/ic_launcher_{background,foreground,monochrome}.xml")

    for r in (False, True):
        name = "ic_launcher_round" if r else "ic_launcher"
        with open(os.path.join(v26, name + ".xml"), "w", encoding="utf-8") as f:
            f.write(adaptive_xml(r))
    print("wrote mipmap-anydpi-v26/ic_launcher{,_round}.xml")

    for name, px in LEGACY.items():
        out = os.path.join(RES, "mipmap-" + name)
        os.makedirs(out, exist_ok=True)
        legacy_image(px, font_path).save(os.path.join(out, "ic_launcher.png"), "PNG")
        legacy_image(px, font_path, round_icon=True).save(
            os.path.join(out, "ic_launcher_round.png"), "PNG")
        print("legacy  mipmap-%-8s %3dpx" % (name, px))

    preview(font_path, os.path.join(HERE, "icon_preview.png"))
    vector_check(os.path.join(drawable, "ic_launcher_foreground.xml"),
                 os.path.join(HERE, "icon_vector_check.png"))


if __name__ == "__main__":
    main()
