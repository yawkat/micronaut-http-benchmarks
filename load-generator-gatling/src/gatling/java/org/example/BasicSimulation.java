package org.example;

import io.gatling.javaapi.core.CoreDsl;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpDsl;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public class BasicSimulation extends Simulation {
    HttpProtocolBuilder httpProtocol =
            HttpDsl.http
                    .baseUrl("http://localhost:8080")
                    .acceptHeader("application/json,text/plain")
                    .contentTypeHeader("application/json");
    HttpProtocolBuilder https1Protocol =
            HttpDsl.http
                    .baseUrl("https://localhost:8443")
                    .acceptHeader("application/json,text/plain")
                    .contentTypeHeader("application/json");
    HttpProtocolBuilder https2Protocol =
            HttpDsl.http
                    .baseUrl("https://localhost:8443")
                    .acceptHeader("application/json,text/plain")
                    .contentTypeHeader("application/json")
                    .enableHttp2();

    InputGenerator shortInput = new InputGenerator(5, 5, 1);

    ScenarioBuilder scn =
            CoreDsl.scenario("short string search with positive result")
                    .exec(HttpDsl.http("request")
                            .post("/search/find")
                            .body(CoreDsl.ByteArrayBody(ses -> shortInput.generate())));

    {
        HttpProtocolBuilder protocol = switch (System.getProperty("protocol", "http")) {
            case "http" -> httpProtocol;
            case "https1" -> https1Protocol;
            case "https2" -> https2Protocol;
            default -> throw new IllegalArgumentException("Unknown protocol");
        };
        setUp(scn.injectOpen(CoreDsl.constantUsersPerSec(500).during(Integer.getInteger("duration", 10))))
                .protocols(protocol);
    }
}
