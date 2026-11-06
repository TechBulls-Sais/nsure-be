package com.nsure.portfolio;

import static com.nsure.portfolio.PdfReport.STAMP;
import static com.nsure.portfolio.PdfReport.money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The policy schedule as an A4 PDF: the one document a policyholder keeps for a policy. It sets out who is
 * insured, for what, at what premium, who is paid, and the terms that decide whether a claim is paid. It is
 * issued once per policy (see {@link PolicyDocumentService}), so it holds only what stays true for the life of
 * the policy: no running history, review notes or next-due dates.
 */
final class PolicySchedulePdf {

    /** Stored with each schedule; bump it when the layout changes so older schedules are replaced on start-up. */
    static final int LAYOUT_VERSION = 2;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private PolicySchedulePdf() {
    }

    /** {@code premiumMonths}: months one premium pays for; 0 means yearly (as {@link PolicyWorkflow} treats it). */
    static byte[] render(PortfolioViews.CoverView p, CustomerAccount customer, InsuranceProduct product,
            int premiumMonths, String issuedBy) {
        int months = premiumMonths <= 0 ? 12 : premiumMonths;
        String per = months == 1 ? "a month" : months == 12 ? "a year" : "every " + months + " months";
        LocalDate coverStart = parse(p.coverStartDate());
        int waitingDays = product == null ? 0 : product.getWaitingPeriodDays();
        int graceDays = product == null ? 0 : product.getGracePeriodDays();

        PdfReport pdf = new PdfReport("Policy schedule " + p.id(), "Policy schedule",
                p.id() + " · " + p.product() + " · issued " + LocalDateTime.now().format(STAMP) + " by " + issuedBy);

        pdf.keyFacts(new String[][] {
                {"Policy number", p.id()},
                {"Policyholder", p.name()},
                {"Product", p.product() + (blank(p.tier()) ? "" : " · " + p.tier())},
                {"Sum assured", money(p.amount())},
                {"Premium", p.premium() == null ? null : money(p.premium()) + " " + per},
                {"Cover starts", format(coverStart)},
        });

        // 1 ── the policy
        pdf.section("1. Your policy");
        pdf.row("Policy number", p.id());
        pdf.row("Product", p.product());
        pdf.row("Cover option", blank(p.tier()) ? null : p.tier());
        pdf.row("Status when issued", p.status()
                + (blank(p.lapseDate()) ? "" : " (lapsed on " + format(parse(p.lapseDate())) + ")"));
        pdf.row("Policy taken out", sameStyle(p.date()));
        pdf.row("Cover starts", format(coverStart));
        pdf.row("Term", "Cover continues for as long as premiums are paid. It ends if the policy lapses or is cancelled.");
        pdf.row("Sum assured", money(p.amount()) + ", the amount paid on a valid claim"
                + (p.lives().size() > 1 ? " on the main life; each covered life's own cover is shown in section 4." : "."));
        pdf.row("Pricing", blank(p.rateTableVersion()) ? null : "Priced from rate table version " + p.rateTableVersion()
                + ", on each covered life's age at entry.");

        // 2 ── the premium
        pdf.section("2. Your premium");
        pdf.row("Premium", p.premium() == null ? null : money(p.premium()) + " " + per);
        pdf.row("Paid by", blank(p.paymentMethod()) ? null : p.paymentMethod());
        pdf.row("When it is due", due(p, coverStart, months));
        pdf.row("Grace period", graceDays > 0
                ? graceDays + " days. If a premium is missed, cover carries on for " + graceDays + " days after the "
                        + "due date. If it is still unpaid then, the policy lapses and cover stops."
                : "None. If a premium is missed, the policy lapses and cover stops.");

        // 3 ── the policyholder
        pdf.section("3. Policyholder");
        pdf.row("Name", p.name());
        if (customer != null) {
            pdf.row("Customer number", customer.getRef());
            pdf.row("Date of birth", customer.getDateOfBirth() == null ? null : customer.getDateOfBirth().format(DAY));
            pdf.row("Identity document", blank(customer.getPoiType()) ? null
                    : customer.getPoiType() + (blank(customer.getPoiNumber()) ? "" : " ending " + last4(customer.getPoiNumber())));
            pdf.row("Email", customer.getEmail());
            pdf.row("Phone", customer.getPhone());
        }

        // 4 ── who is covered
        pdf.section("4. Who is covered");
        if (p.lives().isEmpty()) {
            pdf.paragraph("The policyholder, " + p.name() + ", for a sum assured of " + money(p.amount()) + ".");
        } else {
            List<String[]> rows = new ArrayList<>();
            BigDecimal monthly = BigDecimal.ZERO;
            for (PortfolioViews.LifeCoverView life : p.lives()) {
                rows.add(new String[] {life.fullName(), life.relationship(), String.valueOf(life.age()),
                        money(life.coverAmount()), money(life.monthlyPremium())});
                monthly = monthly.add(life.monthlyPremium() == null ? BigDecimal.ZERO : life.monthlyPremium());
            }
            rows.add(new String[] {"Total", p.lives().size() + (p.lives().size() == 1 ? " life" : " lives"), "", "",
                    money(monthly)});
            pdf.table(new String[] {"Name", "Relationship", "Age at entry", "Cover", "Premium a month"},
                    new float[] {0.32f, 0.2f, 0.14f, 0.17f, 0.17f}, rows, true);
        }

        // 5 ── who is paid
        pdf.section("5. Beneficiaries");
        if (p.beneficiaries().isEmpty()) {
            pdf.paragraph("No beneficiaries were named when this schedule was issued. A claim is paid as the policy "
                    + "wording sets out for a policy with no named beneficiary.");
        } else {
            List<String[]> rows = new ArrayList<>();
            for (PortfolioViews.BeneficiaryView b : p.beneficiaries()) {
                rows.add(new String[] {b.fullName(), b.relationship(),
                        blank(b.payoutMethod()) ? "Not given"
                                : b.payoutMethod() + (blank(b.accountRef()) ? "" : " ending " + last4(b.accountRef())),
                        b.primary() ? "Yes" : "No"});
            }
            pdf.table(new String[] {"Name", "Relationship", "Paid to", "Primary"},
                    new float[] {0.34f, 0.2f, 0.32f, 0.14f}, rows, false);
            pdf.note("Account numbers are shown by their last four digits only.");
        }

        // 6 ── what is covered
        if (product != null && (!blank(product.getSummary()) || !blank(product.getBenefits()))) {
            pdf.section("6. What is covered");
            if (!blank(product.getSummary())) {
                pdf.paragraph(product.getSummary());
            }
            if (!blank(product.getBenefits())) {
                for (String benefit : product.getBenefits().split("\\R")) {
                    if (!benefit.isBlank()) {
                        pdf.bullet(benefit.trim());
                    }
                }
            }
        }

        // 7 ── the terms that decide a claim
        pdf.section((product != null && (!blank(product.getSummary()) || !blank(product.getBenefits())) ? "7" : "6")
                + ". Important terms");
        if (waitingDays > 0) {
            pdf.bullet("Waiting period: " + waitingDays + " days from the cover start date"
                    + (coverStart == null ? "" : ", until " + format(coverStart.plusDays(waitingDays))) + ". A claim for "
                    + "an event in the waiting period may not be paid.");
        }
        pdf.bullet("Premiums must be paid by their due date, or within the grace period, for cover to continue.");
        pdf.bullet("A lapsed or cancelled policy cannot be restarted. To be covered again, take out a new policy; it "
                + "gets its own policy number and its own schedule.");
        pdf.bullet("To claim, or to change your beneficiaries or contact details, contact Nsure and quote policy number "
                + p.id() + ".");
        pdf.bullet("This schedule is issued once for this policy. Keep it with the policy wording; the policy wording "
                + "and the product terms apply in full.");
        return pdf.bytes();
    }

    /**
     * "On the 5th of every month", "Every year on 5 November": from the next due date (or the cover start if none
     * is set yet), since every premium moves the due date on by one period.
     */
    private static String due(PortfolioViews.CoverView p, LocalDate coverStart, int months) {
        LocalDate anchor = parse(p.nextPremiumDue());
        if (anchor == null) {
            anchor = coverStart;
        }
        if (anchor == null) {
            return null;
        }
        if (months == 12) {
            return "Every year on " + anchor.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)) + ".";
        }
        int day = anchor.getDayOfMonth();
        String suffix = day >= 11 && day <= 13 ? "th"
                : switch (day % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return "On the " + day + suffix + (months == 1 ? " of every month." : ", every " + months + " months.");
    }

    private static LocalDate parse(String isoDate) {
        if (blank(isoDate)) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.length() > 10 ? isoDate.substring(0, 10) : isoDate);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The record date ("05 Nov 2026") in this document's style ("5 November 2026"); anything else as it is. */
    private static String sameStyle(String recordDate) {
        if (blank(recordDate)) {
            return null;
        }
        try {
            return LocalDate.parse(recordDate.trim(), DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
                    .format(DAY);
        } catch (RuntimeException e) {
            return recordDate;
        }
    }

    private static String format(LocalDate day) {
        return day == null ? null : day.format(DAY);
    }

    /** Identity and account numbers are shown by their last four characters only. */
    private static String last4(String value) {
        String v = value.trim();
        return v.length() <= 4 ? v : v.substring(v.length() - 4);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
