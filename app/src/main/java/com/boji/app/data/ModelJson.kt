package com.boji.app.data

import com.boji.app.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Strict decoding matters: a malformed restore must never silently become a zero-value record. */
internal object ModelJson {
    fun record(value: LifeRecord) = JSONObject().apply {
        put("id", value.id); put("domain", value.domain.name); put("title", value.title)
        put("date", value.date); put("minutes", value.minutes); put("amountFen", value.amountFen)
        put("moneyKind", value.moneyKind.name); put("category", value.category); put("note", value.note)
        putNullable("goalId", value.goalId); putNullable("planId", value.planId)
        put("source", value.source); putNullable("sourceId", value.sourceId); put("createdAt", value.createdAt)
    }

    fun record(value: JSONObject) = LifeRecord(
        id = value.string("id"), domain = enumValueOf(value.string("domain")), title = value.string("title"),
        date = value.string("date"), minutes = value.int("minutes"), amountFen = value.long("amountFen"),
        moneyKind = enumValueOf(value.string("moneyKind")), category = value.string("category"),
        note = value.string("note"), goalId = value.nullableString("goalId"), planId = value.nullableString("planId"),
        source = value.string("source"), sourceId = value.nullableString("sourceId"), createdAt = value.string("createdAt"),
    )

    fun plan(value: Plan) = JSONObject().apply {
        put("id", value.id); put("title", value.title); put("domain", value.domain.name); put("date", value.date)
        put("time", value.time); put("expectedMinutes", value.expectedMinutes); put("status", value.status.name)
        putNullable("goalId", value.goalId); putNullable("seriesId", value.seriesId)
    }

    fun plan(value: JSONObject) = Plan(
        id = value.string("id"), title = value.string("title"), domain = enumValueOf(value.string("domain")),
        date = value.string("date"), time = value.string("time"), expectedMinutes = value.int("expectedMinutes"),
        status = enumValueOf(value.string("status")), goalId = value.nullableString("goalId"), seriesId = value.nullableString("seriesId"),
    )

    fun goal(value: WeeklyGoal) = JSONObject().apply {
        put("id", value.id); put("title", value.title); put("domain", value.domain.name); put("mode", value.mode.name)
        put("target", value.target); put("minimumMinutes", value.minimumMinutes); put("archived", value.archived)
    }

    fun goal(value: JSONObject) = WeeklyGoal(
        id = value.string("id"), title = value.string("title"), domain = enumValueOf(value.string("domain")),
        mode = enumValueOf(value.string("mode")), target = value.int("target"),
        minimumMinutes = value.int("minimumMinutes"), archived = value.boolean("archived"),
    )

    fun view(value: SavedView) = JSONObject().apply {
        put("name", value.name); putNullable("domain", value.domain?.name); put("thisWeek", value.thisWeek)
    }

    fun view(value: JSONObject) = SavedView(
        name = value.string("name"), domain = value.nullableString("domain")?.let { enumValueOf<Domain>(it) },
        thisWeek = value.boolean("thisWeek"),
    )

    fun message(value: ChatMessage) = JSONObject().apply {
        put("id", value.id); put("user", value.user); put("text", value.text)
    }

    fun message(value: JSONObject) = ChatMessage(value.string("id"), value.boolean("user"), value.string("text"))

    fun draft(value: DraftBatch) = JSONObject().apply {
        put("id", value.id)
        put("records", JSONArray().apply { value.records.forEach { put(record(it)) } })
        put("plans", JSONArray().apply { value.plans.forEach { put(plan(it)) } })
    }

    fun draft(value: JSONObject) = DraftBatch(
        id = value.string("id"), records = value.array("records").objects().map { record(it) },
        plans = value.array("plans").objects().map { plan(it) },
    )

    fun settings(value: ModelSettings, cipher: CredentialCipher) = JSONObject().apply {
        put("enabled", value.enabled); put("activeIndex", value.activeIndex); put("includeHistory", value.includeHistory)
        put("includeFinance", value.includeFinance); put("role", value.role); put("monthlyBudgetFen", value.monthlyBudgetFen)
        put("memory", value.memory)
        put("connections", JSONArray().apply {
            value.connections.forEach { connection ->
                put(JSONObject().apply {
                    put("name", connection.name); put("endpoint", connection.endpoint); put("model", connection.model)
                    put("apiKeyCipher", cipher.encrypt(connection.apiKey)); put("supportsImages", connection.supportsImages)
                })
            }
        })
    }

    fun settings(value: JSONObject, cipher: CredentialCipher) = ModelSettings(
        enabled = value.boolean("enabled"), activeIndex = value.int("activeIndex"),
        includeHistory = value.boolean("includeHistory"), includeFinance = value.boolean("includeFinance"),
        role = value.string("role"), monthlyBudgetFen = value.long("monthlyBudgetFen"),
        memory = value.optString("memory", ""),
        connections = value.array("connections").objects().map { connection ->
            ModelConnection(
                name = connection.string("name"), endpoint = connection.string("endpoint"), model = connection.string("model"),
                apiKey = cipher.decrypt(connection.string("apiKeyCipher")), supportsImages = connection.boolean("supportsImages"),
            )
        },
    )

    fun JSONObject.string(name: String): String = get(name).let {
        require(it is String) { "$name 必须是文字" }; it
    }

    fun JSONObject.nullableString(name: String): String? {
        require(has(name)) { "缺少 $name" }
        return if (isNull(name)) null else string(name)
    }

    fun JSONObject.long(name: String): Long = get(name).let {
        require(it is Long || it is Int) { "$name 必须是整数" }; (it as Number).toLong()
    }

    fun JSONObject.int(name: String): Int = long(name).let {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$name 超出范围" }; it.toInt()
    }

    fun JSONObject.boolean(name: String): Boolean = get(name).let {
        require(it is Boolean) { "$name 必须是开关值" }; it
    }

    fun JSONObject.array(name: String): JSONArray = get(name).let {
        require(it is JSONArray) { "$name 必须是列表" }; it
    }

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
        get(index).let { require(it is JSONObject) { "列表项目格式不正确" }; it }
    }

    private fun JSONObject.putNullable(name: String, value: Any?) { put(name, value ?: JSONObject.NULL) }
}
