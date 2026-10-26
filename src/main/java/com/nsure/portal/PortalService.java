package com.nsure.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.InsuranceApplication;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.InsuranceProduct;
import com.nsure.portfolio.PortfolioViews;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.web.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortalService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final PlatformSecurityContext security;

    public PortalService(EntityManager em, PlatformSecurityContext security) {
        this.em = em;
        this.security = security;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile() {
        AppUser user = requirePortalUser();
        CustomerAccount customer = customerFor(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.getUsername());
        body.put("customerRef", customer.getRef());
        body.put("name", customer.getName());
        body.put("email", customer.getEmail());
        body.put("phone", customer.getPhone());
        body.put("status", customer.getStatus());
        body.put("kycVerified", "Verified".equals(customer.getStatus()));
        body.put("policies", customer.getPolicyCount());
        return body;
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ProductView> products() {
        requirePortalUser();
        return em.createQuery(
                        "select e from InsuranceProduct e order by e.displayOrder asc, e.id asc",
                        InsuranceProduct.class)
                .getResultList()
                .stream()
                .map(product -> new PortfolioViews.ProductView(
                        product.getName(), product.getIcon(), product.getColor(), product.getDescription(),
                        product.getCover(), product.getPremium(), product.getLiveCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> myPolicies() {
        AppUser user = requirePortalUser();
        CustomerAccount customer = customerFor(user);
        return em.createQuery(
                        "select e from InsurancePolicy e where e.customerName = :name order by e.displayOrder asc, e.id desc",
                        InsurancePolicy.class)
                .setParameter("name", customer.getName())
                .getResultList()
                .stream()
                .map(row -> new PortfolioViews.CoverView(
                        row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(),
                        row.getPremium(), row.getStatus(), row.getRecordDate()))
                .toList();
    }

    @Transactional
    public Map<String, Object> purchase(JsonNode body) {
        AppUser user = requirePortalUser();
        CustomerAccount customer = customerFor(user);
        if (!"Verified".equals(customer.getStatus())) {
            throw new IllegalArgumentException("KYC must be verified before you can buy a policy.");
        }
        String productName = requiredText(body, "product");
        InsuranceProduct product = em.createQuery(
                        "select e from InsuranceProduct e where e.name = :name", InsuranceProduct.class)
                .setParameter("name", productName)
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Product " + productName + " not found"));

        int amount = moneyDigits(product.getCover());
        int premium = moneyDigits(product.getPremium());
        String today = LocalDate.now().format(DISPLAY);

        InsuranceApplication application = new InsuranceApplication();
        application.setRef(nextRef("APP-", 4, InsuranceApplication.class));
        application.setCustomerName(customer.getName());
        application.setProduct(product.getName());
        application.setAmount(amount);
        application.setPremium(premium);
        application.setStatus("Issued");
        application.setRecordDate(today);
        application.setDisplayOrder(0);
        em.persist(application);

        InsurancePolicy policy = new InsurancePolicy();
        policy.setRef(nextRef("POL-", 5, InsurancePolicy.class));
        policy.setCustomerName(customer.getName());
        policy.setProduct(product.getName());
        policy.setAmount(amount);
        policy.setPremium(premium);
        policy.setStatus("In force");
        policy.setRecordDate(today);
        policy.setDisplayOrder(0);
        em.persist(policy);

        customer.setPolicyCount(customer.getPolicyCount() + 1);
        customer.setProduct(product.getName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getRef());
        result.put("policyId", policy.getRef());
        result.put("product", product.getName());
        result.put("amount", amount);
        result.put("premium", premium);
        result.put("status", policy.getStatus());
        return result;
    }

    private AppUser requirePortalUser() {
        AppUser user = security.authenticatedUser();
        if (!user.isCustomerPortal()) {
            throw new NoAuthorizationException("Only a customer portal account can use these endpoints.");
        }
        if (user.getCustomerRef() == null || user.getCustomerRef().isBlank()) {
            throw new NoAuthorizationException("This portal account is not linked to a customer record.");
        }
        return user;
    }

    private CustomerAccount customerFor(AppUser user) {
        List<CustomerAccount> rows = em.createQuery(
                        "select e from CustomerAccount e where e.ref = :ref", CustomerAccount.class)
                .setParameter("ref", user.getCustomerRef())
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Customer " + user.getCustomerRef() + " not found");
        }
        return rows.get(0);
    }

    private <T> String nextRef(String prefix, int width, Class<T> type) {
        int max = 0;
        for (String ref : em.createQuery("select e.ref from " + type.getSimpleName() + " e", String.class).getResultList()) {
            max = Math.max(max, Integer.parseInt(ref.substring(prefix.length())));
        }
        return prefix + String.format("%0" + width + "d", max + 1);
    }

    private static int moneyDigits(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.asText().trim();
    }
}
