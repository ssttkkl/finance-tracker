"""Freeze Python timezone rules and verify transition and future-rule boundaries."""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
from pathlib import Path
from zoneinfo import ZoneInfo, available_timezones, _common, _tzpath, _zoneinfo

EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def document():
    names = {}; rules = {}; checks = []
    for name in sorted(available_timezones()):
        path = Path(_tzpath.find_tzfile(name)); digest = sha256(path.read_bytes()).hexdigest()
        names[name] = digest
        if digest in rules:
            continue
        with path.open('rb') as stream:
            indexes, transitions, offsets, isdst, _, tail = _common.load_data(stream)
        initial = next((offset for offset, dst in zip(offsets, isdst) if not dst), offsets[indexes[0]] if indexes else offsets[0])
        changes = list(zip(transitions, [offsets[index] for index in indexes]))
        parsed = _zoneinfo._parse_tz_str(tail.decode()) if tail else None
        def calendar(value):
            assert isinstance(value, _zoneinfo._CalendarOffset), type(value)
            return [value.m, value.w, value.d, value.hour * 3600 + value.minute * 60 + value.second]
        recurring = isinstance(parsed, _zoneinfo._TZStr)
        future = dict(standard=int(parsed.std.utcoff.total_seconds()), daylight=int(parsed.dst.utcoff.total_seconds()), start=calendar(parsed.start), end=calendar(parsed.end)) if recurring else dict(fixed=int(parsed.utcoff.total_seconds()) if parsed else changes[-1][1] if changes else initial)
        rules[digest] = dict(initial=initial, transitions=changes, future=future)
        actual = ZoneInfo(name)
        samples = set(transitions)
        for year in [1, 1900, 2038, 2039, 2100, 2400, 9998]:
            samples.add(int((datetime(year, 6, 15, tzinfo=timezone.utc) - EPOCH).total_seconds()))
            if recurring:
                start, end = parsed.transitions(year)
                samples.update([int(start - parsed.std.utcoff.total_seconds()), int(end - parsed.dst.utcoff.total_seconds())])
        for at in sorted(samples):
            for second in [at - 1, at, at + 1]:
                try:
                    instant = EPOCH + timedelta(seconds=second); local = instant.astimezone(actual)
                except (OverflowError, ValueError):
                    continue
                if not changes or second > changes[-1][0]:
                    expected = parsed.get_trans_info_fromutc(second, instant.year)[0].utcoff.total_seconds() if recurring else future['fixed']
                else:
                    expected = initial
                    for transition, value in changes:
                        if transition > second:
                            break
                        expected = value
                assert expected == local.utcoffset().total_seconds(), (name, instant)
                checks.append([name, second, local.replace(tzinfo=None).isoformat(), local.fold, int(local.utcoffset().total_seconds())])
    return dict(names=names, rules=rules), checks


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--oracle-output', type=Path)
    args = parser.parse_args(); root = Path(__file__).resolve().parents[1]
    path = root / 'compose/backend/src/main/resources/portfolio-timezones.json'
    data, checks = document(); text = json.dumps(data, separators=(',', ':'), sort_keys=True) + '\n'
    if args.check:
        assert path.read_text() == text
    else:
        path.write_text(text)
    if args.oracle_output:
        if not args.oracle_output.is_absolute() or args.oracle_output.resolve().is_relative_to(root):
            raise ValueError('Use an absolute external oracle output')
        args.oracle_output.write_text(json.dumps(checks, separators=(',', ':')) + '\n')
    print(len(data['names']), 'names,', len(data['rules']), 'unique TZif rules,', len(checks), 'verified boundaries')
