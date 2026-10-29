"""Check Python json.loads(bytes) compatibility cases for the legacy category-delete boundary."""
from __future__ import annotations
import argparse,base64,json,random
from pathlib import Path


def cases():
    values=['',' ', '{','[','{"a":','{"a" 1}','{"a":1,}','[1,]','[1 2]','{"a":1} null',
        '"unterminated','"bad\\x"','"bad\\u12xx"','"bad\ncontrol"','{"😀":1, bad}',
        'null','true','1.9','-0','1e999','NaN','Infinity','-Infinity','{"a":1,"a":2}',
        '"\\ud800"','"\\udfff"','{"n":'+'9'*4301+'}']
    texts=[v.encode() for v in values]
    for encoding in ('utf-8-sig','utf-16','utf-16-le','utf-16-be','utf-32','utf-32-le','utf-32-be'):
        texts.extend(v.encode(encoding) for v in ('{"a":"中文😀"}', '{"a":', '"\\ud800"'))
    for bad in (b'\xff',b'\x80',b'\xe2\x80x',b'\xf0\x90\x80',b'\xed\xa0\x80',b'\xef\xbb\xbf\xff',b'\xff\xfe{',b'\x00\x00\xfe\xff\x00\x11\x00\x00'):
        texts.append(bad)
    for second in range(160,192):
        texts.append(bytes([237,second]))
        texts.extend(bytes([237,second,third]) for third in range(256))
    cesu=bytes.fromhex('ed a0 bd ed b8 80')
    texts.extend([b'{"'+cesu+b'":1,}',b'{"a":"'+cesu+b'",}',b'"'+cesu+b'"'])
    for code in range(32):texts.append(b'{"unused":"a'+bytes([code])+b'b"}')
    rng=random.Random(61029)
    valid='{"😀":"中文","x":[null,true,false,1.5,-2],"a":{}}'
    texts.extend(valid[:i].encode() for i in range(len(valid)))
    for _ in range(1000):
        raw=bytearray(valid.encode());raw[rng.randrange(len(raw))]=rng.randrange(256);texts.append(bytes(raw))
    result=[]
    for raw in texts:
        try:
            value=json.loads(raw);case={'value':value}
        except ValueError as exc:case={'error':str(exc)}
        result.append({'bytes':base64.b64encode(raw).decode(),**case})
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check',action='store_true');a=p.parse_args()
    target=Path(__file__).resolve().parents[1]/'compose/backend/src/test/resources/python-category-json.json'
    content=json.dumps(cases(),ensure_ascii=True,indent=2)+'\n'
    if a.check:
        if target.read_text()!=content:raise SystemExit('category JSON golden differs')
    else:target.write_text(content)
    print(f'{len(cases())} Python JSON byte cases')


if __name__=='__main__':main()
