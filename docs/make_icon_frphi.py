"""【已由 make_icon_vector.py 取代，保留作历史】生成 FRPHI 安卓启动图标（legacy 各密度 PNG）。

自适应图标（Android 8.0+ 真正使用的）结构：
  前景层 ic_launcher_foreground.png  108dp 画布（xxxhdpi=432px），内容居中约 70%
  背景层 drawable/ic_launcher_background.xml（矢量渐变，放大不糊）
  mipmap-anydpi-v26/ic_launcher.xml 声明 adaptive-icon
legacy 图标（Android 7.x）仍用 mipmap-*/ic_launcher.png 48~192px。

用法: python make_icon_frphi.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res"))

TEXT = "FRPHI"
TOP = (21, 101, 192)      # #1565C0
BOTTOM = (30, 136, 229)   # #1E88E5

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def find_font(size):
    for path in (r"C:\Windows\Fonts\arialbd.ttf", r"C:\Windows\Fonts\segoeuib.ttf",
                 r"C:\Windows\Fonts\arial.ttf"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
    return ImageFont.load_default()


def draw_icon(size, round_icon=False, with_bg=True, content_scale=1.0):
    """画一个图标；content_scale<1 用于自适应图标安全区缩放。"""
    S = size * 8  # 8 倍超采样，缩小后边缘锐利
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    if with_bg:
        # 竖向渐变
        grad = Image.new("RGBA", (1, S))
        gd = ImageDraw.Draw(grad)
        for y in range(S):
            t = y / max(1, S - 1)
            gd.point((0, y), fill=(
                int(TOP[0] + (BOTTOM[0] - TOP[0]) * t),
                int(TOP[1] + (BOTTOM[1] - TOP[1]) * t),
                int(TOP[2] + (BOTTOM[2] - TOP[2]) * t),
                255))
        grad = grad.resize((S, S))
        mask = Image.new("L", (S, S), 0)
        md = ImageDraw.Draw(mask)
        if round_icon:
            md.ellipse([0, 0, S - 1, S - 1], fill=255)
        else:
            md.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
        img.paste(grad, (0, 0), mask)

    # 纯蓝底 + 中间白字 FRPHI（用户要求：不要气泡）
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # 白字撑满画布 86%，居中
    target_w = S * 0.86
    fs = int(S * 0.60)
    font = find_font(fs)
    while fs > 10:
        font = find_font(fs)
        tw = d.textbbox((0, 0), TEXT, font=font)[2]
        if tw <= target_w:
            break
        fs -= 2
    bbox = d.textbbox((0, 0), TEXT, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx2 = (S - tw) / 2 - bbox[0]
    ty2 = (S - th) / 2 - bbox[1] + fs * 0.06
    d.text((tx2, ty2), TEXT, font=font, fill=(255, 255, 255, 255))

    if content_scale != 1.0:
        new = max(1, int(S * content_scale))
        layer = layer.resize((new, new), Image.LANCZOS)
        off = (S - new) // 2
        img.alpha_composite(layer, (off, off))
    else:
        img.alpha_composite(layer)

    return img.resize((size, size), Image.LANCZOS)


def main():
    # 只生成 legacy 图标（上一版样式：方形圆角底 + 白气泡 + FRPHI，不用自适应图标）
    for name, px in LEGACY.items():
        out_dir = os.path.join(RES, "mipmap-" + name)
        os.makedirs(out_dir, exist_ok=True)
        draw_icon(px).save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
        draw_icon(px, round_icon=True).save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
        print("legacy  mipmap-%-8s %3dpx" % (name, px))

    draw_icon(512).save(os.path.join(HERE, "frphi_icon_preview.png"), "PNG")
    print("wrote frphi_icon_preview.png (512px)")


if __name__ == "__main__":
    main()