package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.web.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;

    public PortfolioService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CustomerView> customers() {
        return list(CustomerAccount.class).stream().map(this::customer).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> applications() {
        return list(InsuranceApplication.class).stream().map(this::cover).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> policies() {
        return list(InsurancePolicy.class).stream().map(this::cover).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ClaimView> claims() {
        return list(InsuranceClaim.class).stream().map(this::claim).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ProductView> products() {
        return list(InsuranceProduct.class).stream()
                .map(product -> new PortfolioViews.ProductView(
                        product.getName(), product.getIcon(), product.getColor(), product.getDescription(),
                        product.getCover(), product.getPremium(), product.getLiveCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.SchemeView> schemes() {
        return list(GroupScheme.class).stream()
                .map(scheme -> new PortfolioViews.SchemeView(
                        scheme.getRef(), scheme.getName(), scheme.getSector(), scheme.getInitials(),
                        scheme.getMembers(), scheme.getPremium(), scheme.getRenewal(), scheme.getColor()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> receipts() {
        return list(PremiumReceipt.class).stream().map(this::cover).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ConfigView> productConfigs() {
        return list(ProductConfiguration.class).stream().map(this::config).toList();
    }

    @Transactional
    public PortfolioViews.CustomerView createCustomer(JsonNode body) {
        CustomerAccount row = new CustomerAccount();
        row.setRef(nextRef("CUS-", 4, CustomerAccount.class));
        row.setName(text(body, "name"));
        row.setEmail(text(body, "email"));
        row.setPhone(text(body, "phone"));
        row.setProduct("");
        row.setPolicyCount(0);
        row.setStatus("Pending KYC");
        row.setColor("green");
        row.setDisplayOrder(0);
        em.persist(row);
        return customer(row);
    }

    @Transactional
    public PortfolioViews.CoverView createApplication(JsonNode body) {
        InsuranceApplication row = new InsuranceApplication();
        fillCover(row, body, "APP-", 4, "Under review");
        em.persist(row);
        return cover(row);
    }

    @Transactional
    public PortfolioViews.CoverView updateApplication(String ref, JsonNode body) {
        InsuranceApplication row = one(InsuranceApplication.class, ref, "Application");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return cover(row);
    }

    @Transactional
    public PortfolioViews.CoverView createPolicy(JsonNode body) {
        InsurancePolicy row = new InsurancePolicy();
        fillCover(row, body, "POL-", 5, "In force");
        em.persist(row);
        List<CustomerAccount> matches = em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", row.getCustomerName())
                .setMaxResults(1)
                .getResultList();
        if (!matches.isEmpty()) {
            CustomerAccount account = matches.get(0);
            account.setPolicyCount(account.getPolicyCount() + 1);
        }
        return cover(row);
    }

    @Transactional
    public PortfolioViews.ClaimView createClaim(JsonNode body) {
        InsuranceClaim row = new InsuranceClaim();
        fillCover(row, body, "CLM-", 4, "Evidence needed");
        row.setPremium(0);
        row.setClaimType(text(body, "type"));
        row.setDescription(optional(body, "description"));
        row.setEventDate(optional(body, "eventDate"));
        em.persist(row);
        return claim(row);
    }

    @Transactional
    public PortfolioViews.ClaimView updateClaim(String ref, JsonNode body) {
        InsuranceClaim row = one(InsuranceClaim.class, ref, "Claim");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return claim(row);
    }

    @Transactional
    public PortfolioViews.CoverView updateReceipt(String ref, JsonNode body) {
        PremiumReceipt row = one(PremiumReceipt.class, ref, "Receipt");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return cover(row);
    }

    @Transactional
    public PortfolioViews.ConfigView saveConfig(String ref, JsonNode body) {
        ProductConfiguration row = ref == null
                ? new ProductConfiguration()
                : one(ProductConfiguration.class, ref, "Product configuration");
        if (ref == null) {
            row.setRef(nextRef("CFG-", 3, ProductConfiguration.class));
            row.setDisplayOrder(0);
            row.setVersionNo(1);
        } else {
            row.setVersionNo(row.getVersionNo() + 1);
        }
        row.setName(text(body, "name"));
        row.setCode(text(body, "code"));
        row.setDomain(defaultText(body, "domain", "Individual Life"));
        row.setCurrency(defaultText(body, "currency", "BWP"));
        row.setDescription(defaultText(body, "description", row.getName()));
        row.setEffectiveOn(defaultText(body, "effective", "2026-10-01"));
        row.setMinAge(body.path("minAge").asInt(18));
        row.setMaxAge(body.path("maxAge").asInt(65));
        row.setWaitingMonths(body.path("waiting").asInt(3));
        row.setCoverAmount(body.path("cover").asInt(500000));
        row.setPremiumAmount(body.path("premium").asInt(420));
        row.setFrequency(defaultText(body, "frequency", "Monthly"));
        row.setGraceDays(body.path("grace").asInt(30));
        row.setUnderwriting(defaultText(body, "underwriting", "Manual review"));
        row.setBenefits(join(body.get("benefits")));
        row.setChannels(join(body.get("channels")));
        row.setWording(defaultText(body, "wording", "Standard life wording · v1.0"));
        row.setApprovals(body.path("approvals").asBoolean(false));
        row.setStatus(defaultText(body, "status", "Draft"));
        if (ref == null) {
            em.persist(row);
        }
        return config(row);
    }

    private void fillCover(CoverRecord row, JsonNode body, String prefix, int width, String defaultStatus) {
        row.setRef(nextRef(prefix, width, row.getClass()));
        row.setCustomerName(text(body, "name"));
        row.setProduct(text(body, "product"));
        row.setAmount(body.path("amount").asInt());
        row.setPremium(body.path("premium").asInt(0));
        row.setStatus(body.hasNonNull("status") ? body.get("status").asText() : defaultStatus);
        row.setRecordDate(body.hasNonNull("date") ? body.get("date").asText() : LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
    }

    private <T> String nextRef(String prefix, int width, Class<T> type) {
        int max = 0;
        for (String ref : em.createQuery("select e.ref from " + type.getSimpleName() + " e", String.class).getResultList()) {
            max = Math.max(max, Integer.parseInt(ref.substring(prefix.length())));
        }
        return prefix + String.format("%0" + width + "d", max + 1);
    }

    private <T> List<T> list(Class<T> type) {
        return em.createQuery(
                "select e from " + type.getSimpleName() + " e order by e.displayOrder asc, e.id desc", type)
                .getResultList();
    }

    private <T> T one(Class<T> type, String ref, String label) {
        List<T> rows = em.createQuery(
                "select e from " + type.getSimpleName() + " e where e.ref = :ref", type)
                .setParameter("ref", ref)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(label + " " + ref + " not found");
        }
        return rows.get(0);
    }

    private PortfolioViews.CustomerView customer(CustomerAccount row) {
        return new PortfolioViews.CustomerView(
                row.getRef(), row.getName(), row.getEmail(), row.getPhone(),
                row.getProduct() == null ? "" : row.getProduct(), row.getPolicyCount(), row.getStatus(), row.getColor());
    }

    private PortfolioViews.CoverView cover(CoverRecord row) {
        return new PortfolioViews.CoverView(
                row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(),
                row.getPremium(), row.getStatus(), row.getRecordDate());
    }

    private PortfolioViews.ClaimView claim(InsuranceClaim row) {
        return new PortfolioViews.ClaimView(
                row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(), row.getPremium(),
                row.getStatus(), row.getRecordDate(), row.getClaimType(), row.getDescription(), row.getEventDate());
    }

    private PortfolioViews.ConfigView config(ProductConfiguration row) {
        return new PortfolioViews.ConfigView(
                row.getRef(), row.getName(), row.getCode(), row.getDomain(), row.getCurrency(), row.getDescription(),
                row.getEffectiveOn(), row.getMinAge(), row.getMaxAge(), row.getWaitingMonths(), row.getCoverAmount(),
                row.getPremiumAmount(), row.getFrequency(), row.getGraceDays(), row.getUnderwriting(),
                split(row.getBenefits()), split(row.getChannels()), row.getWording(), row.isApprovals(),
                row.getStatus(), row.getVersionNo());
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private static String join(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        node.forEach(item -> {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(item.asText());
        });
        return joined.toString();
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.asText().trim();
    }

    private static String optional(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static String defaultText(JsonNode body, String field, String fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText().trim();
    }
}
