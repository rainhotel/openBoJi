package com.boji.app.model

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProgressTest {
    private val sunday = LocalDate.parse("2026-10-18")
    @Test fun weeklyCountUsesActualDaysNotRowsOrNotes() {
        val goal = WeeklyGoal(id="g", minimumMinutes=20)
        val rows = listOf(
            LifeRecord(id="1", date="2026-10-14", minutes=45, goalId="g"),
            LifeRecord(id="2", date="2026-10-18", minutes=40, goalId="g"),
            LifeRecord(id="3", date="2026-10-18", minutes=30, goalId="g"),
            LifeRecord(id="4", date="2026-10-12", minutes=15, goalId="g"),
            LifeRecord(id="5", date="2026-10-11", minutes=30, goalId="g"),
        )
        assertEquals(2, Progress.value(goal, rows + rows.first(), sunday))
        assertEquals(0, Progress.value(goal.copy(minimumMinutes=0), listOf(LifeRecord(minutes=0, goalId="g")), LocalDate.now()))
    }
    @Test fun minutesUseActualWeekAndMatchingDomain() {
        val goal = WeeklyGoal(id="study", domain=Domain.LEARNING, mode=GoalMode.MINUTES, target=90)
        val rows = listOf(
            LifeRecord(id="1", domain=Domain.LEARNING, date="2026-10-13", minutes=45, goalId="study"),
            LifeRecord(id="2", domain=Domain.LEARNING, date="2026-10-18", minutes=30, goalId="study"),
            LifeRecord(id="3", domain=Domain.LEARNING, date="2026-10-19", minutes=60, goalId="study"),
            LifeRecord(id="4", domain=Domain.TRAINING, date="2026-10-18", minutes=60, goalId="study"),
        )
        assertEquals(75, Progress.value(goal, rows, sunday))
        assertEquals(60, Progress.value(goal, rows, sunday.plusDays(1)))
    }
    @Test fun moneyIsExactAndTransfersDoNotBecomeExpenses() {
        assertEquals(101, Amounts.parseFen("1.01"))
        assertEquals("0.10", Amounts.yuan(10))
        val rows = listOf(
            LifeRecord(domain=Domain.FINANCE, amountFen=2800),
            LifeRecord(domain=Domain.FINANCE, amountFen=1000, moneyKind=MoneyKind.REFUND),
            LifeRecord(domain=Domain.FINANCE, amountFen=9000, moneyKind=MoneyKind.INCOME),
            LifeRecord(domain=Domain.FINANCE, amountFen=9000, moneyKind=MoneyKind.TRANSFER),
        )
        assertEquals(1800, Progress.netExpense(rows))
    }
    @Test fun invalidMoneyCannotSilentlyRound() {
        for (text in listOf("1.001", "-1", "NaN", "1e9", "")) assertTrue(runCatching { Amounts.parseFen(text) }.isFailure)
    }
}
