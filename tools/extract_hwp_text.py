import argparse
import re
import zlib
from pathlib import Path

import olefile


HWPTAG_PARA_TEXT = 67


def is_compressed(ole: olefile.OleFileIO) -> bool:
    header = ole.openstream("FileHeader").read()
    flags = int.from_bytes(header[36:40], "little")
    return bool(flags & 1)


def iter_records(data: bytes):
    offset = 0
    length = len(data)
    while offset + 4 <= length:
        header = int.from_bytes(data[offset : offset + 4], "little")
        offset += 4
        tag_id = header & 0x3FF
        level = (header >> 10) & 0x3FF
        size = (header >> 20) & 0xFFF
        if size == 0xFFF:
            if offset + 4 > length:
                break
            size = int.from_bytes(data[offset : offset + 4], "little")
            offset += 4
        payload = data[offset : offset + size]
        offset += size
        yield tag_id, level, payload


def clean_text(text: str) -> str:
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
    text = text.replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def extract_hwp_text(path: Path) -> str:
    parts: list[str] = []
    with olefile.OleFileIO(str(path)) as ole:
        compressed = is_compressed(ole)
        section_paths = sorted(
            entry for entry in ole.listdir(streams=True)
            if len(entry) == 2 and entry[0] == "BodyText" and entry[1].startswith("Section")
        )
        for entry in section_paths:
            data = ole.openstream(entry).read()
            if compressed:
                data = zlib.decompress(data, -15)
            for tag_id, _level, payload in iter_records(data):
                if tag_id == HWPTAG_PARA_TEXT and payload:
                    parts.append(payload.decode("utf-16le", errors="ignore"))
    return clean_text("\n".join(parts))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    text = extract_hwp_text(args.input)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text)


if __name__ == "__main__":
    main()
