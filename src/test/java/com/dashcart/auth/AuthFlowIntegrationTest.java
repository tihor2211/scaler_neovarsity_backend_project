package com.dashcart.auth;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;
import com.jayway.jsonpath.JsonPath;

class AuthFlowIntegrationTest extends IntegrationTest {

    @Test
    void registerLoginMeLogout() throws Exception {
        String email = uniqueEmail();
        register("Asha", email, "secret-pass-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email", is(email)))
                .andExpect(jsonPath("$.role", is("CUSTOMER")));

        String token = login(email, "secret-pass-1");

        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(email)));

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The same token is refused after logout.
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail();
        register("Ravi", email, "secret-pass-1").andExpect(status().isCreated());
        register("Ravi again", email.toUpperCase(), "secret-pass-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", is("Email is already registered")));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        String email = uniqueEmail();
        register("Meera", email, "secret-pass-1").andExpect(status().isCreated());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail", is("Invalid email or password")));
    }

    @Test
    void invalidRegistrationListsFieldErrors() throws Exception {
        register("", "not-an-email", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void protectedEndpointsNeedAValidToken() throws Exception {
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminIsSeededAndOnlyAdminsReachAdminPaths() throws Exception {
        String adminToken = login(TestApi.ADMIN_EMAIL, TestApi.ADMIN_PASSWORD);
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.role", is("ADMIN")));

        String email = uniqueEmail();
        register("Customer", email, "secret-pass-1");
        String customerToken = login(email, "secret-pass-1");
        mvc.perform(get("/admin/anything").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions register(String name, String email, String password)
            throws Exception {
        String body = """
                {"name":"%s","email":"%s","password":"%s"}""".formatted(name, email, password);
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String login(String email, String password) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private static String json(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }
}
