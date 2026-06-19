import argparse
import json
import re
from pathlib import Path

import pdfplumber

from extract_hwp_text import extract_hwp_text


ANSWER_MARKS = {
    "①": "1",
    "②": "2",
    "③": "3",
    "④": "4",
    "⑤": "5",
    "❶": "1",
    "❷": "2",
    "❸": "3",
    "❹": "4",
    "❺": "5",
}


def normalize(text: str) -> str:
    text = text.replace("\u00a0", " ")
    text = text.replace("\n", " ")
    for junk in ("漠杳", "氠瑢", "捤獥汤捯慤桥潴景氠瑢"):
        text = text.replace(junk, " ")
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
    for number, mark in re.findall(r"\[(\d{1,2})번\s*해설\]\s*([①②③④⑤])", text):
        answers[int(number)] = ANSWER_MARKS[mark]
    return answers


def make_record(
    number: int,
    source: str,
    subject: str,
    id_subject: str,
    stem: str,
    choices: list[str],
    answer: str,
) -> dict:
    return {
        "id": f"{source}-{id_subject}-{number:02d}",
        "source": source.replace("-", " "),
        "subject": subject,
        "question": stem,
        "choices": choices[:5],
        "answer": answer,
        "explanation": "",
        "keywords": make_keywords(stem, choices),
    }


def split_question_blocks(text: str) -> list[tuple[int, str]]:
    matches = list(re.finditer(r"문\s*(\d{1,2})\.\s*", text))
    blocks: list[tuple[int, str]] = []
    for index, match in enumerate(matches):
        number = int(match.group(1))
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        blocks.append((number, text[start:end]))
    return blocks


def split_numbered_question_blocks(text: str) -> list[tuple[int, str]]:
    answers = list(re.finditer(r"\[(\d{1,2})번\s*해설\]\s*[①②③④⑤]", text))
    blocks: list[tuple[int, str]] = []
    for answer_marker in answers:
        number = int(answer_marker.group(1))
        starts = list(re.finditer(rf"(?m)^\s*{number}\.\s+", text[: answer_marker.start()]))
        if not starts:
            continue
        start = starts[-1].start()
        blocks.append((number, text[start: answer_marker.start()]))
    return blocks


def trim_to_question_area(block: str) -> str:
    marker = re.search(r"\[\d{1,2}번\s*해설\]", block)
    if marker:
        block = block[: marker.start()]
    marker = re.search(r"(?:\n|\s)(?:①|ㄱ\.)\s*[○×]", block)
    if marker:
        block = block[: marker.start()]
    answer_marker = re.search(r"\n\s*정답\s*[①②③④⑤12345]", block)
    if answer_marker:
        block = block[: answer_marker.start()]
    return block


def parse_question_block(
    number: int,
    block: str,
    answer: str | None,
    source: str,
    subject: str,
    id_subject: str,
) -> dict | None:
    question_area = trim_to_question_area(block)
    question_area = re.sub(r"^(?:문\s*)?\d{1,2}\.\s*", "", question_area).strip()
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

    return make_record(number, source, subject, id_subject, stem, choices, answer)


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


def convert_2025(
    pdf_path: Path,
    subject: str = "행정법총론",
    id_subject: str = "adminlaw",
) -> list[dict]:
    text = extract_columns(pdf_path)
    answers = parse_answer_preview(text)
    records: list[dict] = []
    for number, block in split_question_blocks(text):
        record = parse_question_block(
            number,
            block,
            answers.get(number),
            "2025-national-9",
            subject,
            id_subject,
        )
        if record:
            records.append(record)
    return records


def convert_numbered(
    pdf_path: Path,
    source: str,
    subject: str,
    id_subject: str,
) -> list[dict]:
    with pdfplumber.open(pdf_path) as pdf:
        text = "\n".join((page.extract_text() or "") for page in pdf.pages)
    answers = parse_answer_preview(text)
    records: list[dict] = []
    for number, block in split_numbered_question_blocks(text):
        record = parse_question_block(
            number,
            block,
            answers.get(number),
            source,
            subject,
            id_subject,
        )
        if record:
            records.append(record)
    return records


def convert_cbt(pdf_path: Path, source: str, subject: str, id_subject: str) -> list[dict]:
    text = extract_columns(pdf_path)
    return convert_cbt_text(text, source, subject, id_subject)


def convert_hwp_cbt(hwp_path: Path, source: str, subject: str, id_subject: str) -> list[dict]:
    return convert_cbt_text(extract_hwp_text(hwp_path), source, subject, id_subject)


def convert_cbt_text(text: str, source: str, subject: str, id_subject: str) -> list[dict]:
    answer_table = parse_standalone_answer_table(text)
    records: list[dict] = []
    for number, block in split_cbt_question_blocks(text):
        if number < 1 or number > 40:
            continue
        record = parse_cbt_question_block(
            number,
            block,
            source,
            subject,
            id_subject,
            answer_table.get(number),
        )
        if record:
            records.append(record)
    return records


def parse_standalone_answer_table(text: str) -> dict[int, str]:
    tokens = re.findall(r"(?m)^\s*(\d{1,2}|[①②③④⑤])\s*$", text)
    answers: dict[int, str] = {}
    for index in range(0, len(tokens)):
        first_numbers = [str(n) for n in range(1, 11)]
        second_numbers = [str(n) for n in range(11, 21)]
        if tokens[index : index + 10] == first_numbers:
            marks = tokens[index + 10 : index + 20]
            if len(marks) == 10 and all(mark in ANSWER_MARKS for mark in marks):
                for offset, mark in enumerate(marks, start=1):
                    answers[offset] = ANSWER_MARKS[mark]
        if tokens[index : index + 10] == second_numbers:
            marks = tokens[index + 10 : index + 20]
            if len(marks) == 10 and all(mark in ANSWER_MARKS for mark in marks):
                for offset, mark in enumerate(marks, start=11):
                    answers[offset] = ANSWER_MARKS[mark]
    return answers


def split_cbt_question_blocks(text: str) -> list[tuple[int, str]]:
    for junk in ("氠瑢", "捤獥汤捯慤桥潴景氠瑢"):
        text = text.replace(junk, "\n")
    matches = list(re.finditer(r"(?m)^\s*(\d{1,2})\.\s+", text))
    blocks: list[tuple[int, str]] = []
    for index, match in enumerate(matches):
        number = int(match.group(1))
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        blocks.append((number, text[start:end]))
    return blocks


def parse_cbt_question_block(
    number: int,
    block: str,
    source: str,
    subject: str,
    id_subject: str,
    table_answer: str | None = None,
) -> dict | None:
    question_area = trim_to_question_area(block)
    question_area = re.sub(r"^\s*\d{1,2}\.\s*", "", question_area).strip()
    choice_matches = list(re.finditer(r"[①②③④⑤❶❷❸❹❺]", question_area))
    if len(choice_matches) < 4:
        return None

    stem = normalize(question_area[: choice_matches[0].start()])
    choices: list[str] = []
    answer: str | None = table_answer
    for idx, match in enumerate(choice_matches):
        marker = match.group(0)
        if marker in "❶❷❸❹❺":
            answer = ANSWER_MARKS[marker]
        start = match.end()
        end = choice_matches[idx + 1].start() if idx + 1 < len(choice_matches) else len(question_area)
        choices.append(normalize(question_area[start:end]))

    if not stem or not answer:
        return None

    return make_record(number, source, subject, id_subject, stem, choices, answer)


def convert_bracket_answer(
    pdf_path: Path,
    source: str,
    subject: str,
    id_subject: str,
) -> list[dict]:
    with pdfplumber.open(pdf_path) as pdf:
        text = "\n".join((page.extract_text() or "") for page in pdf.pages)
    answer_section_index = text.find("정답 및 해설")
    question_text = text[:answer_section_index] if answer_section_index >= 0 else text
    answers = parse_bracket_answers(text)
    records: list[dict] = []
    for number, block in split_compact_question_blocks(question_text):
        record = parse_compact_question_with_answer(
            number,
            block,
            answers.get(number),
            source,
            subject,
            id_subject,
        )
        if record:
            records.append(record)
    return records


def parse_bracket_answers(text: str) -> dict[int, str]:
    answers: dict[int, str] = {}
    for number, mark in re.findall(r"(?m)^\s*(\d{1,2})\.\s*\[정답\s*([①②③④⑤])\]", text):
        answers[int(number)] = ANSWER_MARKS[mark]
    return answers


def split_compact_question_blocks(text: str) -> list[tuple[int, str]]:
    matches = list(re.finditer(r"문\s*(\d{1,2})\.\s*", text))
    blocks: list[tuple[int, str]] = []
    for index, match in enumerate(matches):
        number = int(match.group(1))
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        blocks.append((number, text[start:end]))
    return blocks


def parse_compact_question_with_answer(
    number: int,
    block: str,
    answer: str | None,
    source: str,
    subject: str,
    id_subject: str,
) -> dict | None:
    if not answer:
        return None
    question_area = block
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
    if not stem:
        return None
    return make_record(number, source, subject, id_subject, stem, choices, answer)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", default="2025-national-9")
    parser.add_argument("--subject", default="행정법총론")
    parser.add_argument("--id-subject", default="adminlaw")
    parser.add_argument(
        "--format",
        choices=["columns", "numbered", "cbt", "hwp-cbt", "bracket-answer"],
        default="columns",
    )
    args = parser.parse_args()

    if args.format == "columns":
        records = convert_2025(args.pdf, args.subject, args.id_subject)
    elif args.format == "numbered":
        records = convert_numbered(args.pdf, args.source, args.subject, args.id_subject)
    elif args.format == "cbt":
        records = convert_cbt(args.pdf, args.source, args.subject, args.id_subject)
    elif args.format == "bracket-answer":
        records = convert_bracket_answer(args.pdf, args.source, args.subject, args.id_subject)
    else:
        records = convert_hwp_cbt(args.pdf, args.source, args.subject, args.id_subject)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"wrote {len(records)} records to {args.output}")


if __name__ == "__main__":
    main()
