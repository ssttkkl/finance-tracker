package com.finance.tracker.backend

import kotlinx.serialization.json.JsonElement

internal data class PythonJsonText(val text:String,val rawSurrogates:Set<Int>)
internal fun categoryDeleteBody(bytes:ByteArray):JsonElement=categoryDeleteRequest(pythonJsonText(bytes)).value

/** json.loads(bytes) uses BOM/zero detection and surrogatepass for the detected Unicode codec. */
internal fun pythonJsonText(input:ByteArray):PythonJsonText {
    fun starts(vararg prefix:Int)=input.size>=prefix.size && prefix.indices.all {(input[it].toInt() and 255)==prefix[it]}
    val encoding=when {
        starts(0,0,254,255) ->"utf-32-be"
        starts(255,254,0,0) ->"utf-32-le"
        starts(239,187,191) ->"utf-8-sig"
        starts(254,255) ->"utf-16-be"
        starts(255,254) ->"utf-16-le"
        input.size>=4 && input[0]==0.toByte() ->if(input[1]!=0.toByte()) "utf-16-be" else "utf-32-be"
        input.size>=4 && input[1]==0.toByte() ->if(input[2]!=0.toByte() || input[3]!=0.toByte()) "utf-16-le" else "utf-32-le"
        input.size==2 && input[0]==0.toByte() ->"utf-16-be"
        input.size==2 && input[1]==0.toByte() ->"utf-16-le"
        else ->"utf-8"
    }
    val bytes=if(encoding=="utf-8-sig") input.copyOfRange(3,input.size) else input
    fun byte(i:Int)=bytes[i].toInt() and 255
    fun failure(start:Int,end:Int,reason:String):Nothing {
        val codec=if(encoding=="utf-8-sig") "utf-8" else encoding
        val position=if(end-start==1) "byte 0x${byte(start).toString(16).padStart(2,'0')} in position $start" else "bytes in position $start-${end-1}"
        throw CategoryFailure("'$codec' codec can't decode $position: $reason")
    }
    val rawSurrogates=mutableSetOf<Int>()
    val text=buildString {
        var i=when {encoding.startsWith("utf-32") && (starts(0,0,254,255) || starts(255,254,0,0)) ->4
            encoding.startsWith("utf-16") && (starts(254,255) || starts(255,254)) ->2
            else ->0}
        while(i<bytes.size) {
            when {
                encoding.startsWith("utf-16") -> {
                    if(i+2>bytes.size) failure(i,bytes.size,"truncated data")
                    val code=if(encoding.endsWith("le")) byte(i) or (byte(i+1) shl 8) else (byte(i) shl 8) or byte(i+1)
                    if(code in 0xd800..0xdbff && i+4<=bytes.size) {
                        val low=if(encoding.endsWith("le")) byte(i+2) or (byte(i+3) shl 8) else (byte(i+2) shl 8) or byte(i+3)
                        if(low in 0xdc00..0xdfff) {append(code.toChar());append(low.toChar());i+=4;continue}
                    }
                    if(code in 0xd800..0xdfff) rawSurrogates+=length
                    append(code.toChar());i+=2
                }
                encoding.startsWith("utf-32") -> {
                    if(i+4>bytes.size) failure(i,bytes.size,"truncated data")
                    var code=0L
                    for(j in 0..3) code=code or (byte(i+j).toLong() shl (if(encoding.endsWith("le")) j*8 else (3-j)*8))
                    if(code>0x10ffff) failure(i,i+4,"code point not in range(0x110000)")
                    if(code in 0xd800..0xdfff) rawSurrogates+=length
                    appendCodePoint(code.toInt());i+=4
                }
                else -> {
                    val first=byte(i)
                    val length=when(first) {in 0..127 ->1;in 194..223 ->2;in 224..239 ->3;in 240..244 ->4;else ->failure(i,i+1,"invalid start byte")}
                    if(first==237 && i+1<bytes.size && byte(i+1)>=160 && byte(i+1)<=191 &&
                        (i+2>=bytes.size || byte(i+2) !in 128..191)) failure(i,i+1,"invalid continuation byte")
                    var code=first and when(length) {1 ->127;2 ->31;3 ->15;else ->7}
                    for(j in 1 until length) {
                        if(i+j>=bytes.size) failure(i,bytes.size,"unexpected end of data")
                        val next=byte(i+j)
                        if(next !in 128..191 || j==1 && (first==224 && next<160 || first==240 && next<144 || first==244 && next>143)) failure(i,i+j,"invalid continuation byte")
                        code=(code shl 6) or (next and 63)
                    }
                    if(code in 0xd800..0xdfff) rawSurrogates+=this.length
                    appendCodePoint(code);i+=length
                }
            }
        }
    }
    return PythonJsonText(text,rawSurrogates)
}
