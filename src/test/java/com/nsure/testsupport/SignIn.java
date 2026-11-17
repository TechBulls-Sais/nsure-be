package com.nsure.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.nsure.security.session.SessionTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Signs a test request in as someone who has completed sign-in, for tests about what a signed-in user may do.
 *
 * <p>Staff and partners cannot use their password on business endpoints (they need the one-time code first), so
 * this checks the password and then sends a session token issued directly, standing in for the code step. That step
 * itself is tested in {@code SessionTokenApiTest}. A wrong password, an unknown user or a disabled account is sent as
 * plain HTTP Basic, so the API refuses it with 401 exactly as it refuses a real client.
 */
public final class SignIn {

    private SignIn() {
    }

    public static RequestPostProcessor signedIn(String username, String password) {
        return request -> {
            WebApplicationContext context =
                    WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
            UserDetails user;
            try {
                user = context.getBean(UserDetailsService.class).loadUserByUsername(username);
            } catch (UsernameNotFoundException ex) {
                return httpBasic(username, password).postProcessRequest(request);
            }
            if (!user.isEnabled() || !context.getBean(PasswordEncoder.class).matches(password, user.getPassword())) {
                return httpBasic(username, password).postProcessRequest(request);
            }
            String token = context.getBean(SessionTokenService.class)
                    .issue((com.nsure.security.domain.AppUser) user).token();
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
