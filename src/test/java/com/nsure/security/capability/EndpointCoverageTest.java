package com.nsure.security.capability;

import static org.assertj.core.api.Assertions.assertThat;

import com.nsure.security.config.ApiAccess;
import com.nsure.security.config.ApiAccessRules;
import com.nsure.security.config.ApiAccessRules.ApiRule;
import com.nsure.security.domain.Permission;
import com.nsure.security.repository.PermissionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Every endpoint must have an explicit rule in {@link ApiAccessRules}. Without one the security filter denies it
 * to everybody, so a new endpoint with no access decision fails here instead of failing silently in production.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-coverage;DB_CLOSE_DELAY=-1")
class EndpointCoverageTest {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}:]+)(?::([^}]*))?}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private PermissionRepository permissions;

    @Test
    void everyEndpointHasAnAccessRule() {
        List<String> undecided = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().startsWith("com.nsure")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                undecided.add(entry.getValue() + " must name its HTTP method");
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                for (String path : samplePaths(pattern)) {
                    for (RequestMethod method : methods) {
                        if (ruleFor(method.name(), path) == null) {
                            undecided.add(method + " " + path + " (" + entry.getValue().getMethod().getName() + ")");
                        }
                    }
                }
            }
        }
        assertThat(undecided).as("Endpoints with no rule in ApiAccessRules").isEmpty();
    }

    @Test
    void everyPermissionCodeInTheRulesIsSeeded() {
        Set<String> seeded = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        Set<String> missing = new TreeSet<>();
        for (ApiRule rule : ApiAccessRules.RULES) {
            if (rule.access().kind() != ApiAccess.Kind.PERMISSION) {
                continue;
            }
            for (String code : rule.access().permissions()) {
                if (!code.contains(".") && !seeded.contains(code)) {
                    missing.add(code);
                }
            }
        }
        assertThat(missing).as("Permission codes used in ApiAccessRules but missing from app_permission").isEmpty();
    }

    @Test
    void claimActionsAreGatedOneByOne() {
        assertThat(ruleFor("POST", "/portfolio/claims/CLM-1/assess").access().permissions()).containsExactly("ASSESS_CLAIM");
        assertThat(ruleFor("POST", "/portfolio/claims/CLM-1/approve").access().permissions()).containsExactly("APPROVE_CLAIM");
        assertThat(ruleFor("POST", "/portfolio/claims/CLM-1/reject").access().permissions()).containsExactly("REJECT_CLAIM");
        assertThat(ruleFor("POST", "/portfolio/claims/CLM-1/pay").access().permissions()).containsExactly("SETTLE_CLAIM");
    }

    /** The first rule that matches, as in the security filter chain. */
    private static ApiRule ruleFor(String method, String path) {
        for (ApiRule rule : ApiAccessRules.RULES) {
            if (rule.method().name().equals(method)
                    && PathPatternParser.defaultInstance.parse(rule.pattern()).matches(PathContainer.parsePath(path))) {
                return rule;
            }
        }
        return null;
    }

    /** A concrete path per alternative: {@code {action:assess|pay}} gives one path each, {@code {ref}} gives "x". */
    private static List<String> samplePaths(String pattern) {
        Matcher matcher = VARIABLE.matcher(pattern);
        if (!matcher.find()) {
            return List.of(pattern);
        }
        List<String> values = matcher.group(2) == null ? List.of("x") : List.of(matcher.group(2).split("\\|"));
        List<String> paths = new ArrayList<>();
        for (String value : values) {
            String next = pattern.substring(0, matcher.start()) + value + pattern.substring(matcher.end());
            paths.addAll(samplePaths(next));
        }
        return paths;
    }
}
