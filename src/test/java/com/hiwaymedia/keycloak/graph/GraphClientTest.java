package com.hiwaymedia.keycloak.graph;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphClientTest {

    private WireMockServer server;
    private String baseUrl;

    private static final String TENANT = "test-tenant";
    private static final String OID = "ffc44582-ceed-43c7-9c40-0f5b68b3e107";

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        baseUrl = "http://localhost:" + server.port();
        GraphClient.resetTokenCacheForTests();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        GraphClient.resetTokenCacheForTests();
    }

    private GraphClient client(int timeoutMs, int retries) {
        return new GraphClient(TENANT, "cid", "secret", timeoutMs, retries, baseUrl, baseUrl);
    }

    private void stubToken() {
        server.stubFor(post(urlMatching("/" + TENANT + "/oauth2/v2.0/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok\",\"expires_in\":3600}")));
    }

    @Test
    void happyPathReturnsProfile() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + OID + "\",\"mail\":\"a@b.it\",\"givenName\":\"A\",\"surname\":\"B\"}")));

        GraphProfile p = client(2000, 0).fetchUserByOid(OID);

        assertEquals("a@b.it", p.email());
        assertEquals("A", p.firstName());
        assertEquals("B", p.lastName());
    }

    @Test
    void emailFallsBackToOtherMails() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"otherMails\":[\"local@b.it\"],\"givenName\":\"L\",\"surname\":\"O\"}")));

        GraphProfile p = client(2000, 0).fetchUserByOid(OID);

        assertEquals("local@b.it", p.email());
    }

    @Test
    void emailFallsBackToIdentitiesEmailAddress() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"identities\":[{\"signInType\":\"emailAddress\",\"issuerAssignedId\":\"id@b.it\"}]}")));

        GraphProfile p = client(2000, 0).fetchUserByOid(OID);

        assertEquals("id@b.it", p.email());
    }

    @Test
    void emailNullWhenNoSourceAvailable() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + OID + "\"}")));

        GraphProfile p = client(2000, 0).fetchUserByOid(OID);

        assertNull(p.email());
    }

    @Test
    void status404ThrowsGraphException() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withStatus(404).withBody("{}")));

        GraphException e = assertThrows(GraphException.class, () -> client(2000, 0).fetchUserByOid(OID));
        assertEquals(404, e.getStatusCode());
    }

    @Test
    void timeoutThenSuccessRetries() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .inScenario("retry").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withFixedDelay(2000).withStatus(200).withBody("{}"))
                .willSetStateTo("ok"));
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .inScenario("retry").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"mail\":\"x@y.it\"}")));

        GraphProfile p = client(500, 1).fetchUserByOid(OID);

        assertEquals("x@y.it", p.email());
    }

    @Test
    void timeoutWithNoRetriesFailsFast() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withFixedDelay(2000).withStatus(200).withBody("{}")));

        assertThrows(GraphException.class, () -> client(300, 0).fetchUserByOid(OID));
    }

    @Test
    void tokenIsCachedAcrossCalls() {
        stubToken();
        server.stubFor(get(urlPathMatching("/v1.0/users/" + OID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"mail\":\"c@d.it\"}")));

        GraphClient c = client(2000, 0);
        c.fetchUserByOid(OID);
        c.fetchUserByOid(OID);

        server.verify(1, postRequestedFor(urlMatching("/" + TENANT + "/oauth2/v2.0/token")));
    }
}
