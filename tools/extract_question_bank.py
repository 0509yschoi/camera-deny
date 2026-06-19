import argparse
import json
import re
from pathlib import Path

import pdfplumber


ANSWER_MARKS = {
    "①": "1",
    "②": "2",
    "③": "3",
    "④": "4",
    "⑤": "5",
}


def normalize(text: str) -> str:
    text = text.replace("\u00a0", " ")
    text = text.replace("\n", " ")
    text = re.sub(r"[ \t]+", " ", text)
    return text.strip()


def extract_columns(pdf_path: Path) -> str:
    parts: list[str] = []
    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            width, height = page.width, page.height
            boxes = [
                (0, 0, width / 2, height),
                (width / 2, 0, width, height),
            ]
            for box in boxes:
                text = page.crop(box).extract_text(x_tolerance=1, y_tolerance=3) or ""
                if text.strip():
                    parts.append(text)
    return "\n".join(parts)


def parse_answer_preview(text: str) -> dict[int, str]:
    answers: dict[int, str] = {}
    for number, mark in re.findall(r"\b(\d{2})\s*([①②③④⑤])", text):
        answers[int(number)] = ANSWER_MARKS[mark]
    return answers


def split_question_blocks(text: str) -> list[tuple[int, str]]:
    matches = list(re.finditer(r"문\s*(\d{1,2})\.\s*", text))
    blocks: list[tuple[int, str]] = []
    for index, match in enumerate(matches):
        number = int(match.group(1))
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        blocks.append((number, text[start:end]))
    return blocks


def trim_to_question_area(block: str) -> str:
    marker = re.search(r"(?:\n|\s)(?:①|ㄱ\.)\s*[○×]", block)
    if marker:
        block = block[: marker.start()]
    answer_marker = re.search(r"\n\s*정답\s*[①②③④⑤12345]", block)
    if answer_marker:
        block = block[: answer_marker.start()]
    return block


def parse_question_block(number: int, block: str, answer: str | None, source: str) -> dict | None:
    question_area = trim_to_question_area(block)
    question_area = re.sub(r"^문\s*\d{1,2}\.\s*", "", question_area).strip()
    choice_matches = list(re.finditer(r"[①②③④⑤]", question_area))
    if len(choice_matches) < 4:
        return None

    stem = normalize(question_area[: choice_matches[0].start()])
    choices: list[str] = []
    for idx, match in enumerate(choice_matches):
        start = match.end()
        end = choice_matches[idx + 1].start() if idx + 1 < len(choice_matches) else len(question_area)
        choices.append(normalize(question_area[start:end]))

    if not stem or not answer:
        return None

    keywords = make_keywords(stem, choices)
    return {
        "id": f"{source}-adminlaw-{number:02d}",
        "source": source.replace("-", " "),
        "subject": "행정법총론",
        "question": stem,
        "choices": choices[:5],
        "answer": answer,
        "explanation": "",
        "keywords": keywords,
    }


def make_keywords(stem: str, choices: list[str]) -> list[str]:
    text = stem + " " + " ".join(choices)
    tokens = re.findall(r"[가-힣A-Za-z0-9ㆍ]{2,}", text)
    stop = {
        "대한",
        "관한",
        "설명",
        "옳은",
        "옳지",
        "않은",
        "것은",
        "경우",
        "다툼이",
        "있는",
        "판례에",
        "의함",
    }
    seen: set[str] = set()
    keywords: list[str] = []
    for token in tokens:
        if token in stop or token in seen:
            continue
        if len(token) >= 8 or token.endswith(("법", "원칙", "처분", "신고", "소송", "행위")):
            seen.add(token)
            keywords.append(token)
        if len(keywords) >= 12:
            break
    return keywords


def convert_2025(pdf_path: Path) -> list[dict]:
    text = extract_columns(pdf_path)
    answers = parse_answer_preview(text)
    records: list[dict] = []
    for number, block in split_question_blocks(text):
        record = parse_question_block(number, block, answers.get(number), "2025-national-9")
        if record:
            records.append(record)
    return records


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    records = convert_2025(args.pdf)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"wrote {len(records)} records to {args.output}")


if __name__ == "__main__":
    main()
