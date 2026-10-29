"""Export the existing cash ledger option protocol without databases or user data."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from ft.application.cash_ledger import CashLedgerCommandService

TARGET=Path(__file__).resolve().parents[1]/'compose/backend/src/test/resources/python-ledger-options.json'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    # options only reads the fixed domain catalogue; no service resources are used.
    service=object.__new__(CashLedgerCommandService)
    value=service.options();data=json.dumps(value,ensure_ascii=False,indent=2)+'\n'
    if args.check:
        if TARGET.read_text()!=data:raise RuntimeError('Cash ledger options are stale')
    else:TARGET.write_text(data)
    print(f"{len(value['record_types'])} record types and {len(value['relation_types'])} relations "+('verified' if args.check else 'exported'))


if __name__=='__main__':main()
