package com.hiwaymedia.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Arricchisce il profilo di un utente brokered da B2C chiamando Microsoft Graph
 * per recuperare email, firstName e lastName, che B2C non emette nei token.
 *
 * Va inserito in un Post Login Flow agganciato al provider FITP
 * (Identity providers -> FITP -> Advanced settings -> Post login flow).
 *
 * Idempotente: se l'utente ha gia l'email valorizzata, salta la chiamata.
 */
public class FitpEnricherAuthenticator implements Authenticator {

    private static final Logger log = Logger.getLogger(FitpEnricherAuthenticator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HttpClient condiviso (thread-safe). */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Cache del token Graph: validita ~1h, lo riusiamo finche e fresco.
     * Concorrenza: doppio-check con lock per evitare chiamate parallele.
     */
    private static volatile String cachedToken;
    private static volatile Instant cachedTokenExpiresAt = Instant.EPOCH;
    private static final Object TOKEN_LOCK = new Object();
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null) {
            log.debug("Nessun utente in context, skip");
            context.success();
            return;
        }

        // Idempotenza: se gia arricchito, non rifare nulla
        if (user.getEmail() != null && !user.getEmail().isEmpty()) {
            log.debugf("Utente %s gia arricchito (email presente), skip", user.getUsername());
            context.success();
            return;
        }

        AuthenticatorConfigModel cfgModel = context.getAuthenticatorConfig();
        if (cfgModel == null || cfgModel.getConfig() == null) {
            log.error("Authenticator non configurato (manca config nel flow)");
            context.success();
            return;
        }
        Map<String, String> cfg = cfgModel.getConfig();

        String tenantId     = cfg.get(FitpEnricherAuthenticatorFactory.CFG_TENANT_ID);
        String clientId     = cfg.get(FitpEnricherAuthenticatorFactory.CFG_CLIENT_ID);
        String clientSecret = cfg.get(FitpEnricherAuthenticatorFactory.CFG_CLIENT_SECRET);
        int timeoutMs       = parseInt(cfg.get(FitpEnricherAuthenticatorFactory.CFG_TIMEOUT_MS), 5000);
        boolean failOnError = Boolean.parseBoolean(
                cfg.getOrDefault(FitpEnricherAuthenticatorFactory.CFG_FAIL_ON_ERROR, "false"));
        boolean trustEmail  = Boolean.parseBoolean(
                cfg.getOrDefault(FitpEnricherAuthenticatorFactory.CFG_TRUST_EMAIL, "true"));

        if (isBlank(tenantId) || isBlank(clientId) || isBlank(clientSecret)) {
            log.error("Config incompleta: tenantId/clientId/clientSecret obbligatori");
            handleFailure(context, failOnError);
            return;
        }

        String oid = user.getUsername();
        log.infof("Arricchimento profilo Graph per oid=%s", oid);

        try {
            String token = getGraphToken(tenantId, clientId, clientSecret, timeoutMs);

            String url = "https://graph.microsoft.com/v1.0/users/" + oid
                       + "?$select=id,mail,otherMails,givenName,surname,displayName,identities,userPrincipalName";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.errorf("Graph status=%d oid=%s body=%s", res.statusCode(), oid, res.body());
                handleFailure(context, failOnError);
                return;
            }

            JsonNode data = MAPPER.readTree(res.body());

            String email = extractEmail(data);
            if (email != null) {
                user.setEmail(email);
                if (trustEmail) {
                    user.setEmailVerified(true);
                }
            }
            if (data.hasNonNull("givenName")) {
                user.setFirstName(data.get("givenName").asText());
            }
            if (data.hasNonNull("surname")) {
                user.setLastName(data.get("surname").asText());
            }

            log.infof("Profilo arricchito oid=%s email=%s firstName=%s lastName=%s",
                    oid, user.getEmail(), user.getFirstName(), user.getLastName());

            context.success();

        } catch (Exception e) {
            log.errorf(e, "Errore Graph per oid=%s", oid);
            handleFailure(context, failOnError);
        }
    }

    /**
     * Estrae l'email considerando le varianti tipiche di B2C / Entra:
     * - mail (utenti Entra normali)
     * - otherMails[0] (local accounts B2C, caso piu comune)
     * - identities[].issuerAssignedId con signInType=emailAddress
     * - userPrincipalName come fallback estremo (di solito GUID, quindi non email vera)
     */
    private String extractEmail(JsonNode data) {
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

    /**
     * Recupera un token Graph via client_credentials, con caching in memoria.
     */
    private String getGraphToken(String tenantId, String clientId, String clientSecret, int timeoutMs)
            throws Exception {
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
            return requestNewGraphToken(tenantId, clientId, clientSecret, timeoutMs);
        }
    }

    private String requestNewGraphToken(String tenantId, String clientId, String clientSecret, int timeoutMs)
            throws Exception {
        String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
        String form = "client_id="     + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret="    + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&scope="            + URLEncoder.encode("https://graph.microsoft.com/.default", StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Token endpoint status=" + res.statusCode() + " body=" + res.body());
        }
        JsonNode json = MAPPER.readTree(res.body());
        long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong() : 3600L;
        String fresh = json.get("access_token").asText();

        cachedToken = fresh;
        cachedTokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - TOKEN_REFRESH_MARGIN_SECONDS));
        return fresh;
    }

    private void handleFailure(AuthenticationFlowContext context, boolean failOnError) {
        if (failOnError) {
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        } else {
            context.success();
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // niente
    }

    @Override
    public void close() {
        // niente
    }
}
