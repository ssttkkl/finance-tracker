"""Generate anonymous Python cash timestamp acceptance/UTC oracle; no I/O adapters or database."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from ft.domain.cash_record_policy import _timestamp

ROOT=Path(__file__).resolve().parents[1]
TARGET=ROOT/'compose/backend/src/test/resources/python-cash-timestamps.json'


def content():
    values=set()
    dates=('2026-08-01','20260801','2026-W31-6','2026W316','2026-W31','2026W31')
    clocks=('12','1230','12:30','123045','12:30:45','12.5','12:30,5','12:30:45.123456789','12:00:60','24:00','12:99','1:00')
    offsets=('Z','+00:00','-08:00','+0130','+01:60','+00:00:60','+00:00:00.5','+00:00:01.5','+23:59:59.999999','-23:59:59.999999','+24','+00:99:99','', '+0','ZZ')
    for date in dates:
        for separator in ('T',' ','X','😀','\t','\n','1','Z','-'):
            for clock in clocks:
                for offset in offsets:
                    values.add(date+separator+clock+offset)
    for date in ('0001-01-01','9999-12-31','2024-02-29','2025-02-29','0000-01-01','2026-13-01','2026-W00-1','2026-W54-1','2021-W53-1','2026-213'):
        for clock in ('00:00','23:59:59.999999'):
            for offset in offsets: values.add(date+'T'+clock+offset)
    rows=[]
    for value in sorted(values):
        try: row={'value':value,'utc':_timestamp(value).isoformat()}
        except (ValueError,TypeError,OverflowError): row={'value':value,'error':'invalid_field'}
        rows.append(row)
    return json.dumps(rows,ensure_ascii=False,indent=2)+'\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    data=content()
    if args.check:
        if TARGET.read_text()!=data:raise RuntimeError('Cash timestamp oracle is stale')
    else:
        TARGET.parent.mkdir(parents=True,exist_ok=True);TARGET.write_text(data)
    print(f'{len(json.loads(data))} timestamp cases '+('verified' if args.check else 'generated'))


if __name__=='__main__':main()
