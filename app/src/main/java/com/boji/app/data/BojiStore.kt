package com.boji.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.boji.app.data.ModelJson.array
import com.boji.app.data.ModelJson.int
import com.boji.app.data.ModelJson.long
import com.boji.app.data.ModelJson.objects
import com.boji.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * All mutations validate the resulting graph and commit in one SQLite transaction.
 * Restore is a merge by stable id/name; any conflicting relationship rolls the whole merge back.
 * Exports contain records, plans, goals, saved views, applied batch ids and monthly budget.
 * They deliberately omit model connections/credentials, chat history and unconfirmed drafts.
 */
class BojiStore(context: Context, databaseName: String = "boji.db") : AutoCloseable {
    private val helper = Database(context.applicationContext, databaseName)
    private val cipher = CredentialCipher()

    @Synchronized
    fun snapshot(): AppState = transaction { db ->
        val data = readData(db)
        AppState(
            loading = false, records = data.records.sortedWith(compareByDescending<LifeRecord> { it.date }.thenByDescending { it.createdAt }),
            plans = data.plans.sortedWith(compareBy<Plan> { it.date }.thenBy { it.time }),
            goals = data.goals, settings = readSettings(db), views = data.views,
            messages = rows(db, "messages", "seq").map { ModelJson.message(it) },
            draft = readMeta(db, PENDING_DRAFT)?.let { ModelJson.draft(JSONObject(it)) },
            memories=readMemories(db),
            taskReceipt=readMeta(db,"task-receipt")?.let {ModelJson.receipt(JSONObject(it))},
        )
    }

    @Synchronized
    fun saveRecord(record: LifeRecord) = updateData { data ->
        val old = data.records.find { it.id == record.id }
        val records = data.records.filterNot { it.id == record.id } + record
        val plans = reconcile(data.plans,records,setOfNotNull(record.planId,old?.planId))
        data.copy(records = records, plans = plans)
    }

    @Synchronized
    fun deleteRecord(id: String) {
        DataValidation.id(id)
        updateData { data ->
            val old = data.records.find { it.id == id }
            data.copy(
                records = data.records.filterNot { it.id == id },
                plans = reconcile(data.plans,data.records.filterNot { it.id == id },setOfNotNull(old?.planId)),
            )
        }
    }

    @Synchronized
    fun savePlan(plan: Plan) = savePlans(listOf(plan))

    /** A single native transaction owns task writes and their undo receipt, even if the model stops later. */
    @Synchronized
    fun writeAgentTasks(runId: String, creates: List<Plan> = emptyList(), changes: List<Pair<Plan,Plan>> = emptyList()): List<Plan> = transaction { db ->
        require(creates.size + changes.size in 1..180) { "一次操作需要 1 到 180 个任务" }
        val data=readData(db)
        DataValidation.unique(creates.map {it.id} + changes.map {it.second.id},"任务")
        require(creates.all {candidate -> data.plans.none {it.id==candidate.id && it!=candidate}}) {"创建不能覆盖已有任务"}
        changes.forEach { (old,new) ->
            require(old.id==new.id && data.plans.find {it.id==old.id}==old) { "任务已被修改，请重新查询后再操作" }
        }
        val resolved=mutableListOf<Plan>()
        val aliases=mutableMapOf<String,String>()
        val incoming=creates.associateBy {it.id}
        val visiting=mutableSetOf<String>()
        // Resolve parents first, including child-first batches and references to an existing duplicate parent.
        fun resolve(source:Plan):String {
            aliases[source.id]?.let {return it}
            require(visiting.add(source.id)) {"任务关联存在循环"}
            val parent=incoming[source.parentId]?.let {resolve(it)} ?: source.parentId
            val candidate=source.copy(title=source.title.trim(),parentId=parent)
            val existing=(data.plans+resolved).find { it.status!=PlanStatus.ARCHIVED &&
                it.title.trim()==candidate.title && it.domain==candidate.domain && it.date==candidate.date && it.time==candidate.time }
            require(existing==null || existing.copy(id=candidate.id)==candidate) {
                "同名同日同时间已有任务但字段不同，请查询该任务并使用 update_tasks 修改"
            }
            val actual=existing?.id ?: candidate.id
            aliases[source.id]=actual;visiting.remove(source.id)
            if(existing==null)resolved.add(candidate)
            return actual
        }
        creates.forEach {resolve(it)}
        val inserted=resolved.toList()
        val after=data.copy(plans=merge(data.plans,inserted+changes.map {it.second}) {it.id})
        DataValidation.data(after)
        val previous=readMeta(db,"task-receipt")?.let {ModelJson.receipt(JSONObject(it))}?.takeIf {it.id==runId}
        if(inserted.isNotEmpty() || changes.isNotEmpty()) {
            val created=(previous?.createdIds.orEmpty()+inserted.map {it.id}).distinct()
            val originals=(previous?.before.orEmpty()+changes.map {it.first}).distinctBy {it.id}.filterNot {it.id in created}
            val affected=(previous?.after.orEmpty().map {it.id}+inserted.map {it.id}+changes.map {it.second.id}).toSet()
            val receipt=TaskReceipt(runId,created,originals,after.plans.filter {it.id in affected})
            persistData(db,data,after)
            putMeta(db,"task-receipt",ModelJson.receipt(receipt).toString())
        } else if(previous==null) {
            // A new request that only finds existing tasks must not offer undo for an unrelated old request.
            db.delete("meta","name = ?",arrayOf("task-receipt"))
        }
        creates.map {candidate -> after.plans.first {it.id==(aliases[candidate.id] ?: candidate.id)}}+changes.map {it.second}
    }

    @Synchronized fun undoAgentTasks() = transaction { db ->
        val receipt=readMeta(db,"task-receipt")?.let {ModelJson.receipt(JSONObject(it))} ?: error("没有可撤销的任务操作")
        val data=readData(db)
        require(receipt.after.all { task -> data.plans.find {it.id==task.id}==task }) { "任务已发生后续变化，请直接编辑任务" }
        require(data.records.none {it.planId in receipt.createdIds} && data.plans.none {it.id !in receipt.createdIds && it.parentId in receipt.createdIds}) {
            "任务已有记录或关联子任务，请直接编辑任务"
        }
        val remaining=data.plans.filterNot {it.id in receipt.createdIds}
        val after=data.copy(plans=merge(remaining,receipt.before) {it.id})
        DataValidation.data(after);persistData(db,data,after)
        db.delete("meta","name = ?",arrayOf("task-receipt"))
    }

    @Synchronized fun attachTaskReceipt(messageId:String) = transaction {db ->
        DataValidation.id(messageId)
        readMeta(db,"task-receipt")?.let {raw ->
            val receipt=ModelJson.receipt(JSONObject(raw))
            putMeta(db,"task-receipt",ModelJson.receipt(receipt.copy(messageId=messageId)).toString())
        }
    }

    @Synchronized
    fun savePlans(plans: List<Plan>, recurrence: String? = null) {
        DataValidation.unique(plans.map { it.id }, "计划")
        transaction { db ->
            val before = readData(db)
            val after = before.copy(plans = merge(before.plans,plans) { it.id })
            DataValidation.data(after)
            recurrence?.let { RecurrenceJson.validate(it,after.goals) }
            persistData(db,before,after)
            recurrence?.let { putMeta(db,RECURRENCE,it) }
        }
    }

    @Synchronized
    fun skipPlan(id: String) {
        DataValidation.id(id)
        updateData { data ->
            require(data.plans.any { it.id == id }) { "计划不存在" }
            require(data.records.none { it.planId == id }) { "已有完成记录，请先删除记录再跳过" }
            data.copy(plans = data.plans.map { if (it.id == id) it.copy(status = PlanStatus.SKIPPED) else it })
        }
    }

    @Synchronized
    fun deletePlan(id: String) {
        DataValidation.id(id)
        transaction { db ->
            val before = readData(db)
            val deleted = before.plans.find { it.id == id }
            val after = before.copy(plans=before.plans.filterNot { it.id == id }.map { if(it.parentId == id) it.copy(parentId=null) else it },
                records=before.records.map { if (it.planId == id) it.copy(planId=null) else it })
            DataValidation.data(after)
            persistData(db,before,after)
            if (deleted?.seriesId != null) {
                val recurrence = JSONObject(readMeta(db,RECURRENCE) ?: RecurrenceJson.EMPTY)
                val exceptions = recurrence.getJSONArray("exceptions")
                if ((0 until exceptions.length()).none { exceptions.getString(it) == id }) exceptions.put(id)
                RecurrenceJson.validate(recurrence.toString(),after.goals)
                putMeta(db,RECURRENCE,recurrence.toString())
            }
        }
    }

    @Synchronized
    fun recurrenceJson(): String = transaction { db -> readMeta(db,RECURRENCE) ?: RecurrenceJson.EMPTY }

    @Synchronized
    fun saveGoal(goal: WeeklyGoal) = updateData { data -> data.copy(goals = merge(data.goals, listOf(goal)) { it.id }) }

    @Synchronized
    fun archiveGoal(id: String) {
        DataValidation.id(id)
        updateData { data -> data.copy(goals = data.goals.map { if (it.id == id) it.copy(archived = true) else it }) }
    }

    @Synchronized
    fun saveSettings(settings: ModelSettings) {
        DataValidation.settings(settings)
        transaction { db -> putMeta(db, SETTINGS, ModelJson.settings(settings, cipher).toString()) }
    }

    @Synchronized fun saveSettingsDraft(settings: ModelSettings?) = transaction { db ->
        if(settings == null) db.delete("meta","name = ?",arrayOf("settings-draft"))
        else { DataValidation.settings(settings.copy(enabled=false)); putMeta(db,"settings-draft",ModelJson.settings(settings,cipher).toString()) }
    }
    @Synchronized fun settingsDraft(): ModelSettings? = transaction { db -> readMeta(db,"settings-draft")?.let { ModelJson.settings(JSONObject(it),cipher) } }

    private fun readMemories(db: SQLiteDatabase): List<MemoryEntry> = readMeta(db,"memories")?.let {
        JSONArray(it).objects().map(ModelJson::memory)
    } ?: readSettings(db).memory.takeIf { it.isNotBlank() }?.chunked(1000)?.mapIndexed { index,text -> MemoryEntry(id="legacy-memory-$index",text=text,source="旧版偏好") }.orEmpty()
    @Synchronized fun saveMemory(memory: MemoryEntry) = transaction { db ->
        DataValidation.memory(memory)
        val rows=merge(readMemories(db),listOf(memory)) { it.id }
        require(rows.size <= 200) { "记忆最多 200 条" }
        putMeta(db,"memories",jsonArray(rows,ModelJson::memory).toString())
    }
    @Synchronized fun deleteMemory(id: String) = transaction { db ->
        putMeta(db,"memories",jsonArray(readMemories(db).filterNot { it.id == id },ModelJson::memory).toString())
    }

    @Synchronized
    fun saveView(view: SavedView) = updateData { data -> data.copy(views = merge(data.views, listOf(view)) { it.name }) }

    @Synchronized
    fun deleteView(name: String) = updateData { data -> data.copy(views = data.views.filterNot { it.name == name }) }

    @Synchronized
    fun saveMessage(message: ChatMessage) {
        DataValidation.message(message)
        transaction { db ->
            val values = ContentValues().apply { put("id", message.id); put("payload", ModelJson.message(message).toString()) }
            if (db.update("messages", values, "id = ?", arrayOf(message.id)) == 0) db.insertOrThrow("messages", null, values)
            db.execSQL("DELETE FROM messages WHERE seq NOT IN (SELECT seq FROM messages ORDER BY seq DESC LIMIT 500)")
        }
    }

    @Synchronized
    fun savePendingDraft(batch: DraftBatch?) {
        transaction { db ->
            if (batch == null) {
                db.delete("meta", "name = ?", arrayOf(PENDING_DRAFT))
            } else {
                batch.memories.forEach(DataValidation::memory)
                require(!batchApplied(db, batch.id)) { "这份草稿已保存" }
                val candidate = withDraft(readData(db), batch)
                DataValidation.data(candidate)
                putMeta(db, PENDING_DRAFT, ModelJson.draft(batch).toString())
            }
        }
    }

    /** Batch id is committed with all facts/plans, so retry and process restart cannot double count. */
    @Synchronized fun applyDraftWithMessage(batch:DraftBatch,message:ChatMessage) = transaction {
        DataValidation.message(message)
        applyDraft(batch);saveMessage(message)
    }

    @Synchronized
    fun applyDraft(batch: DraftBatch) {
        DataValidation.id(batch.id)
        transaction { db ->
            if (!batchApplied(db, batch.id)) {
                val before = readData(db)
                val candidate = withDraft(before, batch)
                DataValidation.data(candidate)
                persistData(db, before, candidate)
                batch.memories.forEach(DataValidation::memory)
                val memories=merge(readMemories(db),batch.memories) { it.id }
                require(memories.size <= 200) { "记忆最多 200 条" }
                if(batch.memories.isNotEmpty()) putMeta(db,"memories",jsonArray(memories,ModelJson::memory).toString())
                db.insertOrThrow("applied_batches", null, ContentValues().apply { put("id", batch.id) })
            }
            val pending = readMeta(db, PENDING_DRAFT)?.let { ModelJson.draft(JSONObject(it)) }
            if (pending?.id == batch.id) db.delete("meta", "name = ?", arrayOf(PENDING_DRAFT))
        }
    }

    @Synchronized
    fun exportJson(): String = transaction { db ->
        val data = readData(db)
        DataValidation.data(data)
        JSONObject().apply {
            put("format", "boji-local-backup"); put("version", 1); put("exportedAt", LocalDateTime.now().toString())
            put("records", jsonArray(data.records, ModelJson::record)); put("plans", jsonArray(data.plans, ModelJson::plan))
            put("goals", jsonArray(data.goals, ModelJson::goal)); put("views", jsonArray(data.views, ModelJson::view))
            put("appliedBatchIds", JSONArray().apply {
                db.query("applied_batches", arrayOf("id"), null, null, null, null, "id").use { cursor ->
                    while (cursor.moveToNext()) put(cursor.getString(0))
                }
            })
            put("monthlyBudgetFen", readSettings(db).monthlyBudgetFen)
            put("memory", readSettings(db).memory)
            put("memories",jsonArray(readMemories(db),ModelJson::memory))
            put("recurrence", JSONObject(readMeta(db,RECURRENCE) ?: RecurrenceJson.EMPTY))
        }.toString()
    }

    @Synchronized
    fun importJson(json: String) {
        require(json.length <= 30_000_000) { "备份文件过大" }
        val root = JSONObject(json)
        require(root.opt("format") == "boji-local-backup" && root.int("version") == 1) { "备份格式不支持" }
        val incoming = StoredData(
            records = root.array("records").objects().map(ModelJson::record),
            plans = root.array("plans").objects().map(ModelJson::plan),
            goals = root.array("goals").objects().map(ModelJson::goal),
            views = root.array("views").objects().map(ModelJson::view),
        )
        DataValidation.data(incoming)
        val batchIds = root.array("appliedBatchIds").let { ids ->
            require(ids.length() <= 100_000) { "备份批次过多" }
            (0 until ids.length()).map { index ->
                val id = ids.get(index)
                require(id is String) { "备份批次格式不正确" }
                DataValidation.id(id); id
            }
        }
        DataValidation.unique(batchIds, "备份批次")
        val budget = root.long("monthlyBudgetFen")
        val memory = if (root.has("memory")) root.get("memory").also { require(it is String && it.length <= 4000) { "记忆格式无效" } } as String else null
        val importedMemories=root.optJSONArray("memories")?.objects()?.map(ModelJson::memory)
        importedMemories?.forEach(DataValidation::memory)
        require(budget in 0..DataValidation.MAX_MONEY_FEN) { "预算超出范围" }
        transaction { db ->
            val before = readData(db)
            val candidate = StoredData(
                records = merge(before.records, incoming.records) { it.id },
                plans = merge(before.plans, incoming.plans) { it.id },
                goals = merge(before.goals, incoming.goals) { it.id },
                views = merge(before.views, incoming.views) { it.name },
            )
            DataValidation.data(candidate)
            val recurrence = RecurrenceJson.merge(readMeta(db,RECURRENCE) ?: RecurrenceJson.EMPTY,
                root.optJSONObject("recurrence")?.toString() ?: RecurrenceJson.EMPTY,candidate.goals)
            persistData(db, before, candidate)
            putMeta(db,RECURRENCE,recurrence)
            batchIds.forEach { id ->
                db.insertWithOnConflict("applied_batches", null, ContentValues().apply { put("id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            val currentSettings = readSettings(db)
            val settings = currentSettings.copy(monthlyBudgetFen = budget,memory=memory ?: currentSettings.memory)
            putMeta(db, SETTINGS, ModelJson.settings(settings, cipher).toString())
            if(importedMemories != null) {
                val merged=merge(readMemories(db),importedMemories) { it.id }; require(merged.size <= 200)
                putMeta(db,"memories",jsonArray(merged,ModelJson::memory).toString())
            }
            // A restored record could make a pending proposal stale; actual records remain untouched.
            db.delete("meta", "name = ?", arrayOf(PENDING_DRAFT))
        }
    }

    @Synchronized
    override fun close() { helper.close() }

    private fun withDraft(data: StoredData, batch: DraftBatch): StoredData {
        DataValidation.id(batch.id)
        require(batch.records.size + batch.plans.size + batch.memories.size in 1..300) { "草稿需包含 1 到 300 项" }
        batch.memories.forEach(DataValidation::memory)
        DataValidation.unique(batch.records.map { it.id }, "草稿记录")
        DataValidation.unique(batch.plans.map { it.id }, "草稿计划")
        require(batch.records.none { record -> data.records.any { it.id == record.id } }) { "草稿记录已存在" }
        require(batch.plans.none { plan -> data.plans.any { it.id == plan.id } }) { "草稿计划已存在" }
        val affected = batch.records.mapNotNull { it.planId }.toSet()
        return data.copy(
            records = data.records + batch.records,
            plans = reconcile(data.plans + batch.plans,data.records + batch.records,affected),
        )
    }

    private fun reconcile(plans: List<Plan>, records: List<LifeRecord>, affected: Set<String>): List<Plan> = plans.map { plan ->
        if(plan.id !in affected || plan.status == PlanStatus.ARCHIVED) plan else {
            val linked=records.filter { it.planId == plan.id }
            plan.copy(status=when { linked.any { it.completesPlan } -> PlanStatus.DONE; linked.isNotEmpty() -> PlanStatus.IN_PROGRESS; else -> PlanStatus.TODO })
        }
    }

    private fun updateData(transform: (StoredData) -> StoredData) {
        transaction { db ->
            val before = readData(db)
            val candidate = transform(before)
            DataValidation.data(candidate)
            persistData(db, before, candidate)
        }
    }

    private fun readData(db: SQLiteDatabase) = StoredData(
        records = rows(db, "records").map(ModelJson::record), plans = rows(db, "plans").map(ModelJson::plan),
        goals = rows(db, "goals").map(ModelJson::goal), views = rows(db, "saved_views").map(ModelJson::view),
    )

    private fun rows(db: SQLiteDatabase, table: String, order: String = "rowid"): List<JSONObject> = buildList {
        db.query(table, arrayOf("payload"), null, null, null, null, order).use { cursor ->
            while (cursor.moveToNext()) add(JSONObject(cursor.getString(0)))
        }
    }

    private fun readSettings(db: SQLiteDatabase): ModelSettings =
        readMeta(db, SETTINGS)?.let { ModelJson.settings(JSONObject(it), cipher) } ?: ModelSettings()

    private fun readMeta(db: SQLiteDatabase, key: String): String? =
        db.query("meta", arrayOf("payload"), "name = ?", arrayOf(key), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun putMeta(db: SQLiteDatabase, key: String, payload: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("name", key); put("payload", payload) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun batchApplied(db: SQLiteDatabase, id: String): Boolean =
        db.query("applied_batches", arrayOf("id"), "id = ?", arrayOf(id), null, null, null).use { it.moveToFirst() }

    private fun persistData(db: SQLiteDatabase, before: StoredData, after: StoredData) {
        syncRows(db, "goals", "id", before.goals, after.goals, { it.id }, ModelJson::goal)
        syncRows(db, "plans", "id", before.plans, after.plans, { it.id }, ModelJson::plan)
        syncRows(db, "records", "id", before.records, after.records, { it.id }, ModelJson::record)
        syncRows(db, "saved_views", "name", before.views, after.views, { it.name }, ModelJson::view)
    }

    private fun <T> syncRows(
        db: SQLiteDatabase, table: String, keyColumn: String, before: List<T>, after: List<T>, key: (T) -> String,
        encode: (T) -> JSONObject,
    ) {
        val old = before.associateBy(key)
        val updated = after.associateBy(key)
        (old.keys - updated.keys).forEach { db.delete(table, "$keyColumn = ?", arrayOf(it)) }
        updated.forEach { (id, value) ->
            if (old[id] != value) {
                val content = ContentValues().apply { put(keyColumn, id); put("payload", encode(value).toString()) }
                if (db.update(table, content, "$keyColumn = ?", arrayOf(id)) == 0) db.insertOrThrow(table, null, content)
            }
        }
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try { val result = block(db); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
    }

    private fun <T> merge(old: List<T>, incoming: List<T>, key: (T) -> String): List<T> =
        (old.associateBy(key) + incoming.associateBy(key)).values.toList()

    private fun <T> jsonArray(values: List<T>, encode: (T) -> JSONObject) = JSONArray().apply { values.forEach { put(encode(it)) } }

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE plans (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE goals (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE saved_views (name TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE meta (name TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE applied_batches (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("暂不支持此数据库版本，请保留备份")
        }
    }

    private companion object { const val SETTINGS = "model-settings"; const val PENDING_DRAFT = "pending-draft"; const val RECURRENCE = "recurrence" }
}
