package com.boji.app.assistant

import com.boji.app.model.AppState
import com.boji.app.model.Domain
import com.boji.app.model.LifeRecord
import com.boji.app.model.ModelConnection
import com.boji.app.model.ModelSettings
import com.boji.app.model.MoneyKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantTest {
    private val today = LocalDate.of(2026, 10, 18)
    private val connection = ModelConnection(endpoint = "https://example.com/v1", model = "test", apiKey = "SECRET_KEY")

    @Test fun `offline input creates three exact drafts without saving`() {
        val draft = LocalDraftParser.parse("今天练拳40分钟，晚饭28元，学Redis半小时", today)!!
        assertEquals(3, draft.records.size)
        assertEquals(listOf(Domain.TRAINING, Domain.FINANCE, Domain.LEARNING), draft.records.map { it.domain })
        assertEquals(40, draft.records[0].minutes)
        assertEquals(2800L, draft.records[1].amountFen)
        assertEquals(MoneyKind.EXPENSE, draft.records[1].moneyKind)
        assertEquals(30, draft.records[2].minutes)
        assertEquals(listOf("2026-10-18"), draft.records.map { it.date }.distinct())
        assertTrue(draft.plans.isEmpty())
    }

    @Test fun `money is exact integer cents and extra precision is rejected`() {
        assertEquals(29L, LocalDraftParser.parse("买水0.29元", today)!!.records.single().amountFen)
        assertNull(LocalDraftParser.parse("买水1.999元", today))
        assertNull(LocalDraftParser.parse("买水-5元", today))
        assertNull(LocalDraftParser.parse("晚饭28元或30元", today))
        assertEquals(MoneyKind.REFUND, LocalDraftParser.parse("退款28元", today)!!.records.single().moneyKind)
    }

    @Test fun `plans do not invent a clock time or duration`() {
        val plan = LocalDraftParser.parse("明天学习Redis", today)!!.plans.single()
        assertEquals("2026-10-19", plan.date)
        assertEquals("", plan.time)
        assertEquals(0, plan.expectedMinutes)
        val timed = LocalDraftParser.parse("明天19:00练拳40分钟", today)!!.plans.single()
        assertEquals("19:00", timed.time)
        assertEquals(40, timed.expectedMinutes)
        assertNull(LocalDraftParser.parse("下周练拳3次", today))
    }

    @Test fun `ambiguous and hypothetical input never produces offline drafts`() {
        listOf("运动一下", "学习Redis", "练拳大概40分钟", "比如练拳40分钟", "没有练拳40分钟", "练拳和学习共40分钟", "记录学习半分钟", "10月17日练拳40分钟", "三天前练拳40分钟")
            .forEach { assertNull(it, LocalDraftParser.parse(it, today)) }
        assertEquals(90, LocalDraftParser.parse("学习Redis一个半小时", today)!!.records.single().minutes)
        assertEquals(90, LocalDraftParser.parse("学习Redis1.5小时", today)!!.records.single().minutes)
    }

    @Test fun `default cloud request contains no local history or other keys`() {
        val state = cloudState().copy(records = listOf(
            LifeRecord(domain = Domain.FINANCE, title = "PRIVATE_FINANCE", amountFen = 800),
            LifeRecord(domain = Domain.LEARNING, title = "PRIVATE_LEARNING"),
        ))
        val request = AssistantProtocol.request("hello", state, today, null, "image/jpeg")
        assertFalse(request.contains("PRIVATE_FINANCE"))
        assertFalse(request.contains("PRIVATE_LEARNING"))
        assertFalse(request.contains("SECRET_KEY"))
        assertFalse(request.contains("备用密钥"))
        assertEquals(2, JSONObject(request).getJSONArray("messages").length())
    }

    @Test fun `history scope excludes finances unless separately permitted`() {
        val state = cloudState().copy(records = listOf(
            LifeRecord(domain = Domain.FINANCE, title = "PRIVATE_FINANCE", amountFen = 800),
            LifeRecord(domain = Domain.LEARNING, title = "ALLOWED_LEARNING"),
        ), settings = cloudState().settings.copy(includeHistory = true))
        val request = AssistantProtocol.request("hello", state, today, null, "image/jpeg")
        assertFalse(request.contains("PRIVATE_FINANCE"))
        assertTrue(request.contains("ALLOWED_LEARNING"))
        val allowed = AssistantProtocol.request("hello", state.copy(settings = state.settings.copy(includeFinance = true)), today, null, "image/jpeg")
        assertTrue(allowed.contains("PRIVATE_FINANCE"))
    }

    @Test fun `malformed cloud values are rejected instead of coerced`() {
        val valid = """{"text":"核对后保存","records":[{"domain":"FINANCE","title":"午饭","date":"2026-10-18","minutes":0,"amountFen":2901,"moneyKind":"EXPENSE"}],"plans":[]}"""
        assertEquals(2901L, AssistantProtocol.parseDraft(valid).draft!!.records.single().amountFen)
        listOf(
            valid.replace("2901", "2901.5"),
            valid.replace("2901", "\"2901\""),
            valid.replace("2026-10-18", "2026-02-30"),
            valid.replace("EXPENSE", "UNKNOWN"),
        ).forEach { body -> assertTrue(runCatching { AssistantProtocol.parseDraft(body) }.isFailure) }
    }

    @Test fun `connection error never echoes credentials or raw provider body`() = runBlocking {
        val client = AssistantClient(AssistantTransport { _, key, _ -> error("bad token $key PRIVATE_SERVER_RESPONSE") }, { today })
        val reply = client.respond("hello", cloudState())
        assertFalse(reply.text.contains("SECRET_KEY"))
        assertFalse(reply.text.contains("PRIVATE_SERVER_RESPONSE"))
        assertNull(reply.draft)
        assertFalse(reply.success)
    }

    @Test fun `active model slot controls request and image support`() = runBlocking {
        var sent = ""
        val client = AssistantClient(AssistantTransport { endpoint, key, body ->
            sent = "$endpoint|$key|$body"
            """{"choices":[{"message":{"content":"你好"}}]}"""
        }, { today })
        val state = cloudState().copy(settings = cloudState().settings.copy(activeIndex = 1))
        assertEquals("你好", client.respond("hello", state).text)
        assertTrue(sent.startsWith("https://second.example.com/v1/chat/completions|备用密钥|"))
        assertTrue(sent.contains("second-model"))
        sent = ""
        assertNull(client.respond("图片", state, byteArrayOf(1)).draft)
        assertEquals("", sent)
    }

    @Test fun `endpoint rejects cleartext credentials and query strings`() {
        assertEquals("https://example.com/v1/chat/completions", AssistantClient.normalizeEndpoint("https://example.com/v1/"))
        assertEquals("https://example.com/v1/chat/completions", AssistantClient.normalizeEndpoint("https://example.com"))
        assertNull(AssistantClient.normalizeEndpoint("http://example.com/v1"))
        assertNull(AssistantClient.normalizeEndpoint("https://key:secret@example.com/v1"))
        assertNull(AssistantClient.normalizeEndpoint("https://example.com/v1?key=SECRET"))
        assertNull(AssistantClient.normalizeEndpoint("https://example.com/v1#token"))
    }

    private fun cloudState() = AppState(settings = ModelSettings(enabled = true, connections = listOf(connection,
        ModelConnection(endpoint = "https://second.example.com/v1", model = "second-model", apiKey = "备用密钥"))))
}
