package com.nsure.collections;

import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Due dates by calendar rules (NS-SH04-001). A premium paid by debit order falls due on the mandate's debit day on or
 * after the period starts, moved to the next working day of the business calendar (Operations > Workflow: working days
 * and holidays) when it lands on a weekend or holiday. Other premiums fall due when their period starts. Demo defaults
 * standing in for the insurer's approved calendar rules (D02/D10): {@code nsure.collections.debit-day-rule}.
 */
@Component
public class DueDateCalendar {

    private static final Map<String, DayOfWeek> DAYS = Map.of("MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY,
            "WED", DayOfWeek.WEDNESDAY, "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY,
            "SUN", DayOfWeek.SUNDAY);

    private final EntityManager em;
    private final boolean debitDayRule;

    public DueDateCalendar(EntityManager em, @Value("${nsure.collections.debit-day-rule:true}") boolean debitDayRule) {
        this.em = em;
        this.debitDayRule = debitDayRule;
    }

    /** The due date of the period starting {@code start} for this policy. */
    public LocalDate dueDate(String policyRef, LocalDate start) {
        if (!debitDayRule) {
            return start;
        }
        Integer debitDay = em.createQuery("select m.debitDay from CollectionMandate m where m.activePolicyRef = :ref"
                        + " and m.channel = :debit", Integer.class)
                .setParameter("ref", policyRef).setParameter("debit", CollectionMandate.DEBIT_ORDER)
                .getResultStream().findFirst().orElse(null);
        if (debitDay == null) {
            return start;
        }
        LocalDate due = start.withDayOfMonth(Math.min(debitDay, start.lengthOfMonth()));
        if (due.isBefore(start)) {
            LocalDate next = start.plusMonths(1);
            due = next.withDayOfMonth(Math.min(debitDay, next.lengthOfMonth()));
        }
        return nextWorkingDay(due);
    }

    /** {@code day} itself when it is a working day, otherwise the next one (at most a month ahead). */
    public LocalDate nextWorkingDay(LocalDate day) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select working_days, holidays from wf_calendar where id = 1")
                .getResultList();
        Set<DayOfWeek> working = Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY);
        Set<String> holidays = Set.of();
        if (!rows.isEmpty()) {
            Object[] row = rows.get(0);
            if (row[0] != null && !row[0].toString().isBlank()) {
                working = Arrays.stream(row[0].toString().split(",")).map(String::trim).map(DAYS::get)
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            }
            if (row[1] != null) {
                holidays = Arrays.stream(row[1].toString().split(",")).map(String::trim).collect(Collectors.toSet());
            }
        }
        LocalDate d = day;
        for (int i = 0; i < 31 && (!working.contains(d.getDayOfWeek()) || holidays.contains(d.toString())); i++) {
            d = d.plusDays(1);
        }
        return d;
    }
}
