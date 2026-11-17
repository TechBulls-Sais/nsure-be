package com.nsure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.exception.ApiError;
import com.nsure.common.exception.ApiErrorWriter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key for the money-moving and batch commands (NS-API-002): policy issue and premiums, claims and Care
 * Cash, payments and checkout,
 * collections, finance and GL, reinsurance, distribution, group census and partner requests (paths in
 * {@code nsure.idempotency.paths}). The scope is the signed-in caller, the operation (method and path) and the key.
 * The first request records the hash of its method, path, query and body; repeating it with the same request returns
 * the original answer (header {@code Idempotency-Replayed: true}) without running it again, the same key with a
 * different request is refused with 409 IDEMPOTENCY_CONFLICT, and a repeat while the first is still running gets 409
 * IDEMPOTENCY_IN_PROGRESS. Only successful answers are kept: a refused request changed nothing and may be retried
 * with the same key. Records are kept for {@code nsure.idempotency.retention-hours} (partner retry windows), then
 * purged. Access administration keeps its own command framework. Policy issue keeps its own issue key as well;
 * the filter in front of it adds the request-hash check (a changed request with a used key is a conflict).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED = "Idempotency-Replayed";
    static final String IN_PROGRESS_CODE = "IDEMPOTENCY_IN_PROGRESS";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final List<String> paths;
    private final long retentionHours;

    public IdempotencyFilter(EntityManager em, PlatformTransactionManager transactions, ObjectMapper mapper,
            @Value("${nsure.idempotency.paths:/portfolio/policies,/portfolio/claims,/portfolio/shop/claims,/care-cash,/collections,"
                    + "/finance,/checkout,/payments,/integration/payment-notifications,/reinsurance,/distribution,"
                    + "/group/schemes,/group/census-batches,/grouplife,/credit,/partner}") List<String> paths,
            @Value("${nsure.idempotency.retention-hours:72}") long retentionHours) {
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mapper = mapper;
        this.paths = paths.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
        this.retentionHours = Math.max(1, retentionHours);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!MUTATING.contains(request.getMethod().toUpperCase(Locale.ROOT)) || request.getHeader(HEADER) == null) {
            return true;
        }
        // The body is read here to hash it; multipart and form bodies are parsed by the container from the raw
        // stream, so only JSON (or empty) bodies take part.
        String type = request.getContentType();
        if (type != null && !type.toLowerCase(Locale.ROOT).contains("json")) {
            return true;
        }
        String path = path(request);
        return paths.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER).trim();
        if (!KEY.matcher(key).matches()) {
            ApiErrorWriter.write(response, mapper, 400, ApiError.INVALID_REQUEST,
                    "Idempotency-Key must be 1 to 64 letters, digits or . _ : -");
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String caller = auth.getName();
        String operation = (request.getMethod().toUpperCase(Locale.ROOT) + " " + path(request));
        operation = operation.length() <= 300 ? operation : operation.substring(0, 300);
        String hash = hash(operation, request.getQueryString(), cached.body);

        Long recordId;
        try {
            recordId = begin(caller, operation, key, hash);
        } catch (Answered answered) {
            answered.write(response);
            return;
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(cached, wrapped);
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                String type = wrapped.getContentType();
                tx.executeWithoutResult(s -> em.find(IdempotencyRecord.class, recordId).completed(status, type, body));
                stored = true;
            }
        } finally {
            if (!stored) {
                tx.executeWithoutResult(s -> {
                    IdempotencyRecord record = em.find(IdempotencyRecord.class, recordId);
                    if (record != null) {
                        em.remove(record);
                    }
                });
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** Claims the key for this request, or explains why the request is already answered. */
    private Long begin(String caller, String operation, String key, String hash) throws Answered {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<IdempotencyRecord> earlier = find(caller, operation, key);
            if (earlier.isPresent()) {
                IdempotencyRecord record = earlier.get();
                if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
                    tx.executeWithoutResult(s -> em.remove(em.find(IdempotencyRecord.class, record.getId())));
                } else if (!record.getRequestHash().equals(hash)) {
                    throw new Answered(409, ApiError.IDEMPOTENCY_CONFLICT,
                            "This Idempotency-Key was already used for a different request", null);
                } else if (IdempotencyRecord.IN_PROGRESS.equals(record.getStatus())) {
                    throw new Answered(409, IN_PROGRESS_CODE,
                            "A request with this Idempotency-Key is still being processed. Try again shortly.", null);
                } else {
                    throw new Answered(record.getResponseStatus(), null, null, record);
                }
            }
            try {
                return tx.execute(s -> {
                    IdempotencyRecord created = new IdempotencyRecord(caller, operation, key, hash,
                            LocalDateTime.now().plusHours(retentionHours));
                    em.persist(created);
                    em.flush();
                    return created.getId();
                });
            } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException race) {
                // Another request with the same key got there first; read what it recorded.
            }
        }
        throw new Answered(409, IN_PROGRESS_CODE,
                "A request with this Idempotency-Key is still being processed. Try again shortly.", null);
    }

    private Optional<IdempotencyRecord> find(String caller, String operation, String key) {
        return tx.execute(s -> em.createQuery("select r from IdempotencyRecord r where r.caller = :caller"
                        + " and r.operation = :operation and r.key = :key", IdempotencyRecord.class)
                .setParameter("caller", caller).setParameter("operation", operation).setParameter("key", key)
                .getResultStream().findFirst());
    }

    /** Removes records past their retention, hourly. */
    @Scheduled(fixedDelayString = "${nsure.idempotency.purge-interval-ms:3600000}", initialDelay = 60000)
    public void purgeExpired() {
        tx.executeWithoutResult(s -> em.createQuery("delete from IdempotencyRecord r where r.expiresAt < :now")
                .setParameter("now", LocalDateTime.now()).executeUpdate());
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static String hash(String operation, String query, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update((query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The request is answered without running it: a conflict, or the stored answer of the first request. */
    private final class Answered extends Exception {
        private final int status;
        private final String code;
        private final String message;
        private final transient IdempotencyRecord replay;

        Answered(int status, String code, String message, IdempotencyRecord replay) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
            this.message = message;
            this.replay = replay;
        }

        void write(HttpServletResponse response) throws IOException {
            if (replay == null) {
                ApiErrorWriter.write(response, mapper, status, code, message);
                return;
            }
            response.setStatus(status);
            response.setHeader(REPLAYED, "true");
            if (replay.getResponseType() != null) {
                response.setContentType(replay.getResponseType());
            }
            byte[] body = replay.getResponseBody() == null ? new byte[0]
                    : replay.getResponseBody().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
    }

    /** The request with its body read once, so it can be hashed and still be read by the controller. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
