package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.group.CensusService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The batch contract of spec section 11 for employer census files (NS-SH12-002): the file comes with a manifest —
 * schema version, source, external batch id, creation and as-of dates, record count, currency, salary control total
 * and SHA-256 checksum — and the manifest is validated <b>before</b> any row is staged. A batch whose manifest does
 * not match its content is rejected whole with the reasons and nothing is staged; a matching one is handed to the
 * census staging of the group module unchanged. Each receipt keeps its manifest and the earlier batch it corrects,
 * and its acknowledgement (accepted, rejected and pending rows, row errors, created member references) is produced on
 * demand. Encryption of files in transit is a production concern (out of scope).
 */
@Service
public class BatchGateway {

    /** Census file schema versions this build reads. */
    static final List<String> CENSUS_SCHEMAS = List.of("census-1");

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final CensusService census;
    private final ObjectMapper mapper;

    public BatchGateway(JdbcTemplate jdbc, PlatformSecurityContext security, CensusService census, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.census = census;
        this.mapper = mapper;
    }

    /**
     * {schemeRef, mode?, csv, correctsRef?, manifest: {schemaVersion, source, externalBatchId, createdOn, asOf,
     * recordCount, currency, controlTotal, checksum}}: receives a census batch with its manifest.
     */
    @Transactional
    public Map<String, Object> receiveCensus(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("PROCESS_CENSUS");
        String scheme = text(body, "schemeRef");
        if (scheme == null) {
            throw new InvalidFieldException("schemeRef", "Name the scheme the census is for.");
        }
        JsonNode manifest = body.path("manifest");
        if (manifest.isMissingNode() || !manifest.isObject()) {
            throw new InvalidFieldException("manifest", "A census batch needs its manifest.");
        }
        String csv = text(body, "csv") == null ? "" : body.get("csv").asText().replace("\r\n", "\n").strip();
        List<String> lines = csv.isEmpty() ? List.of() : csv.lines().filter(l -> !l.isBlank()).toList();
        int count = Math.max(0, lines.size() - 1);
        String checksum = sha256(csv);
        BigDecimal total = salaryTotal(lines);
        List<String> problems = new ArrayList<>();
        String schema = text(manifest, "schemaVersion");
        if (schema == null || !CENSUS_SCHEMAS.contains(schema)) {
            problems.add("Schema version " + schema + " is not supported (supported: " + CENSUS_SCHEMAS + ")");
        }
        String source = text(manifest, "source");
        if (source == null) {
            problems.add("The manifest names no source");
        }
        String external = text(manifest, "externalBatchId");
        if (external == null) {
            problems.add("The manifest has no external batch id");
        }
        Integer declared = manifest.hasNonNull("recordCount") ? manifest.get("recordCount").asInt() : null;
        if (declared == null || declared != count) {
            problems.add("The manifest declares " + declared + " record(s) but the file holds " + count);
        }
        String declaredChecksum = text(manifest, "checksum");
        if (declaredChecksum == null || !declaredChecksum.equalsIgnoreCase(checksum)) {
            problems.add("The checksum does not match the file (file SHA-256 " + checksum + ")");
        }
        BigDecimal declaredTotal = null;
        if (manifest.hasNonNull("controlTotal")) {
            declaredTotal = new BigDecimal(manifest.get("controlTotal").asText()).setScale(2, RoundingMode.HALF_UP);
            if (declaredTotal.compareTo(total) != 0) {
                problems.add("The salary control total " + declaredTotal.toPlainString() + " does not match the rows ("
                        + total.toPlainString() + ")");
            }
        } else {
            problems.add("The manifest has no salary control total");
        }
        String currency = text(manifest, "currency") == null ? null : text(manifest, "currency").toUpperCase(Locale.ROOT);
        if (currency != null && !"BWP".equals(currency)) {
            problems.add("Currency " + currency + " is not the scheme currency BWP");
        }
        String corrects = text(body, "correctsRef");
        if (corrects != null) {
            Integer earlier = jdbc.queryForObject("select count(*) from int_batch_receipt where record_ref = ? and"
                    + " target_ref = ?", Integer.class, corrects, scheme);
            if (earlier == null || earlier == 0) {
                problems.add("The batch it corrects (" + corrects + ") is not an earlier batch of scheme " + scheme);
            }
        }
        Integer n = jdbc.queryForObject("select count(*) from int_batch_receipt", Integer.class);
        String ref = String.format("BR-%05d", (n == null ? 0 : n) + 1);
        String staged = null;
        String status;
        if (problems.isEmpty()) {
            ObjectNode stage = mapper.createObjectNode();
            stage.put("externalBatchId", external);
            stage.put("csv", csv);
            if (text(body, "mode") != null) {
                stage.put("mode", text(body, "mode"));
            }
            staged = census.upload(scheme, stage).ref();
            status = "STAGED";
        } else {
            status = "REJECTED";
        }
        jdbc.update("insert into int_batch_receipt (record_ref, interface_code, batch_type, target_ref, schema_version,"
                + " source, external_batch_id, created_on, as_of, declared_count, actual_count, currency, declared_total,"
                + " actual_total, declared_checksum, actual_checksum, corrects_ref, status, problems, staged_ref,"
                + " received_by, received_at) values (?, 'EMPLOYER_LENDER', 'CENSUS', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?)", ref, scheme, schema, source, external, text(manifest, "createdOn"),
                text(manifest, "asOf"), declared, count, currency, declaredTotal, total, declaredChecksum, checksum,
                corrects, status, problems.isEmpty() ? null : clip(String.join("; ", problems), 1000), staged,
                user.getUsername(), LocalDateTime.now());
        return acknowledgement(ref, false);
    }

    /** Every receipt, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> receipts() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        return jdbc.queryForList("select record_ref from int_batch_receipt order by id desc", String.class).stream()
                .map(ref -> acknowledgement(ref, false)).toList();
    }

    /**
     * The acknowledgement for the partner: manifest checks, accepted / rejected / pending rows, row errors and the
     * member references created, read from the staged batch as it stands now.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> acknowledgement(String ref) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("READ_INTEGRATION")) {
            user.validateHasPermissionTo("PROCESS_CENSUS");
        }
        return acknowledgement(ref, true);
    }

    private Map<String, Object> acknowledgement(String ref, boolean detail) {
        Map<String, Object> r = jdbc.queryForList("select * from int_batch_receipt where record_ref = ?", ref).stream()
                .findFirst().orElseThrow(() -> new NotFoundException("Batch receipt " + ref + " not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptRef", ref);
        out.put("interfaceCode", v(r, "interface_code"));
        out.put("batchType", v(r, "batch_type"));
        out.put("targetRef", v(r, "target_ref"));
        out.put("status", v(r, "status"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", v(r, "schema_version"));
        manifest.put("source", v(r, "source"));
        manifest.put("externalBatchId", v(r, "external_batch_id"));
        manifest.put("createdOn", v(r, "created_on"));
        manifest.put("asOf", v(r, "as_of"));
        manifest.put("declaredCount", v(r, "declared_count"));
        manifest.put("actualCount", v(r, "actual_count"));
        manifest.put("currency", v(r, "currency"));
        manifest.put("declaredTotal", v(r, "declared_total"));
        manifest.put("actualTotal", v(r, "actual_total"));
        manifest.put("checksum", v(r, "actual_checksum"));
        out.put("manifest", manifest);
        out.put("correctsRef", v(r, "corrects_ref"));
        out.put("problems", v(r, "problems") == null ? List.of() : List.of(String.valueOf(v(r, "problems")).split("; ")));
        out.put("stagedRef", v(r, "staged_ref"));
        out.put("receivedBy", v(r, "received_by"));
        out.put("receivedAt", v(r, "received_at"));
        Object staged = v(r, "staged_ref");
        if (staged != null) {
            Map<String, Object> batch = jdbc.queryForList("select batch_status, total_rows, valid_rows, invalid_rows,"
                    + " accepted_rows, rejected_rows from grp_census_batch where record_ref = ?", staged).stream()
                    .findFirst().orElse(Map.of());
            boolean committed = "COMMITTED".equals(v(batch, "batch_status"));
            out.put("batchStatus", v(batch, "batch_status"));
            out.put("accepted", committed ? v(batch, "accepted_rows") : 0);
            out.put("rejected", committed ? v(batch, "rejected_rows") : v(batch, "invalid_rows"));
            out.put("pending", committed ? 0 : v(batch, "valid_rows"));
            if (detail) {
                out.put("rowErrors", jdbc.query("select r.line_no, r.member_key, r.error_detail from grp_census_row r"
                        + " join grp_census_batch b on b.id = r.batch_id where b.record_ref = ? and r.error_detail is"
                        + " not null order by r.line_no", (rs, i) -> Map.of("line", rs.getInt(1), "memberKey",
                        String.valueOf(rs.getString(2)), "error", rs.getString(3)), staged));
                out.put("createdRefs", jdbc.queryForList("select distinct r.member_key from grp_census_row r join"
                        + " grp_census_batch b on b.id = r.batch_id where b.record_ref = ? and r.member_id is not null"
                        + " order by r.member_key", String.class, staged));
            }
        } else {
            out.put("accepted", 0);
            out.put("rejected", v(r, "actual_count"));
            out.put("pending", 0);
        }
        return out;
    }

    private static BigDecimal salaryTotal(List<String> lines) {
        if (lines.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        List<String> header = List.of(lines.get(0).split(",", -1)).stream().map(String::trim).toList();
        int salary = header.indexOf("salary");
        BigDecimal total = BigDecimal.ZERO;
        if (salary >= 0) {
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split(",", -1);
                if (cells.length > salary) {
                    try {
                        total = total.add(new BigDecimal(cells[salary].trim()));
                    } catch (NumberFormatException ignored) {
                        // a malformed salary is a row error for staging, not a manifest total
                    }
                }
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Object v(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) && !body.get(field).asText().isBlank()
                ? body.get(field).asText().trim() : null;
    }
}
