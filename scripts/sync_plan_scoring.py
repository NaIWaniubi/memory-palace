#!/usr/bin/env python3
"""把 PICO 作战方案 v4 的自评口径与竞品调研结论同步到仓库实际状态。

改动点（都是 2026-11-09 竞品调研后的真实结果，不是措辞调整）：
1. 总分 74–86/91 → 72–84/89（创新分下调）
2. 创新维度 20–23 → 18–21，前提条件从「未发现撞车型竞品」改为「存在 2 款高相似度先例」
3. 排期里 11/10–11/13 的竞品调研任务标记为已完成，并写入结论
4. 风险表 L0 估分同步

用法：python scripts/sync_plan_scoring.py [方案文件路径]
默认路径指向本机 Trae-CN 助手目录下的方案 v4；可传参覆盖。
"""

import io
import os
import sys

DEFAULT_PLAN = os.path.join(
    os.path.expanduser("~"),
    ".trae-cn", "assistant", "2026-11-07", "two-competitions", "outputs",
    "PICO-记忆宫殿-完整作战方案-v4.md",
)

REPLACEMENTS = [
    # 0. 结论先行
    (
        "- **诚实总分：74–86（验证项全过）/ 91（理论上限）**。单一自评口径见 §4，三处并存口径已消除。",
        "- **诚实总分：72–84（验证项全过）/ 89（理论上限）**。单一自评口径见 §4，三处并存口径已消除。\n"
        "- **创新分已下调（2026-11-09 竞品调研回填）**：25 款 XR/AR 同类取证后，查证到 2 款高相似度先例"
        "（Loci Devpost MR 原型、LOCI Memory Palace Trainer 已上架 Meta Quest），"
        "创新维度从 20–23 降到 **18–21**，总分同步下调 2 分。"
        "关键统计：「真实空间锚定」与「遗忘曲线调度」同时为「是」的产品 **0 款** —— 立足点仍成立，"
        "但答辩口径必须主动披露这两款先例（详见 §13 v5 响应）。",
    ),
    # 4. 自评表
    (
        "| 01 创新 | 25 | 对比表（Anki/墨墨 + **XR 同类竞品调研 ≥5 款**【11/13 前完成，见 §7】）；"
        "loci 文献引用；首 90 秒视频 | XR 调研未发现撞车型竞品；若发现则下调 | **20–23**（撞车则 18–20） |",
        "| 01 创新 | 25 | 对比表（**XR/AR 同类 25 款，已完成**，见仓库 `docs/COMPETITORS.md`）；"
        "loci 文献引用；首 90 秒视频 | **存在 2 款高相似度先例（Loci Devpost MR 原型 / LOCI Quest 应用）**，"
        "主动披露并区隔 | **18–21**（PICO 商店补查再撞车 → 16–18） |",
    ),
    (
        "| **合计** | 100 | | | **74–86（验证全过）/ 91（上限）** |",
        "| **合计** | 100 | | | **72–84（验证全过）/ 89（上限）** |",
    ),
    # 7. 排期
    (
        "| 11/10–11/13 | **core：数据模型 + SM-2 + 单测；CSV 导入；XR 同类竞品调研"
        "（Meta Store/Steam/SideQuest 记忆宫殿与间隔重复类 ≥5 款，逐一截图建档，对比表补\"XR 同类\"列）** | "
        "单测 ≥20 例全绿；调研档案 5 份；若发现撞车型竞品，创新自评下调 18–20 并重排证据 |",
        "| 11/10–11/13 | **core：数据模型 + SM-2 + 单测；CSV 导入；XR 同类竞品调研** | "
        "**【2026-11-09 提前完成】** core 42 例单测全绿（要求 ≥20）；"
        "竞品调研 25 款取证完成，档案入仓库 `docs/COMPETITORS.md`；"
        "**发现 2 款高相似度先例，创新自评已下调至 18–21 并重排证据** |",
    ),
    # 9. 风险表
    (
        "| L0 | 完整版 | — | 全部功能 | 74–86 |",
        "| L0 | 完整版 | — | 全部功能 | 72–84 |",
    ),
]


def main() -> int:
    path = os.path.normpath(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PLAN)
    if not os.path.exists(path):
        print(f"plan not found: {path}")
        return 1

    with io.open(path, encoding="utf-8", newline="") as f:
        text = f.read()

    applied = 0
    missing = []
    for old, new in REPLACEMENTS:
        if old in text:
            text = text.replace(old, new)
            applied += 1
        else:
            missing.append(old[:60])

    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)

    print(f"applied {applied}/{len(REPLACEMENTS)} replacements -> {path}")
    for m in missing:
        print(f"  MISS: {m}...")
    return 0 if not missing else 2


if __name__ == "__main__":
    sys.exit(main())
