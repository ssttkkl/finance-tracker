"""Freeze the existing JVM GB18030 decoder and independent strict text-decoding samples."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'compose/business-domain/src/commonMain/kotlin/com/finance/tracker/business/domain/StatementGb18030Data.kt'
VECTORS = ROOT / 'compose/business-domain/src/commonTest/kotlin/com/finance/tracker/business/domain/StatementTextCharsetOriginalVectors.kt'
JAVA = r'''
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
public class StatementCharsetOracle {
 static final Charset GB=Charset.forName("GB18030");
 static final CharsetDecoder DECODER=GB.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
 static final ByteBuffer INPUT=ByteBuffer.allocate(4);
 static final CharBuffer OUTPUT=CharBuffer.allocate(2);
 static int one(byte[] bytes) {
  DECODER.reset();INPUT.clear();INPUT.put(bytes).flip();OUTPUT.clear();
  var result=DECODER.decode(INPUT,OUTPUT,true);
  if(result.isError() || result.isOverflow() || INPUT.hasRemaining())return -1;
  result=DECODER.flush(OUTPUT);
  if(result.isError() || result.isOverflow())return -1;
  OUTPUT.flip();
  if(OUTPUT.remaining()==1)return OUTPUT.get();
  if(OUTPUT.remaining()==2){char a=OUTPUT.get(),b=OUTPUT.get();if(Character.isSurrogatePair(a,b))return Character.toCodePoint(a,b);}
  return -1;
 }
 static byte[] four(int pointer) {
  int b4=pointer%10+0x30;pointer/=10;int b3=pointer%126+0x81;pointer/=126;
  return new byte[]{(byte)(pointer/10+0x81),(byte)(pointer%10+0x30),(byte)b3,(byte)b4};
 }
 static String text(byte[] bytes) {
  for(var charset:new Charset[]{StandardCharsets.UTF_8,GB}) {
   try {
    String value=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
     .decode(ByteBuffer.wrap(bytes)).toString();
    return value.startsWith("\ufeff")?value.substring(1):value;
   } catch(CharacterCodingException ignored){}
  }
  return null;
 }
 static void vector(byte[] bytes) {
  String value=text(bytes);StringBuilder units=new StringBuilder();
  if(value!=null)for(char c:value.toCharArray())units.append(String.format(Locale.ROOT,"%04x",(int)c));
  System.out.println("vector\t"+HexFormat.of().formatHex(bytes)+"\t"+(value==null?"-":units.toString()));
 }
 public static void main(String[] args) {
  for(int b=0;b<256;b++){int value=one(new byte[]{(byte)b});if(value!=(b<128?b:-1))throw new IllegalStateException("Single-byte contract changed: "+b);}
  StringBuilder two=new StringBuilder();
  for(int b1=0x81;b1<=0xfe;b1++)for(int b2=0x40;b2<=0xfe;b2++)if(b2!=0x7f) {
   int cp=one(new byte[]{(byte)b1,(byte)b2});
   two.append(String.format(Locale.ROOT,"%06x",cp<0?0xffffff:cp));
   if((b1*190+b2)%19==0)vector(new byte[]{(byte)b1,(byte)b2});
  }
  System.out.println("two\t"+two);
  int start=-1,last=-1,unicode=-1,previous=-1;var boundaries=new LinkedHashSet<Integer>();
  for(int pointer=0;pointer<126*10*126*10;pointer++) {
   int cp=one(four(pointer));
   if(start>=0 && (cp<0 || cp!=previous+1)) {
    System.out.println("range\t"+start+"\t"+last+"\t"+unicode);
    for(int p:new int[]{start-1,start,start+1,last-1,last,last+1})if(p>=0 && p<126*10*126*10)boundaries.add(p);
    start=-1;
   }
   if(cp>=0){if(start<0){start=pointer;unicode=cp;}last=pointer;previous=cp;}
  }
  if(start>=0)throw new IllegalStateException("Expected invalid terminal GB18030 range");
  for(int pointer:boundaries)vector(four(pointer));
  for(int b=0;b<256;b++)vector(new byte[]{(byte)b});
  var random=new Random(0x1b18030L);
  for(int n=0;n<1500;n++)vector(four(random.nextInt(126*10*126*10)));
  for(int n=0;n<1500;n++){byte[] bytes=new byte[random.nextInt(17)];random.nextBytes(bytes);vector(bytes);}
  for(var value:new String[]{"", "账单😀", "\ufeff", "\ufeff\ufeff原文", "a\u0000b"})vector(value.getBytes(StandardCharsets.UTF_8));
 }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='ft-statement-charset-') as directory:
        source = Path(directory) / 'StatementCharsetOracle.java'
        source.write_text(JAVA)
        output = subprocess.run(['java', str(source)], check=True, text=True, capture_output=True).stdout
    two, ranges, samples = None, [], []
    for line in output.splitlines():
        kind, *parts = line.split('\t')
        if kind == 'two':
            two = parts[0]
        elif kind == 'range':
            ranges.append(tuple(map(int, parts)))
        elif kind == 'vector':
            raw, units = parts
            value = None if units == '-' else bytes.fromhex(units).decode('utf-16-be', errors='strict')
            samples.append([raw, value])
        else:
            raise RuntimeError(kind)
    assert two is not None and len(two) == 23940 * 6
    assert ranges and ranges[-1][2] + ranges[-1][1] - ranges[-1][0] == 0x10ffff
    checksum = hashlib.sha256((two + repr(ranges)).encode()).hexdigest()
    chunks = [two[index:index + 6000] for index in range(0, len(two), 6000)]
    content = '''package com.finance.tracker.business.domain

// Generated by scripts/export_statement_text_charset.py from the existing strict JVM GB18030 decoder.
// Mapping SHA-256: CHECKSUM
internal object StatementGb18030Data {
    private val twoByte: IntArray by lazy {
        val encoded = listOf(
CHUNKS
        ).joinToString("")
        IntArray(23_940) { index -> encoded.substring(index * 6, index * 6 + 6).toInt(16).let {
            if (it == 0xffffff) -1 else it
        } }
    }
    private val fourByteRanges = intArrayOf(
RANGES
    )

    fun twoByte(first: Int, second: Int): Int {
        val trail = second - 0x40 - if (second > 0x7f) 1 else 0
        return twoByte[(first - 0x81) * 190 + trail]
    }

    fun fourByte(pointer: Int): Int {
        var low = 0
        var high = fourByteRanges.size / 3 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = fourByteRanges[mid * 3]
            val end = fourByteRanges[mid * 3 + 1]
            when {
                pointer < start -> high = mid - 1
                pointer > end -> low = mid + 1
                else -> return fourByteRanges[mid * 3 + 2] + pointer - start
            }
        }
        return -1
    }
}
'''.replace('CHECKSUM', checksum).replace('CHUNKS', ',\n'.join('            "' + chunk + '"' for chunk in chunks)).replace('RANGES', '\n'.join('        ' + ', '.join(map(str, row)) + ',' for row in ranges))
    payload = json.dumps(samples, ensure_ascii=True, separators=(',', ':'))
    chunks = [json.dumps(payload[index:index + 3000], ensure_ascii=True).replace('$', r'\$') for index in range(0, len(payload), 3000)]
    vectors = ('package com.finance.tracker.business.domain\n\n'
        '// Generated by scripts/export_statement_text_charset.py using the original UTF-8 / GB18030 strict fallback.\n'
        'internal object StatementTextCharsetOriginalVectors {\n'
        '    val json: String by lazy { listOf(\n' + ',\n'.join('        ' + c for c in chunks) + '\n    ).joinToString("") }\n}\n')
    for target, expected in ((TARGET, content), (VECTORS, vectors)):
        if args.check:
            if not target.exists() or target.read_text() != expected:
                raise RuntimeError(f'Charset artifact is stale: {target.relative_to(ROOT)}')
        else:
            target.write_text(expected)
    print(f'23,940 two-byte slots, {len(ranges)} four-byte ranges, {len(samples)} original text-decoder samples; mapping {checksum}')


if __name__ == '__main__':
    main()
