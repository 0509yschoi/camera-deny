import json
import argparse
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
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "inputs",
        nargs="*",
        type=Path,
        default=[Path("tmp_2025_question_bank.jsonl")],
    )
    args = parser.parse_args()

    asset = Path("app/src/main/assets/exam_question_bank.jsonl")

    records_by_id: dict[str, dict] = {}
    for record in read_records(asset):
        records_by_id[record["id"]] = record
    for path in args.inputs:
        for record in read_records(path):
            records_by_id[record["id"]] = record

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
