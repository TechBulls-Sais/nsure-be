package com.nsure.portfolio;

import static com.nsure.portfolio.PdfReport.STAMP;
import static com.nsure.portfolio.PdfReport.money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A policy schedule as an A4 PDF: policyholder, cover, premium, covered lives, beneficiaries, product benefits
 * and history, as they stand at the moment it is generated.
 */
final class PolicySchedulePdf {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private PolicySchedulePdf() {
    }

    static byte[] render(PortfolioViews.CoverView p, CustomerAccount customer, InsuranceProduct product,
            List<PortfolioViews.HistoryView> history, String generatedBy) {
        PdfReport pdf = new PdfReport("Policy schedule " + p.id(), "Policy schedule  " + p.id(),
                "Generated " + LocalDateTime.now().format(STAMP) + " by " + generatedBy
                        + ". A snapshot of the policy at that moment.");

        pdf.section("Policy");
        pdf.row("Policy number", p.id());
        pdf.row("Status", p.status());
        pdf.row("Product", p.product());
        pdf.row("Cover tier", p.tier());
        pdf.row("Sum assured", money(p.amount()));
        pdf.row("Premium", p.premium() == null ? null
                : money(p.premium()) + " " + (blank(p.paymentMethod()) ? "per month" : "per month, paid by " + p.paymentMethod()));
        pdf.row("Priced from", blank(p.rateTableVersion()) ? null : "Rate table version " + p.rateTableVersion());
        pdf.row("Issued", p.date());
        pdf.row("Cover starts", day(p.coverStartDate()));
        pdf.row("Next premium due", day(p.nextPremiumDue()));
        pdf.row("Grace period ends", day(p.gracePeriodEnd()));
        pdf.row("Lapsed on", day(p.lapseDate()));
        if (product != null) {
            pdf.row("Waiting period", product.getWaitingPeriodDays() > 0 ? product.getWaitingPeriodDays() + " days" : null);
            pdf.row("Grace period", product.getGracePeriodDays() > 0 ? product.getGracePeriodDays() + " days" : null);
        }
        pdf.row("Review", blank(p.reviewedBy()) ? null
                : "By " + p.reviewedBy() + (blank(p.reviewNote()) ? "" : ": " + p.reviewNote()));

        pdf.section("Policyholder");
        pdf.row("Name", p.name());
        if (customer != null) {
            pdf.row("Customer number", customer.getRef());
            pdf.row("Date of birth", customer.getDateOfBirth() == null ? null : customer.getDateOfBirth().format(DAY));
            pdf.row("Identity", blank(customer.getPoiType()) ? null
                    : customer.getPoiType() + (blank(customer.getPoiNumber()) ? "" : " ending " + last4(customer.getPoiNumber())));
            pdf.row("Email", customer.getEmail());
            pdf.row("Phone", customer.getPhone());
        }

        if (!p.lives().isEmpty()) {
            pdf.section("Covered lives");
            for (PortfolioViews.LifeCoverView life : p.lives()) {
                pdf.row(life.relationship(), life.fullName() + ", age " + life.age() + " at entry · cover "
                        + money(life.coverAmount()) + " · " + money(life.monthlyPremium()) + " a month");
            }
        }

        pdf.section("Beneficiaries");
        if (p.beneficiaries().isEmpty()) {
            pdf.row("Beneficiaries", "None named");
        }
        for (PortfolioViews.BeneficiaryView b : p.beneficiaries()) {
            pdf.row(b.primary() ? "Primary" : "Beneficiary", b.fullName() + " (" + b.relationship() + ")"
                    + (blank(b.payoutMethod()) ? "" : " · " + b.payoutMethod()
                            + (blank(b.accountRef()) ? "" : " ending " + last4(b.accountRef()))));
        }

        if (product != null && !blank(product.getBenefits())) {
            pdf.section("What is covered");
            pdf.row("Summary", product.getSummary());
            for (String benefit : product.getBenefits().split("\\R")) {
                pdf.row("Benefit", benefit.trim());
            }
        }

        ClaimSummaryPdf.history(pdf, history);

        pdf.section("Important");
        pdf.note("This schedule summarises the policy as recorded by Nsure when it was generated. The policy wording "
                + "and the product terms apply. Cover depends on premiums being paid by their due date or within the "
                + "grace period, and claims in the waiting period may not be paid.");
        return pdf.bytes();
    }

    private static String day(String isoDate) {
        if (blank(isoDate)) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.length() > 10 ? isoDate.substring(0, 10) : isoDate).format(DAY);
        } catch (RuntimeException e) {
            return isoDate;
        }
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
