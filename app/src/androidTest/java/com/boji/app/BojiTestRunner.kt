package com.boji.app

import android.app.Activity
import androidx.test.runner.AndroidJUnitRunner
import android.os.Bundle
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import java.time.LocalDate

/** Explicit manual-QA entry point, separate from the JUnit suite; never packaged in the app. */
class BojiTestRunner:AndroidJUnitRunner() {
    private var fixture=false
    private var cleanup=false
    override fun onCreate(arguments:Bundle?) {
        fixture=arguments?.getString("manualFixture")=="true"
        cleanup=arguments?.getString("cleanup")=="true"
        super.onCreate(arguments)
    }
    override fun onStart() {
        if(!fixture){super.onStart();return}
        val store=BojiStore(targetContext)
        try {
            val ids=setOf("qa-walk-chest","qa-walk-study","qa-walk-project")
            if(cleanup) {
                store.snapshot().records.filter {it.id=="qa-walk-record"}.forEach {store.deleteRecord(it.id)}
                ids.forEach(store::deletePlan)
                if(store.snapshot().draft?.id?.startsWith("qa-walk-draft")==true)store.savePendingDraft(null)
                store.snapshot().views.firstOrNull {it.name=="QA_WALK_VIEW"}?.let {store.deleteView(it.name)}
            } else {
                require(store.snapshot().draft==null){"Existing draft must be kept; fixture not installed"}
                require(store.snapshot().plans.none {it.id in ids}){"QA fixture already exists"}
                val today=LocalDate.now()
                store.savePlans(listOf(
                    Plan(id="qa-walk-chest",title="QA Chest",domain=Domain.TRAINING,priority=0,deadline=today.toString()),
                    Plan(id="qa-walk-study",title="QA Study",domain=Domain.LEARNING,priority=1,date=today.plusDays(1).toString(),deadline=today.plusDays(1).toString()),
                    Plan(id="qa-walk-project",title="QA Project",domain=Domain.PROJECT,priority=2,deadline=today.plusDays(2).toString())))
                store.savePendingDraft(DraftBatch(id="qa-walk-draft-${java.util.UUID.randomUUID()}",records=listOf(LifeRecord(id="qa-walk-record",title="练胸",count=7,planId="qa-walk-chest",note="Manual QA fixture"))))
            }
            finish(Activity.RESULT_OK,Bundle().apply {putString("fixture",if(cleanup)"cleaned" else "seeded")})
        } catch(error:Exception) {finish(Activity.RESULT_CANCELED,Bundle().apply {putString("fixtureError",error.message)})}
        finally {store.close()}
    }
}
