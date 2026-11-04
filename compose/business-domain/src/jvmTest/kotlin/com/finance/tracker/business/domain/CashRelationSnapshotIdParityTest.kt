package com.finance.tracker.business.domain

import java.math.BigInteger
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.*

class CashRelationSnapshotIdParityTest {
    @Test fun arbitraryUtf16AndCollisionProbesMatchOriginalJvmIdentifier() {
        fun original(reference:String,used:MutableSet<Long>):Long {
            val digest=MessageDigest.getInstance("SHA-256").digest(reference.toByteArray(Charsets.UTF_8))
            var candidate=BigInteger(1,digest.copyOfRange(0,8)).and(BigInteger.TWO.pow(63).subtract(BigInteger.ONE)).toLong()
            if(candidate<=0) candidate=1
            while(candidate in used) candidate=if(candidate<Long.MAX_VALUE) candidate+1 else 1
            used+=candidate
            return candidate
        }
        val random=Random(884211)
        val references=listOf("","parent:账户","preview:😀","\ud800", "\udc00", "a\ud800b")+
            List(1000) {buildString {repeat(random.nextInt(1,60)) {append(random.nextInt(65536).toChar())}}}
        for(reference in references) {
            val nativeUsed=mutableSetOf<Long>();val sharedUsed=mutableSetOf<Long>()
            repeat(3) {assertEquals(original(reference,nativeUsed),cashRelationSnapshotId(reference,sharedUsed))}
            assertEquals(nativeUsed,sharedUsed)
        }
    }
}
