# Question Bank

The app can use a bundled past-question database to improve answer selection.
Put records in:

```text
app/src/main/assets/exam_question_bank.jsonl
```

Each line is one JSON object:

```json
{"id":"2024-local-9-adminlaw-18","source":"2024 지방직 9급","subject":"행정법","question":"...","choices":["...","...","...","..."],"answer":"2","explanation":"짧은 근거","keywords":["처분의 재심사","행정기본법"]}
```

Required fields:

- `id`: stable unique id
- `question`: question stem
- `answer`: answer number as text, such as `"2"`

Recommended fields:

- `source`: exam/year/source label
- `subject`: subject name
- `choices`: array of printed choices
- `explanation`: short reason or trap note
- `keywords`: repeated exam concepts, statutes, case-law names, and trap terms

Workflow:

1. Collect legally usable past-question text.
2. Normalize each question into one JSONL line.
3. Add it to `exam_question_bank.jsonl`.
4. Build a new release.
5. In OCR Debug, check `PAST_QUESTION_REFERENCES` to see what the app retrieved.

Keep explanations short. The app only needs enough context to choose the likely answer.

## Current Import Notes

- Current bundled data includes admin-law and public-administration records. Keep `subject` values distinct so retrieval debug stays readable.
- `AS_NJJ.pdf`: imported 20 public-administration questions from 2011 national 9.
- `9급지방직공무원행정학개론20250621(교사용).hwp`: imported 20 public-administration questions.
- `9급지방직공무원행정학개론20240622(교사용).hwp`: imported 20 public-administration questions.
- `9급지방직공무원행정학개론20230610(교사용).hwp`: imported 20 public-administration questions.
- `9급지방직공무원행정학개론20220618(교사용).hwp`: imported 20 public-administration questions.
- `9급지방직공무원행정학개론20210605(교사용).hwp`: imported 20 public-administration questions.
- `9급지방직공무원행정학개론20200613(교사용).hwp`: imported 20 public-administration questions.
- `2024 국가직 9급 행정법총론 해설 김대근.pdf`: imported 20 questions.
- `2025 국가직 9급 행정법총론 해설 유휘운.pdf`: imported 20 questions.
- `9급지방직공무원행정법총론20250621(교사용).pdf`: imported 20 questions.
- `9급지방직공무원행정법총론20240622(교사용).pdf`: imported 20 questions.
- `2026 국가직 9급 행정법총론 해설 공기출.pdf`: not imported yet because the provided PDF text is mainly generated explanation text and does not contain clean question/choice source text.
- `2023 지방직 9급 행정법총론 해설 이승철.pdf`: not imported yet because it is image-based and needs OCR before structured conversion.
- `2024 국가직 9급 행정법총론 해설 이승철.pdf`: not imported because it is image-based and needs OCR before structured conversion.
- Other 2024 admin-law explanations from different instructors were not imported to avoid duplicate records for the same exam.
- HWP files can be better than image PDFs when text is extractable. `tools/extract_hwp_text.py` can extract text from HWP5 files.
- Some CBT HWP explanation files include a notice prohibiting document modification or DB storage; those files should not be bundled into the app database.

Use `tools/extract_question_bank.py` for text-based two-column PDFs similar to the 2025 file, then merge with:

```text
python tools/merge_question_bank.py
```

For numbered PDFs with `[1번 해설] ③` style answers:

```text
python tools/extract_question_bank.py --format numbered --source 2024-national-9 --pdf input.pdf --output tmp_question_bank.jsonl
python tools/merge_question_bank.py tmp_question_bank.jsonl
```

For CBT teacher PDFs where the correct answer is marked as `❶` through `❺`:

```text
python tools/extract_question_bank.py --format cbt --source 2025-local-9 --pdf input.pdf --output tmp_question_bank.jsonl
python tools/merge_question_bank.py tmp_question_bank.jsonl
```

For HWP CBT files with the same answer markers:

```text
python tools/extract_question_bank.py --format hwp-cbt --source 2023-local-9 --pdf input.hwp --output tmp_question_bank.jsonl
python tools/merge_question_bank.py tmp_question_bank.jsonl
```
