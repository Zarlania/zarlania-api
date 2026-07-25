"""Allocate the next id and validate the id sequence is contiguous and unique."""

from __future__ import annotations

from .document import Document


def next_id(docs: list[Document], width: int) -> str:
    numbers = [int(d.id) for d in docs if d.id.isdecimal()]
    nxt = max(numbers) + 1 if numbers else 1
    if nxt >= 10**width:
        raise ValueError(f"id space exhausted: no {width}-digit id past {10**width - 1}")
    return str(nxt).zfill(width)


def validate_sequence(docs: list[Document], width: int) -> list[str]:
    errors: list[str] = []
    for doc in docs:
        if len(doc.id) != width or not doc.id.isdecimal():
            errors.append(f"{doc.path.name}: id '{doc.id}' must be {width} digits")

    numbers = sorted(int(d.id) for d in docs if d.id.isdecimal())
    seen: set[int] = set()
    for number in numbers:
        if number in seen:
            errors.append(f"duplicate id: {str(number).zfill(width)}")
        seen.add(number)

    for expected, actual in enumerate(sorted(seen), start=1):
        if expected != actual:
            errors.append(
                f"id sequence gap: expected {str(expected).zfill(width)}, "
                f"found {str(actual).zfill(width)}"
            )
            break
    return errors
