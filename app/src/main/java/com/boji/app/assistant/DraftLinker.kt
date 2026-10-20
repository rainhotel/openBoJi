package com.boji.app.assistant

import com.boji.app.model.*

/** Linking happens locally; no ungranted history needs to be sent to a provider. */
internal object DraftLinker {
    fun link(batch: DraftBatch, state: AppState, original: String, attachment: String? = null): DraftBatch = batch.copy(records=batch.records.map { record ->
        val available=(state.plans + batch.plans).filter { it.domain == record.domain && it.date == record.date && it.status !in setOf(PlanStatus.SKIPPED,PlanStatus.ARCHIVED) }
        val explicit=record.planId?.let { id -> available.find { it.id == id } }
        val title=record.title.replace(Regex("[\\s0-9分钟次组]+"),"")
        val titleMatches=available.filter { title.isNotBlank() && (it.title.contains(title,true) || title.contains(it.title,true)) }
        val body=Regex("胸|背|腿|肩|卧推|深蹲|跑步|练拳|读书|Redis|英语",RegexOption.IGNORE_CASE)
        val hints=body.findAll(record.title).map { it.value }.toSet()
        val hinted=available.filter { plan -> hints.any { plan.title.contains(it,true) } }
        val chosen=explicit ?: titleMatches.singleOrNull() ?: hinted.singleOrNull()
        record.copy(planId=chosen?.id,goalId=chosen?.goalId ?: record.goalId,
            note=(record.note+if(original.isBlank()) "" else "\n原话：$original").trim().take(10000),
            source=if(attachment == null) record.source else "图片",sourceId=attachment?.let { "image:$it" } ?: record.sourceId,
            attachments=(record.attachments+listOfNotNull(attachment)).distinct())
    })
}
