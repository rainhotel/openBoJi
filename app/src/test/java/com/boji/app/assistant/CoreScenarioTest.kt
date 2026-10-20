package com.boji.app.assistant

import com.boji.app.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CoreScenarioTest {
    @Test fun originalSevenTimesIsNotConvertedIntoSetsOrMinutes() {
        val batch=LocalDraftParser.parse("今天练胸练了七次",LocalDate.of(2026,10,20))!!
        val row=batch.records.single()
        assertEquals(7,row.count);assertEquals("次",row.countUnit);assertEquals(0,row.minutes)
        assertEquals("组",LocalDraftParser.parse("今天练胸7组",LocalDate.now())!!.records.single().countUnit)
    }
    @Test fun localTaskAssociationRequiresAMatchAndNeverGuessesAmongTwoCandidates() {
        val chest=Plan(id="chest",title="胸部训练",domain=Domain.TRAINING)
        val leg=Plan(id="leg",title="腿部训练",domain=Domain.TRAINING)
        val raw=DraftBatch(records=listOf(LifeRecord(title="练胸",count=7)))
        val linked=DraftLinker.link(raw,AppState(plans=listOf(chest,leg)),"今天练胸7次","test-photo.jpg")
        assertEquals("chest",linked.records.single().planId)
        assertEquals(listOf("test-photo.jpg"),linked.records.single().attachments)
        assertTrue(linked.records.single().note.contains("今天练胸7次"))
        assertNull(DraftLinker.link(raw,AppState(plans=listOf(chest,chest.copy(id="chest2"))),"",null).records.single().planId)
        assertNull(DraftLinker.link(raw,AppState(plans=listOf(leg)),"",null).records.single().planId)
    }
    @Test fun plannedExamAndRevisionUseStableLocalParentReferences() {
        val payload="""{"plans":[{"ref":"exam","domain":"LEARNING","title":"英语考试","date":"2026-11-03","time":"09:00","deadline":"2026-11-03T09:00","priority":0,"expectedMinutes":120},{"domain":"LEARNING","title":"英语复习","date":"2026-11-01","time":"19:00","expectedMinutes":30,"parentId":"exam","reminderMinutes":15}],"records":[]}"""
        val batch=AssistantProtocol.parseDraft(payload).draft!!
        assertEquals(batch.plans.first().id,batch.plans.last().parentId)
        assertEquals(0,batch.plans.first().priority)
        assertTrue(batch.records.isEmpty())
    }
    @Test fun explicitRememberCreatesADraftWithoutInventingAnActivity() {
        val reply=LocalDraftParser.reply("记住：周二晚上不能学习",AppState(),LocalDate.now())
        assertTrue(reply.draft!!.records.isEmpty());assertEquals("周二晚上不能学习",reply.draft!!.memories.single().text)
    }
    @Test fun deepSeekConnectionCheckReplaysTheWholeAssistantToolMessage() = runBlocking {
        assertTrue(DeepSeekConnection.isOfficial(DeepSeekConnection.ENDPOINT))
        assertFalse(DeepSeekConnection.isOfficial("https://api.deepseek.com.example.org/chat/completions"))
        var calls=0
        DeepSeekConnection.check(DeepSeekConnection.default("TEST_ONLY"),AssistantTransport { endpoint,_,body ->
            assertEquals("https://api.deepseek.com/chat/completions",endpoint)
            if(++calls==1) """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"required-reasoning","tool_calls":[{"id":"call1","type":"function","function":{"name":"boji_connection_check","arguments":"{}"}}]}}]}"""
            else {
                assertTrue(body.contains("required-reasoning"));assertTrue(body.contains("tool_call_id"))
                assertEquals("",JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
                """{"choices":[{"finish_reason":"stop","message":{"content":"连接成功"}}]}"""
            }
        })
        assertEquals(2,calls)
    }
}
