"""docs/model_report.md holds one section per model family; each training script rewrites only its own."""

from training.export_data import ROOT

REPORT = ROOT / "docs" / "model_report.md"


def write_section(name: str, text: str) -> None:
    """Replaces the block between <!-- name:start --> and <!-- name:end -->, or appends it."""
    start, end = f"<!-- {name}:start -->", f"<!-- {name}:end -->"
    block = f"{start}\n{text.strip()}\n{end}"
    current = REPORT.read_text() if REPORT.exists() else ""
    if start in current and end in current:
        current = current[: current.index(start)] + block + current[current.index(end) + len(end):]
    else:
        current = (current.rstrip() + "\n\n" if current.strip() else "") + block + "\n"
    REPORT.write_text(current)
