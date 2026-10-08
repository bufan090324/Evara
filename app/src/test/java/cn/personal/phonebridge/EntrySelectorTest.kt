package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class EntrySelectorTest {
    private fun node(path:String,l:Int=0,t:Int=0,r:Int=300,b:Int=100,click:Boolean=false,on:Boolean=true)=EntryNode(path,WindowBox(l,t,r,b),click,on)
    private fun ambiguous(matches:List<EntryNode>,nodes:List<EntryNode>) {try{EntrySelector.choose(matches,nodes);fail("Expected ambiguity")}catch(e:BridgeError){assertEquals("AMBIGUOUS_TARGET",e.code)}}
    @Test fun containerAndLabelRepresentOneEntrance() {
        val card=node("0/0",click=true);val text=node("0/0/1",20,20,150,60)
        assertEquals(card.path,EntrySelector.choose(listOf(card,text),listOf(card,text)))
    }
    @Test fun textAndIconWithSharedClickableParentAreOneEntrance() {
        val card=node("0/0",click=true);val text=node("0/0/1",10,10,150,40);val icon=node("0/0/2",180,10,210,40)
        assertEquals(text.path,EntrySelector.choose(listOf(text,icon),listOf(card,text,icon)))
    }
    @Test fun parentAndClickableChildPreferSpecificClickableNode() {
        val card=node("0/0",click=true);val child=node("0/0/1",20,20,150,60,click=true)
        assertEquals(child.path,EntrySelector.choose(listOf(card,child),listOf(card,child)))
    }
    @Test fun twoSeparateCardsStillPause() {
        val first=node("0/0",click=true);val second=node("0/1",0,200,300,300,click=true)
        ambiguous(listOf(first,second),listOf(first,second))
    }
    @Test fun parentCannotMergeTwoDistinctClickableEntrances() {
        val parent=node("0/0",0,0,500,500,click=true)
        val first=node("0/0/1",10,10,150,100,click=true);val second=node("0/0/2",10,200,150,300,click=true)
        ambiguous(listOf(parent,first,second),listOf(parent,first,second))
    }
    @Test fun sameCoordinatesAloneDoNotMergeUnrelatedNodes() {
        val first=node("0/0",click=true);val second=node("0/1",click=true)
        ambiguous(listOf(first,second),listOf(first,second))
    }
    @Test fun similarlyPrefixedPathsAreNotAncestorRelated() {
        val first=node("0/1",click=true);val second=node("0/10",click=true)
        ambiguous(listOf(first,second),listOf(first,second))
    }
    @Test fun disabledDuplicatesDoNotBlockValidEntrance() {
        val first=node("0/0",click=true);val disabled=node("0/1",click=true,on=false)
        assertEquals(first.path,EntrySelector.choose(listOf(first,disabled),listOf(first,disabled)))
    }
    @Test fun ancestorWithoutSpatialContainmentDoesNotMerge() {
        val parent=node("0/0",click=true);val child=node("0/0/1",500,500,600,600,click=true)
        ambiguous(listOf(parent,child),listOf(parent,child))
    }
    @Test fun noMatchAndRepeatedSameNodeAreHandled() {
        assertNull(EntrySelector.choose(emptyList(),emptyList()))
        val n=node("0/0",click=true)
        assertEquals(n.path,EntrySelector.choose(listOf(n,n),listOf(n)))
    }
}
