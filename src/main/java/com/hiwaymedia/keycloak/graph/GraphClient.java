package com.hiwaymedia.keycloak.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Wrapper attorno a Microsoft Graph per leggere il profilo utente per OID.
 *
 * - Token client_credentials cachato in memoria (~1h).
 * - Retry breve su timeout / 429 / 503; nessun retry su 401/403/404.
 * - Singolo HttpClient condiviso (thread-safe).
 */
public class GraphClient {

    private static final Logger log = Logger.getLogger(GraphClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile String cachedToken;
    private static volatile Instant cachedTokenExpiresAt = Instant.EPOCH;
    private static final Object TOKEN_LOCK = new Object();
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;
    private static final long RETRY_BACKOFF_MS = 250;

    private static final String DEFAULT_TOKEN_BASE_URL = "https://login.microsoftonline.com";
    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.microsoft.com";

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final int timeoutMs;
    private final int retryCount;
    private final String tokenBaseUrl;
    private final String graphBaseUrl;

    public GraphClient(String tenantId, String clientId, String clientSecret, int timeoutMs, int retryCount) {
        this(tenantId, clientId, clientSecret, timeoutMs, retryCount, DEFAULT_TOKEN_BASE_URL, DEFAULT_GRAPH_BASE_URL);
    }

    /** Visibility per i test: permette di puntare a una WireMock locale. */
    GraphClient(String tenantId, String clientId, String clientSecret, int timeoutMs, int retryCount,
                String tokenBaseUrl, String graphBaseUrl) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeoutMs = timeoutMs;
        this.retryCount = Math.max(0, retryCount);
        this.tokenBaseUrl = tokenBaseUrl;
        this.graphBaseUrl = graphBaseUrl;
    }

    public GraphProfile fetchUserByOid(String oid) {
        String token = getGraphToken();
        String url = graphBaseUrl + "/v1.0/users/" + oid
                + "?$select=id,mail,otherMails,givenName,surname,displayName,identities,userPrincipalName";

        HttpResponse<String> res = sendWithRetry(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build());

        if (res.statusCode() != 200) {
            throw new GraphException("Graph users endpoint status=" + res.statusCode() + " body=" + res.body(),
                    res.statusCode());
        }

        try {
            JsonNode data = MAPPER.readTree(res.body());
            return new GraphProfile(
                    extractEmail(data),
                    data.hasNonNull("givenName") ? data.get("givenName").asText() : null,
                    data.hasNonNull("surname") ? data.get("surname").asText() : null);
        } catch (Exception e) {
            throw new GraphException("Cannot parse Graph response", 0, e);
        }
    }

    /**
     * Estrae l'email considerando le varianti tipiche di B2C / Entra:
     * - mail (utenti Entra normali)
     * - otherMails[0] (local accounts B2C, caso piu comune)
     * - identities[].issuerAssignedId con signInType=emailAddress
     */
    static String extractEmail(JsonNode data) {
        if (data.hasNonNull("mail")) {
            return data.get("mail").asText();
        }
        JsonNode otherMails = data.get("otherMails");
        if (otherMails != null && otherMails.isArray() && otherMails.size() > 0) {
            return otherMails.get(0).asText();
        }
        JsonNode identities = data.get("identities");
        if (identities != null && identities.isArray()) {
            for (JsonNode id : identities) {
                if ("emailAddress".equals(id.path("signInType").asText())
                        && id.hasNonNull("issuerAssignedId")) {
                    return id.get("issuerAssignedId").asText();
                }
            }
        }
        return null;
    }

    private String getGraphToken() {
        Instant now = Instant.now();
        String tok = cachedToken;
        if (tok != null && now.isBefore(cachedTokenExpiresAt)) {
            return tok;
        }
        synchronized (TOKEN_LOCK) {
            tok = cachedToken;
            if (tok != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
                return tok;
            }
            return requestNewGraphToken();
        }
    }

    private String requestNewGraphToken() {
        String tokenUrl = tokenBaseUrl + "/" + tenantId + "/oauth2/v2.0/token";
        String form = "client_id="     + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret="    + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&scope="            + URLEncoder.encode("https://graph.microsoft.com/.default", StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";

        HttpResponse<String> res = sendWithRetry(HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());

        if (res.statusCode() != 200) {
            throw new GraphException("Token endpoint status=" + res.statusCode() + " body=" + res.body(),
                    res.statusCode());
        }

        try {
            JsonNode json = MAPPER.readTree(res.body());
            long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong() : 3600L;
            String fresh = json.get("access_token").asText();
            cachedToken = fresh;
            cachedTokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - TOKEN_REFRESH_MARGIN_SECONDS));
            return fresh;
        } catch (Exception e) {
            throw new GraphException("Cannot parse token response", 0, e);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest req) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (shouldRetryStatus(res.statusCode()) && attempts <= retryCount) {
                    log.warnf("Graph status=%d, retry %d/%d", res.statusCode(), attempts, retryCount);
                    sleepBackoff();
                    continue;
                }
                return res;
            } catch (HttpTimeoutException te) {
                if (attempts <= retryCount) {
                    log.warnf("Graph timeout, retry %d/%d", attempts, retryCount);
                    sleepBackoff();
                    continue;
                }
                throw new GraphException("Graph request timed out after " + attempts + " attempts", 0, te);
            } catch (Exception e) {
                throw new GraphException("Graph request failed", 0, e);
            }
        }
    }

    private static boolean shouldRetryStatus(int status) {
        return status == 429 || status == 503;
    }

    private static void sleepBackoff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Visibility per i test: invalida la cache token statica. */
    static void resetTokenCacheForTests() {
        synchronized (TOKEN_LOCK) {
            cachedToken = null;
            cachedTokenExpiresAt = Instant.EPOCH;
        }
    }
}
