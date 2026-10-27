package com.nsure.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-public-products;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PublicProductApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void landingProductsAreReadableWithoutSigningIn() throws Exception {
        mockMvc.perform(get("/public/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].featuredOrder").value(1))
                .andExpect(jsonPath("$[?(@.name == 'Care Cash')].featuredOrder").value(3))
                .andExpect(jsonPath("$[?(@.name == 'Whole of Life')].featuredOrder").value(Matchers.contains(Matchers.nullValue())))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].tagline").value("Their tomorrow.\nYour peace of mind."))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].image").value("/products/term-life.jpg"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].tiers.length()").value(3));
    }

    @Test
    void landingProductsLeaveOutCountsAndStaffFields() throws Exception {
        mockMvc.perform(get("/public/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").doesNotExist())
                .andExpect(jsonPath("$[0].color").doesNotExist())
                .andExpect(jsonPath("$[0].description").doesNotExist());
    }

    @Test
    void headlineFiguresComeFromTheTiers() throws Exception {
        mockMvc.perform(get("/public/products"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].premium").value("from BWP 230"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].cover").value("up to BWP 1,000,000"))
                .andExpect(jsonPath("$[?(@.name == 'Care Cash')].cover").value("up to BWP 2,000 / day"));
    }

    @Test
    void otherPortfolioReadsStillNeedASignIn() throws Exception {
        mockMvc.perform(get("/portfolio/products")).andExpect(status().isUnauthorized());
    }
}
