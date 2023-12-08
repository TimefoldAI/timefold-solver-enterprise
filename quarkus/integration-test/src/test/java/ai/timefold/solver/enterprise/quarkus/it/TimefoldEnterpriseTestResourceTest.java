package ai.timefold.solver.enterprise.quarkus.it;

import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * Test various Timefold operations running in Quarkus
 */

@QuarkusTest
public class TimefoldEnterpriseTestResourceTest {
    @Test
    @Timeout(600)
    void solveWithSolverFactory() throws Exception {
        RestAssured.given()
                .header("Content-Type", "application/json")
                .when()
                .post("/timefold/test/solver-factory")
                .then()
                .body(is(
                        "0hard/5soft"));
    }
}
