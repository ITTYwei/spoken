"""离线导入口语题卡：验证固定提交和发布摘要，保留原题与旧课程编号。

只处理 speaking_cards.jsonl，不导入词汇、发音词对、语法、写作、阅读或听力。
中文练习目标与指导为本项目新增，完整教材教学字段保存在来源快照中。
"""
import argparse
import hashlib
import json
import subprocess
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "spoken-server-app/src/main/resources/textbooks"
EXPECTED_COMMIT = "10282cc853a03b6984907fec0affe6cbbd4fe0b0"
GUIDANCE = {
    1: "本课为 Part 1 日常问答，一次问一个原题，邀请补充细节。优先回答用户学习问题，遵循学员语言档位，不强制跟读，不评分。",
    2: "本课为 Part 2 长段表达，先介绍总题与完整要点，让用户准备并完整表达，再问收尾问题。要点不是独立短问。遵循学员档位，不强制跟读，不评分。",
    3: "本课为 Part 3 观点讨论，一次问一个原题，引导说明理由、举例或比较。优先回答用户学习问题，遵循学员档位，不强制跟读，不评分。",
}

def main(source):
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"]).decode().strip()
    if commit != EXPECTED_COMMIT:
        raise ValueError("来源提交已变化，请先审阅内容再更新固定版本")
    pack = source / "content/core-en"
    manifest = json.loads((pack / "manifest.json").read_text(encoding="utf-8"))
    raw = (pack / "data/speaking_cards.jsonl").read_bytes().replace(b"\r\n", b"\n")
    expected = manifest["checksums"]["data/speaking_cards.jsonl"].removeprefix("sha256:")
    if manifest["license"] != "CC0-1.0" or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError("口语内容许可或摘要不符合固定发布版本")
    cards = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip() and not line.startswith("//")]
    if len(cards) != manifest["counts"]["speaking_cards"] or len({c["id"] for c in cards}) != len(cards):
        raise ValueError("口语题卡数量或唯一编号无效")
    existing = json.loads((DEST / "bandready-daily.json").read_text(encoding="utf-8"))
    retained = {l["sourceCardId"] for l in existing["lessons"]}
    if not retained.issubset({c["id"] for c in cards}):
        raise ValueError("已发布日常课程的来源题卡不存在，不能覆盖历史编号")
    title_counts = Counter(c["title"].casefold() for c in cards)
    books = []
    for part in (1, 2, 3):
        lessons = []
        for card in cards:
            if card["part"] != part or card["id"] in retained:
                continue
            data = card["payload_json"]
            if part == 2:
                cue = data["cue_card"]
                units = [(card["id"], card["title"], [cue["topic"], *cue["bullets"], *cue.get("rounding_off", [])])]
            elif part == 3:
                # Part 3 原生按主题组织。以完整主题为一课，保留全部原题与反向追问，
                # 避免将多主题长题卡强塞进单课或静默裁掉最后一组问题。
                units = [(card["id"] + "-theme-" + str(i + 1), theme["title"],
                          [*theme["questions"], *([theme["counter_probe"]] if theme.get("counter_probe") else [])])
                         for i, theme in enumerate(data["part3_themes"])]
            else:
                units = [(card["id"], card["title"], data["questions"])]
            for unit_id, title, questions in units:
                aliases = [unit_id]
                if part != 3 and title_counts[title.casefold()] == 1:
                    aliases.append(title)
                entry = {"id": unit_id, "title": title,
                         "objective": {1: "回答日常话题，补充具体细节。", 2: "围绕题卡要点组织一段完整表达。", 3: "讨论观点，说明原因并举例。"}[part],
                         "sourceCardId": card["id"], "questions": questions,
                         "aliases": aliases, "addedGuidance": GUIDANCE[part]}
                context = "当前文本课程数据：" + entry["title"] + "\n教材原题：\n" + "\n".join(questions) + "\n本项目陪练指导：" + entry["addedGuidance"] + "\n"
                if not questions or len(context.encode("utf-8")) + 16 > 1400 or len(unit_id) > 80:
                    raise ValueError("原题组无法完整装入上下文或编号过长：" + unit_id)
                lessons.append(entry)
        books.append({"id": f"bandready-speaking-p{part}", "title": f"BandReady · 口语 Part {part}",
                      "part": {1: "PART_ONE", 2: "PART_TWO", 3: "PART_THREE"}[part],
                      "sourceUrl": f"https://github.com/Luxshan2000/ielts-prep/tree/{commit}/content/core-en",
                      "sourceCommit": commit, "license": manifest["license"], "lessons": lessons})
    # 全部校验成功才写入。新目录只含口语；旧三课仍使用原来教材和课编号。
    unit_titles = Counter(l["title"].casefold() for b in books for l in b["lessons"])
    for book in books:
        for unit in book["lessons"]:
            if unit_titles[unit["title"].casefold()] == 1 and unit["title"] not in unit["aliases"]:
                unit["aliases"].append(unit["title"])
    generated = DEST / "bandready-speaking"
    generated.mkdir(parents=True, exist_ok=True)
    files = ["bandready-daily.json"]
    for book in books:
        filename = book["id"] + ".json"
        (generated / filename).write_bytes((json.dumps(book, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        files.append("bandready-speaking/" + filename)
    (DEST / "catalog.json").write_bytes((json.dumps(files, indent=2) + "\n").encode())
    (generated / "speaking_cards.source.jsonl").write_bytes(raw)
    report = {"sourceCommit": commit, "license": manifest["license"], "speakingCards": len(cards),
              "parts": dict(Counter(c["part"] for c in cards)), "retainedLessons": len(retained),
              "importedLessons": sum(len(b["lessons"]) for b in books), "sourceSha256": expected,
              "nonSpeakingImported": False}
    (generated / "IMPORT-REPORT.json").write_bytes((json.dumps(report, indent=2) + "\n").encode())
    print(json.dumps(report))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("D:/develop/code/github/ielts-prep"))
    main(parser.parse_args().source)
