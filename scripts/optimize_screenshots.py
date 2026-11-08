#!/usr/bin/env python3
"""压缩 media/screenshots/ 下的截图（交付体积治理）。

为什么需要：2026-11-09 实测 `git clone` 公开仓失败（`invalid index-pack output`），
原因是 57 张 PNG 截图占 165 MB —— 平均每张 2.9 MB，2160×2158 的 MR 透视截图
对 PNG 这种无损格式极不友好（大量渐变与噪点）。评委 clone 我们的仓库时会
踩同一个坑，第一印象直接受损。

压缩策略（按可读性优先，截图要能看清 UI 文字）：
- 缩到最长边 1600px（README 里展示够用，原始分辨率在评审现场不可读）
- 转 JPEG 质量 88（截图内容是 UI 与文字，JPEG 的块状伪影在质量 88 下不可见）
- 保留 PNG 原文件在 git 历史里，需要原图时从历史取

用法：
    python scripts/optimize_screenshots.py            # 生成 .jpg，报告前后体积
    python scripts/optimize_screenshots.py --apply    # 转换并删除对应 .png

依赖：Pillow
"""

import glob
import os
import sys

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow：pip install Pillow")
    sys.exit(1)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOT_DIR = os.path.join(ROOT, "media", "screenshots")

MAX_EDGE = 1600
JPEG_QUALITY = 88


def human(n: int) -> str:
    return f"{n / 1024 / 1024:.1f} MB"


def convert_one(png_path: str, apply: bool) -> tuple:
    jpg_path = png_path[:-4] + ".jpg"
    before = os.path.getsize(png_path)

    with Image.open(png_path) as im:
        im = im.convert("RGB")  # JPEG 不支持 alpha
        im.thumbnail((MAX_EDGE, MAX_EDGE), Image.LANCZOS)
        if apply:
            im.save(jpg_path, "JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)

    if apply:
        after = os.path.getsize(jpg_path)
        os.remove(png_path)
    else:
        # 干跑：用内存估算
        import io

        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)
        after = buf.tell()

    return before, after, jpg_path


def main() -> int:
    apply = "--apply" in sys.argv
    pngs = sorted(glob.glob(os.path.join(SHOT_DIR, "*.png")))
    if not pngs:
        print("没有 PNG 需要处理")
        return 0

    # 已有同名 jpg 的先跳过（避免覆盖人工挑过的版本）
    targets = [p for p in pngs if not os.path.exists(p[:-4] + ".jpg")]
    skipped = len(pngs) - len(targets)

    before_total = sum(os.path.getsize(p) for p in pngs)
    after_total = 0
    saved = 0

    for p in targets:
        before, after, jpg = convert_one(p, apply)
        after_total += after
        saved += before - after
        print(f"  {os.path.basename(p):<44} {human(before):>8} -> {human(after):>8}")

    # 跳过的那些 png 保持原样计入总体积
    for p in pngs:
        if os.path.exists(p):
            after_total += os.path.getsize(p)

    print()
    print(f"处理 {len(targets)} 张，跳过 {skipped} 张（已有同名 jpg）")
    # 转换后的总体积 = 新 jpg 们 + 未被处理的既有 jpg + 跳过的 png
    new_jpg_total = sum(
        os.path.getsize(p[:-4] + ".jpg") if apply else 0 for p in targets
    )
    if not apply:
        # 干跑：用估算值
        new_jpg_total = 0
        for p in targets:
            import io

            with Image.open(p) as im:
                im = im.convert("RGB")
                im.thumbnail((MAX_EDGE, MAX_EDGE), Image.LANCZOS)
                buf = io.BytesIO()
                im.save(buf, "JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)
                new_jpg_total += buf.tell()

    existing_jpg_total = sum(
        os.path.getsize(j)
        for j in glob.glob(os.path.join(SHOT_DIR, "*.jpg"))
        if not any(j == p[:-4] + ".jpg" for p in targets)
    )
    skipped_png_total = sum(
        os.path.getsize(p) for p in pngs if os.path.exists(p) and p in [x for x in pngs if os.path.exists(x[:-4] + ".jpg")]
    )
    after_total = new_jpg_total + existing_jpg_total + skipped_png_total

    print(f"总体积 {human(before_total)} -> {human(after_total)}  （省 {human(before_total - after_total)}）")
    if not apply:
        print("（干跑。加 --apply 实际执行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
