# -*- coding: utf-8 -*-
"""生成 M5 导入实测用的三种格式样例牌组 + 构建一个最小合法 .apkg。"""
import os, sqlite3, zipfile

OUT = r"E:\abc\Trae-CN\temp_20261107_01\memory-palace\testdata"
os.makedirs(OUT, exist_ok=True)

# 1) CSV：语言词汇（含表头 + 引号字段含逗号，验解析器边界）
csv_lines = [
    "front,back",
    "luminous,adj.发光的；明亮的",
    '"run, rise","v.运行/奔跑（含, 逗号字段）"',
    "ubiquitous,adj.无处不在的",
    "resilient,adj.有韧性的",
    "ambiguous,adj.模棱两可的",
]
open(os.path.join(OUT, "lang_en.csv"), "w", encoding="utf-8", newline="").write("\n".join(csv_lines) + "\n")

# 2) 纯文本：生活常识（TAB + :: + 无分隔符混排，验三种分隔）
txt_lines = [
    "# 生活常识牌组（M5 实测样例）",
    "闪电和雷声\t闪电先被看到：光速远大于声速",
    "蜂蜜 :: 几乎不会变质（低水分+弱酸性抑菌）",
    "冰水混合物的温度——标准大气压下为 0°C",
    "蝙蝠 - 靠回声定位导航",
    "鸵鸟",
]
open(os.path.join(OUT, "common_sense.txt"), "w", encoding="utf-8").write("\n".join(txt_lines) + "\n")

# 3) APKG：专业术语（最小合法 anki2：notes 表 + \x1f 拼接 flds，含 HTML 标签）
db = os.path.join(OUT, "collection.anki2")
if os.path.exists(db):
    os.remove(db)
con = sqlite3.connect(db)
con.execute(
    "CREATE TABLE notes (id INTEGER PRIMARY KEY, guid TEXT, mid INTEGER, mod INTEGER, "
    "usn INTEGER, tags TEXT, flds TEXT, sfld TEXT, csum INTEGER, flags INTEGER, data TEXT)"
)
terms = [
    ("TCP 三次握手", "SYN → SYN+ACK → <b>ACK</b>，建立可靠连接"),
    ("向量数据库", "按 embedding 相似度检索<br/>常用：FAISS / Milvus"),
    ("幂等性", "同一操作执行多次 &amp; 一次效果相同"),
    ("MVCC", "多版本并发控制：读不加锁，快照隔离"),
]
for i, (f, b) in enumerate(terms, start=1):
    con.execute(
        "INSERT INTO notes VALUES (?,?,?,?,0,'',?,?,0,0,'')",
        (i, f"guid{i}", 1, 0, f + "\x1f" + b, f),
    )
con.commit()
con.close()
apkg = os.path.join(OUT, "tech_terms.apkg")
if os.path.exists(apkg):
    os.remove(apkg)
with zipfile.ZipFile(apkg, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(db, "collection.anki2")
    z.writestr("media", "{}")
os.remove(db)

for f in sorted(os.listdir(OUT)):
    print(f, os.path.getsize(os.path.join(OUT, f)), "B")
