"""Freeze the existing JVM NFC contract and independent normalizer samples for cash source nicknames."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'compose/business-domain/src/commonMain/kotlin/com/finance/tracker/business/domain/CashNfcData.kt'
VECTORS = ROOT / 'compose/business-domain/src/commonTest/kotlin/com/finance/tracker/business/domain/CashNfcOriginalVectors.kt'
JAVA = r'''
import java.text.Normalizer;
import java.util.*;
import jdk.internal.icu.lang.UCharacter;
public class CashNfcOracle {
 static String form(String value,Normalizer.Form form){return Normalizer.normalize(value,form);}
 static String hexPoints(String value){return value.codePoints().mapToObj(cp->Integer.toHexString(cp)).reduce((a,b)->a+","+b).orElse("");}
 static String hexUnits(String value){StringBuilder b=new StringBuilder();for(char c:value.toCharArray())b.append(String.format(Locale.ROOT,"%04x",(int)c));return b.toString();}
 public static void main(String[] args) {
  var pairs=new TreeMap<Long,Integer>();var samples=new LinkedHashSet<String>();var marks=new ArrayList<Integer>();var decomposed=new ArrayList<Integer>();
  int classStart=-1,classEnd=-1,lastClass=0;
  for(int cp=0;cp<=0x10ffff;cp++) {
   int ccc=UCharacter.getCombiningClass(cp);
   if(ccc!=lastClass || ccc==0) {
    if(classStart>=0)System.out.println("class\t"+classStart+"\t"+classEnd+"\t"+lastClass);
    classStart=ccc==0?-1:cp;
   }
   if(ccc!=0){classEnd=cp;marks.add(cp);}lastClass=ccc;
   String text=new String(Character.toChars(cp));String nfd=form(text,Normalizer.Form.NFD);String nfc=form(text,Normalizer.Form.NFC);
   if(!nfc.equals(text))samples.add(text);
   if(cp>=0xac00 && cp<=0xd7a3){samples.add(nfd);continue;}
   if(nfd.equals(text))continue;
   System.out.println("decomp\t"+Integer.toHexString(cp)+"\t"+hexPoints(nfd));decomposed.add(cp);samples.add(nfd);
   int[] points=nfd.codePoints().toArray();String prefix="";
   for(int point:points) {
    String left=form(prefix,Normalizer.Form.NFC);String right=new String(Character.toChars(point));String composed=form(left+right,Normalizer.Form.NFC);
    if(left.codePointCount(0,left.length())==1 && composed.codePointCount(0,composed.length())==1) {
     long key=((long)left.codePointAt(0)<<21)|point;Integer old=pairs.putIfAbsent(key,composed.codePointAt(0));
     if(old!=null && old.intValue()!=composed.codePointAt(0))throw new IllegalStateException("Ambiguous composition");
    }
    prefix+=right;
   }
  }
  if(classStart>=0)System.out.println("class\t"+classStart+"\t"+classEnd+"\t"+lastClass);
  for(var pair:pairs.entrySet())System.out.println("compose\t"+Long.toHexString(pair.getKey()>>21)+"\t"+Long.toHexString(pair.getKey()&0x1fffff)+"\t"+Integer.toHexString(pair.getValue()));
  for(int cp:marks){String mark=new String(Character.toChars(cp));samples.add("A"+mark+"\u0301");samples.add(mark+"A"+mark+"\u0327");}
  var random=new Random(0x4e4643L);int[] starters={0x41,0x65,0x1100,0x1161,0x11a8,0xac00,0x1f600,0x4e2d,0xd800,0xdc00};
  for(int n=0;n<4096;n++) {
   StringBuilder text=new StringBuilder();int length=1+random.nextInt(24);
   for(int i=0;i<length;i++) {
    int kind=random.nextInt(4);int cp=kind==0?starters[random.nextInt(starters.length)]:kind==1?decomposed.get(random.nextInt(decomposed.size())):marks.get(random.nextInt(marks.size()));
    text.appendCodePoint(cp);
   }
   samples.add(text.toString());
  }
  samples.add("");samples.add("  e\u0301😀  ");samples.add("ﬁＡ");samples.add("\r\n\u0000");samples.add("A\u0305\u0301");
  for(String text:samples)System.out.println("vector\t"+hexUnits(text)+"\t"+hexUnits(form(text,Normalizer.Form.NFC)));
 }
}
'''


def literals(value):
    return ',\n'.join('            ' + json.dumps(value[index:index + 4000]) for index in range(0, len(value), 4000))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='ft-cash-nfc-') as directory:
        source = Path(directory) / 'CashNfcOracle.java'
        source.write_text(JAVA)
        output = subprocess.run(['java', '--add-exports', 'java.base/jdk.internal.icu.lang=ALL-UNNAMED', str(source)],
            check=True, text=True, capture_output=True).stdout
    decompositions, compositions, classes, samples = [], [], [], []
    for line in output.splitlines():
        kind, *parts = line.split('\t')
        if kind == 'decomp':
            decompositions.append((parts[0], parts[1]))
        elif kind == 'compose':
            compositions.append(parts)
        elif kind == 'class':
            classes.append(tuple(map(int, parts)))
        elif kind == 'vector':
            samples.append([bytes.fromhex(text).decode('utf-16-be', errors='surrogatepass') for text in parts])
        else:
            raise RuntimeError(kind)
    assert len(decompositions) == 2061 and len(compositions) == 941
    decomposition_text = ';'.join(key + ':' + value for key, value in decompositions)
    composition_text = ';'.join(','.join(row) for row in compositions)
    checksum = hashlib.sha256(json.dumps([decompositions, compositions, classes], separators=(',', ':')).encode()).hexdigest()
    data = '''package com.finance.tracker.business.domain

// Generated by scripts/export_cash_nfc.py from the original JVM NFC implementation.
// Mapping SHA-256: CHECKSUM
internal object CashNfcData {
    private val decompositions: Map<Int, IntArray> by lazy {
        val encoded = listOf(
DECOMPOSITIONS
        ).joinToString("")
        encoded.split(';').associate { item ->
            val (key, value) = item.split(':')
            key.toInt(16) to value.split(',').map { it.toInt(16) }.toIntArray()
        }
    }
    private val compositions: Map<Long, Int> by lazy {
        val encoded = listOf(
COMPOSITIONS
        ).joinToString("")
        encoded.split(';').associate { item ->
            val parts = item.split(',').map { it.toInt(16) }
            ((parts[0].toLong() shl 21) or parts[1].toLong()) to parts[2]
        }
    }
    private val classes = intArrayOf(
CLASSES
    )

    fun appendDecomposition(code: Int, output: MutableList<Int>) {
        val values = decompositions[code]
        if (values == null) output.add(code) else for (value in values) output.add(value)
    }

    fun compose(first: Int, second: Int): Int? = compositions[(first.toLong() shl 21) or second.toLong()]

    fun combiningClass(code: Int): Int {
        var low = 0
        var high = classes.size / 3 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                code < classes[mid * 3] -> high = mid - 1
                code > classes[mid * 3 + 1] -> low = mid + 1
                else -> return classes[mid * 3 + 2]
            }
        }
        return 0
    }
}
'''.replace('CHECKSUM', checksum).replace('DECOMPOSITIONS', literals(decomposition_text)).replace('COMPOSITIONS', literals(composition_text)).replace('CLASSES', '\n'.join('        ' + ', '.join(map(str, row)) + ',' for row in classes))
    payload = json.dumps(samples, ensure_ascii=True, separators=(',', ':'))
    chunks = [json.dumps(payload[index:index + 3000], ensure_ascii=True).replace('$', r'\$') for index in range(0, len(payload), 3000)]
    vectors = ('package com.finance.tracker.business.domain\n\n'
        '// Generated by scripts/export_cash_nfc.py using the original JVM Normalizer.Form.NFC.\n'
        'internal object CashNfcOriginalVectors {\n'
        f'    const val count = {len(samples)}\n'
        '    val json: String by lazy { listOf(\n' + ',\n'.join('        ' + chunk for chunk in chunks) + '\n    ).joinToString("") }\n}\n')
    for target, expected in ((TARGET, data), (VECTORS, vectors)):
        if args.check:
            if not target.exists() or target.read_text() != expected:
                raise RuntimeError(f'NFC artifact is stale: {target.relative_to(ROOT)}')
        else:
            target.write_text(expected)
    print(f'{len(decompositions)} decompositions, {len(compositions)} compositions, {len(classes)} combining-class ranges, {len(samples)} NFC samples; mapping {checksum}')


if __name__ == '__main__':
    main()
