package com.finance.tracker.business.domain

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.*

class CashProjectionTopologyParityTest {
    @Test fun largeIdentifierComponentsMatchIndependentReachability() {
        val random=Random(81557)
        repeat(500) {
            val nodes=(0 until random.nextInt(2,20)).map {BigInteger("900719925474099312345678901234567890")+BigInteger.valueOf(it.toLong())}
            val edges=List(random.nextInt(0,30)) {nodes[random.nextInt(nodes.size)] to nodes[random.nextInt(nodes.size)]}
            val components=CashProjectionComponents(nodes.shuffled(random))
            for((a,b) in edges) components.union(a,b)
            val remaining=nodes.toMutableSet()
            val expected=mutableListOf<List<BigInteger>>()
            while(remaining.isNotEmpty()) {
                val reached=mutableSetOf(remaining.minOrNull()!!)
                var size:Int
                do {
                    size=reached.size
                    for((a,b) in edges) if(a in reached || b in reached) {reached+=a;reached+=b}
                } while(size!=reached.size)
                remaining.removeAll(reached)
                expected+=reached.sorted()
            }
            assertEquals(expected,components.groups())
            for(group in expected) for(id in group) assertEquals(group.first(),components.find(id))
            val tree=nodes.drop(1).mapIndexed {index,node ->nodes[random.nextInt(index+1)] to node}
            assertEquals(nodes.first(),cashProjectionRoot(nodes.shuffled(random),tree.shuffled(random)))
            assertFailsWith<CashProjectionDomainFailure> {cashProjectionRoot(nodes,tree+tree.first())}
        }
    }
}
