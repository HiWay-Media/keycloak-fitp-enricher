package com.hiwaymedia.keycloak.broker;

import com.hiwaymedia.keycloak.graph.GraphClient;
import com.hiwaymedia.keycloak.graph.GraphException;
import com.hiwaymedia.keycloak.graph.GraphProfile;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Map;

/**
 * Authenticator del First Broker Login flow che arricchisce il
 * {@link BrokeredIdentityContext} chiamando Microsoft Graph (email/firstName/lastName)
 * PRIMA di {@code Create User If Unique}.
 *
 * Va messo come PRIMO step (REQUIRED) del flow: cosi quando Keycloak crea o deduplica
 * l'utente, l'email c'e gia. Risolve sia il primo login fallito (B2C non emette email)
 * sia i duplicati (dedup per email funziona solo se l'email e' presente al broker-time).
 *
 * Pura acquisizione: scrive solo sul context, non tocca lo UserModel. L'update/heal
 * post-login non e' piu' necessario.
 */
public class FitpBrokerEnricherAuthenticator extends AbstractIdpAuthenticator {

    private static final Logger log = Logger.getLogger(FitpBrokerEnricherAuthenticator.class);

    @Override
    protected void authenticateImpl(AuthenticationFlowContext context,
                                    SerializedBrokeredIdentityContext serializedCtx,
                                    BrokeredIdentityContext brokerContext) {

        AuthenticatorConfigModel cfgModel = context.getAuthenticatorConfig();
        Map<String, String> cfg = cfgModel != null ? cfgModel.getConfig() : null;
        boolean failOnError = cfg == null || Boolean.parseBoolean(
                cfg.getOrDefault(FitpBrokerEnricherAuthenticatorFactory.CFG_FAIL_ON_ERROR, "true"));

        if (cfg == null) {
            log.error("FITP broker enricher non configurato (manca config nel flow)");
            fail(context, failOnError);
            return;
        }

        // Se l'email e' gia sul context (improbabile per FITP/B2C), niente Graph.
        if (!isBlank(serializedCtx.getEmail())) {
            context.success();
            return;
        }

        String tenantId     = cfg.get(FitpBrokerEnricherAuthenticatorFactory.CFG_TENANT_ID);
        String clientId     = cfg.get(FitpBrokerEnricherAuthenticatorFactory.CFG_CLIENT_ID);
        String clientSecret = cfg.get(FitpBrokerEnricherAuthenticatorFactory.CFG_CLIENT_SECRET);
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(clientSecret)) {
            log.error("FITP broker enricher: config incompleta (tenantId/clientId/clientSecret obbligatori)");
            fail(context, failOnError);
            return;
        }

        int timeoutMs  = parseInt(cfg.get(FitpBrokerEnricherAuthenticatorFactory.CFG_TIMEOUT_MS), 8000);
        int retryCount = parseInt(cfg.get(FitpBrokerEnricherAuthenticatorFactory.CFG_RETRY_COUNT), 1);

        String oid = resolveOid(brokerContext);
        if (isBlank(oid)) {
            log.warn("FITP broker enricher: nessun OID identificabile sul context");
            fail(context, failOnError);
            return;
        }

        log.infof("[FITP] arricchimento context da Graph per oid=%s", oid);
        GraphProfile p;
        try {
            p = createGraphClient(tenantId, clientId, clientSecret, timeoutMs, retryCount).fetchUserByOid(oid);
        } catch (GraphException e) {
            log.errorf(e, "[FITP] errore Graph per oid=%s status=%d", oid, e.getStatusCode());
            fail(context, failOnError);
            return;
        }

        if (isBlank(p.email())) {
            // Senza email non possiamo creare/deduplicare correttamente -> fail-closed.
            log.warnf("[FITP] Graph non ha restituito email per oid=%s", oid);
            fail(context, failOnError);
            return;
        }

        serializedCtx.setEmail(p.email());
        if (p.firstName() != null) {
            serializedCtx.setFirstName(p.firstName());
        }
        if (p.lastName() != null) {
            serializedCtx.setLastName(p.lastName());
        }
        // Brokered username -> finisce in FEDERATED_IDENTITY.FEDERATED_USERNAME.
        // Lo allineiamo all'email come fanno gli altri IdP (google/apple/fb), che
        // l'email la portano nel token; B2C no, quindi la impostiamo qui.
        serializedCtx.setUsername(p.email());
        if (FitpBrokerEnricherAuthenticatorFactory.USERNAME_SOURCE_EMAIL.equals(usernameSource(cfg))) {
            serializedCtx.setModelUsername(p.email());
        }

        // Persisti sul note letto dagli step successivi (Create User If Unique, ...).
        serializedCtx.saveToAuthenticationSession(context.getAuthenticationSession(), BROKERED_CONTEXT_NOTE);

        log.infof("[FITP] context arricchito oid=%s email=%s firstName=%s lastName=%s",
                oid, p.email(), p.firstName(), p.lastName());
        context.success();
    }

    @Override
    protected void actionImpl(AuthenticationFlowContext context,
                              SerializedBrokeredIdentityContext serializedCtx,
                              BrokeredIdentityContext brokerContext) {
        // Nessuna interazione utente: lo step si risolve interamente in authenticateImpl.
        context.success();
    }

    private void fail(AuthenticationFlowContext context, boolean failOnError) {
        if (failOnError) {
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        } else {
            context.success();
        }
    }

    /**
     * Ricava il sub B2C "nudo" da passare a Graph.
     *
     * {@code getId()} e' l'id federato (il sub) dal costruttore del context; come fallback
     * {@code getBrokerUserId()}. Nel First Broker Login Keycloak puo' prefissare username/id
     * con "&lt;alias&gt;." (es. "fitp.<sub>"): quel prefisso va tolto, altrimenti Graph risponde 404.
     */
    static String resolveOid(BrokeredIdentityContext brokerContext) {
        String alias = brokerContext.getIdpConfig() != null ? brokerContext.getIdpConfig().getAlias() : null;
        return resolveOid(brokerContext.getId(), brokerContext.getBrokerUserId(), alias);
    }

    /** Logica pura (testabile senza mock): sceglie il sub e rimuove il prefisso "&lt;alias&gt;.". */
    static String resolveOid(String id, String brokerUserId, String alias) {
        String oid = firstNonBlank(id, brokerUserId);
        if (oid == null) {
            return null;
        }
        if (alias != null && !alias.isEmpty() && oid.startsWith(alias + ".")) {
            oid = oid.substring(alias.length() + 1);
        }
        return oid;
    }

    /** Seam per i test: permette di iniettare un GraphClient mocked. */
    protected GraphClient createGraphClient(String tenantId, String clientId, String clientSecret,
                                            int timeoutMs, int retryCount) {
        return new GraphClient(tenantId, clientId, clientSecret, timeoutMs, retryCount);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    private static String usernameSource(Map<String, String> cfg) {
        String v = cfg.get(FitpBrokerEnricherAuthenticatorFactory.CFG_USERNAME_SOURCE);
        return (v == null || v.isEmpty()) ? FitpBrokerEnricherAuthenticatorFactory.USERNAME_SOURCE_EMAIL : v;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (!isBlank(c)) return c;
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
