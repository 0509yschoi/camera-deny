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
