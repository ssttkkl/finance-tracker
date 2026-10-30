"""Export full funding scan and resolution inputs from the existing Python contract tests."""
from __future__ import annotations
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import pytest
from ft.application.cash_import_operations import _wire
import ft.application.cash_import_funding as funding


def export():
    cases=[]
    scan=funding.scan_cash_funding
    resolve=funding.resolve_cash_funding
    def capture_scan(files):
        files=tuple(files)
        case={'kind':'scan','files':_wire([asdict(file) for file in files])}
        try:
            result=scan(files)
        except (ValueError, ArithmeticError) as error:
            case.update(ok=False,error=str(error));cases.append(case);raise
        case.update(ok=True,result=_wire(asdict(result)));cases.append(case);return result
    def capture_resolve(value, *, selections, accounts):
        case={'kind':'resolve','scan':_wire(asdict(value)),'selections':_wire(selections),'accounts':_wire(accounts)}
        try:
            result=resolve(value,selections=selections,accounts=accounts)
        except (ValueError,ArithmeticError) as error:
            case.update(ok=False,error=str(error));cases.append(case);raise
        case.update(ok=True,result=_wire([asdict(row) for row in result]));cases.append(case);return result
    funding.scan_cash_funding=capture_scan
    funding.resolve_cash_funding=capture_resolve
    try:
        if pytest.main(['-q','tests/test_cash_import_funding.py','-p','no:cacheprovider']) != 0:
            raise RuntimeError('source tests failed')
    finally:
        funding.scan_cash_funding=scan;funding.resolve_cash_funding=resolve
    # Exact deduplication preserves each unique contract, with all nested types and fields.
    return list({json.dumps(case,ensure_ascii=False,sort_keys=True):case for case in cases}.values())

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    content=json.dumps(export(),ensure_ascii=False,sort_keys=True,indent=2)+'\n'
    if args.check:
        if args.output.read_text()!=content: raise SystemExit('funding golden mismatch')
    else:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(content)
