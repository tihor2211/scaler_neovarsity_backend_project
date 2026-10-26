package com.dashcart.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.dashcart.TestcontainersConfiguration;

// Base for API tests: the full app against real MySQL + Redis containers. All subclasses share one
// Spring context (and one pair of containers), so each test creates its own uniquely named data.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dashcart.admin.email=" + TestApi.ADMIN_EMAIL,
        "dashcart.admin.password=" + TestApi.ADMIN_PASSWORD
})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    protected TestApi api() {
        return new TestApi(mvc);
    }
}
