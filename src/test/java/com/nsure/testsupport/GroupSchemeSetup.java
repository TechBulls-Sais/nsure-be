package com.nsure.testsupport;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A scheme created with {@code POST /group/schemes} is in SETUP and takes no census or cover until it is activated
 * from accepted terms (NS-GL01-004). Tests about membership, billing or claims activate it here: a quotation is
 * prepared by {@code admin}, approved by {@code secadmin}, accepted, and the scheme activated on the demo group
 * wording with the inception conditions confirmed. Categories the test already added are kept; a scheme without any
 * gets the quotation's single default category ({@code ALL}).
 */
public final class GroupSchemeSetup {

    /** The demo census a test quotation is rated on: two lives, both valid. */
    public static final String QUOTE_CENSUS = "memberKey,dateOfBirth,category,salary\\nT1,1985-01-01,ALL,500000"
            + "\\nT2,1990-06-01,ALL,500000";

    private GroupSchemeSetup() {
    }

    /** Activates the scheme if it is still in set-up; does nothing to a scheme that is already active. */
    public static void activate(MockMvc mvc, String schemeRef) throws Exception {
        String detail = mvc.perform(get("/group/schemes/" + schemeRef).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        if (!"SETUP".equals(JsonPath.read(detail, "$.scheme.status"))) {
            return;
        }
        String quote = acceptedQuote(mvc, "Terms for " + schemeRef + " " + System.nanoTime());
        mvc.perform(post("/group/schemes/" + schemeRef + "/activation").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quoteRef\":\"" + quote + "\","
                                + "\"wordingRef\":\"WRD-G001\",\"conditions\":[\"SIGNED_PROPOSAL\",\"SIGNED_CENSUS\","
                                + "\"FIRST_PREMIUM_OR_DEFERRAL\"]}"))
                .andExpect(status().isOk());
    }

    /** A quotation for the sponsor taken through approval to the sponsor's acceptance; returns its reference. */
    public static String acceptedQuote(MockMvc mvc, String sponsor) throws Exception {
        String quote = JsonPath.read(mvc.perform(post("/group-life/quotes").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sponsorName\":\"" + sponsor + "\","
                                + "\"sector\":\"Retail\",\"lives\":2,\"salaryRoll\":1000000,\"benefits\":[\"GLA\"],\"ratingBasis\":\"SUM_ASSURED\","
                                + "\"salaryMultiple\":1,\"ratePerMille\":1,\"freeCoverLimit\":5000000,"
                                + "\"census\":\"" + QUOTE_CENSUS + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        for (String[] step : new String[][] {{"admin", "SUBMIT"}, {"secadmin", "APPROVE"}, {"admin", "ACCEPT"}}) {
            mvc.perform(post("/group-life/quotes/" + quote + "/progress").with(signedIn(step[0], "password"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"" + step[1] + "\"}"))
                    .andExpect(status().isOk());
        }
        return quote;
    }
}
