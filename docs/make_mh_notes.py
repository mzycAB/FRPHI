# -*- coding: utf-8 -*-
"""
重新生成内置资源包的「双押贴图」（*_mh.png）。

为什么要重做：
  旧的一套 *_mh 是把普通贴图整张放大 1.15 倍再塞进 (普通尺寸 + 150) 的画布里，
  音符本体被放大、两端还被画布边裁掉了一截；而 Phira 默认包的做法是
  「音符本体原样不动 + 四周留出光晕位置」。于是旧双押音符看起来比普通音符大一圈，
  文件也大 5~7 倍（放大后的模糊边缘几乎压不动）。

现在的做法（与 Phira 官方默认包一致）：
  * click / flick / drag：画布 = 普通尺寸 + 150（与旧文件同尺寸，代码里 1.15 倍的
    宽度比照旧成立），音符本体按 **原始像素尺寸** 居中贴进去，外圈画一圈金色光晕。
    绘制时贴图宽度是普通的 1.15 倍，而本体只占贴图宽度的 1/1.15
    ⇒ 屏幕上的音符本体与普通音符逐像素同大。
  * hold：Phira 画长条的宽度倍率也是 click 的 1.15 倍（见 prpr/core/note.rs），
    所以 hold_mh = hold.png 宽 ×1.15（1150）、**高度与 hold.png 相同**、本体水平居中，
    光晕只画在左右 75px 留白里 ⇒ 双押长条的本体与普通长条完全一样大，光晕挂在本体外面。
    头/尾切片大小由 info.yml 的 holdAtlasMH 决定，本体是 1:1 的，所以 holdAtlasMH 必须
    和 holdAtlas 同值（[50, 50]，见 ResPack.ensureInfo）。

用法：python make_mh_notes.py <notes 目录>
"""
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

GLOW_RGB = (0xD8, 0xBE, 0x55)     # Phira 默认包那圈金黄色的光
SIGMA = 26.0                      # 光晕扩散半径（像素）
GAMMA = 0.72                      # <1 = 把尾巴拉长一点，贴近 Phira 的衰减曲线
PEAK = 0.88                       # 紧贴本体处的不透明度




def glow_from_mask(mask: Image.Image, sigma=SIGMA, gamma=GAMMA, peak=PEAK) -> Image.Image:
    """把一张 8bit 蒙版模糊成光晕 RGBA。

    直接用高斯模糊而不是算距离场：距离场在拐角处会断（上下左右哪个方向都取不到最近点），
    糊出来会出现方块样的硬边；模糊出来的光晕天然连续，和 Phira 的观感一致。
    """
    b = np.asarray(mask.filter(ImageFilter.GaussianBlur(sigma)), dtype=np.float64) / 255.0
    a = peak * np.power(np.clip(b / 0.5, 0.0, 1.0), gamma)
    out = np.zeros((a.shape[0], a.shape[1], 4), dtype=np.uint8)
    out[..., 0] = GLOW_RGB[0]
    out[..., 1] = GLOW_RGB[1]
    out[..., 2] = GLOW_RGB[2]
    out[..., 3] = np.clip(a * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def make_outer(core: Image.Image) -> Image.Image:
    """本体原样居中 + 外圈光晕（click / flick / drag）"""
    cw, ch = core.size
    canvas = Image.new("RGBA", (cw + 150, ch + 150), (0, 0, 0, 0))
    canvas.paste(core, ((canvas.width - cw) // 2, (canvas.height - ch) // 2), core)
    glow = glow_from_mask(canvas.split()[3])
    return Image.alpha_composite(glow, canvas)      # 光晕在下，本体在上


def make_inner(core: Image.Image) -> Image.Image:
    """长条：画布 = hold.png 宽 ×1.15、**高度不变**；本体原样水平居中 + 两侧留白里画光晕。

    两件事缺一不可：
      * 宽度必须 ×1.15：Phira 画音符的宽度倍率取 `click_mh/click`（≈1.1503），对长条同样生效，
        整张贴图被拉伸到这个宽度 —— 本体居中后，画出来的本体宽度正好 = 普通长条。
      * 高度不能动：FRPHI 把整张贴图拉伸到长条高度，上下留白会把本体压短；
        而且头/尾切片的高度 = atlas 像素数 × 绘制比例，本体是 1:1 的，atlas 就得和 holdAtlas 同值。
    光晕只画在左右 75px 留白里（和蓝键的外圈光晕同一套参数），本体一个像素都不碰。
    """
    cw, ch = core.size
    canvas = Image.new("RGBA", (cw + 150, ch), (0, 0, 0, 0))
    canvas.paste(core, (75, 0), core)
    glow = glow_from_mask(canvas.split()[3])
    return Image.alpha_composite(glow, canvas)  # 光晕在下，本体在上


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/notes"
    pairs = [
        ("click.png", "click_mh.png", "outer"),
        ("flick.png", "flick_mh.png", "outer"),
        ("drag.png", "drag_mh.png", "outer"),
        ("hold.png", "hold_mh.png", "inner"),
    ]
    for src, dst, mode in pairs:
        base = Image.open(os.path.join(d, src)).convert("RGBA")
        path = os.path.join(d, dst)
        old = os.path.getsize(path) if os.path.exists(path) else 0
        out = make_outer(base) if mode == "outer" else make_inner(base)
        out.save(path, "PNG", optimize=True)
        new = os.path.getsize(path)
        print(
            "%-14s %-12s %-13s %7d → %6d 字节 (%+d%%)"
            % (src, dst, str(out.size), old, new,
               round((new - old) * 100.0 / old) if old else 0)
        )


if __name__ == "__main__":
    main()
