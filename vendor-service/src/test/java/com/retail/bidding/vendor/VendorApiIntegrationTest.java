package com.retail.bidding.vendor;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class VendorApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void registersVendorsAndFiltersByStatus() throws Exception {
        String a = createVendor("Speedy Logistics", "ops@speedy.example");
        createVendor("Blue Dart Lite", "bids@bluedart.example");

        mvc.perform(patch("/vendors/{id}/status", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mvc.perform(get("/vendors").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem("bids@bluedart.example")))
                .andExpect(jsonPath("$[*].email", not(hasItem("ops@speedy.example"))));
    }

    @Test
    void rejectsDuplicateEmailAndInvalidInput() throws Exception {
        createVendor("Dup One", "dup@vendor.example");

        mvc.perform(post("/vendors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dup Two\",\"email\":\"DUP@vendor.example\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/vendors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404ForUnknownVendor() throws Exception {
        mvc.perform(get("/vendors/{id}", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private String createVendor(String name, String email) throws Exception {
        String body = mvc.perform(post("/vendors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"email\":\"%s\"}".formatted(name, email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }
}
