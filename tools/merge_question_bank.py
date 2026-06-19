import json
from pathlib import Path


def read_records(path: Path) -> list[dict]:
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    if "\\n{" in text and "\n{" not in text:
        text = text.replace("\\n", "\n")
    records: list[dict] = []
    for line in text.splitlines():
        if line.strip():
            records.append(json.loads(line))
    return records


def main() -> None:
    asset = Path("app/src/main/assets/exam_question_bank.jsonl")
    generated = Path("tmp_2025_question_bank.jsonl")

    records_by_id: dict[str, dict] = {}
    for record in read_records(asset) + read_records(generated):
        record_id = record["id"]
        records_by_id[record_id] = record

    records = list(records_by_id.values())

    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text(
        "\n".join(
            json.dumps(record, ensure_ascii=False, separators=(",", ":"))
            for record in records
        ) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {len(records)} records to {asset}")


if __name__ == "__main__":
    main()
