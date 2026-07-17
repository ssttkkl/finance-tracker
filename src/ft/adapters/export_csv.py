"""CSV export adapter for CLI-owned output files."""
import csv


def write_csv_export(payload, output):
    fieldnames = list(payload.fieldnames)
    if not fieldnames and payload.rows:
        fieldnames = list(payload.rows[0].keys())
    with open(output, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(payload.rows)
