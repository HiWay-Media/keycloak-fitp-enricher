package com.hiwaymedia.keycloak;

import com.hiwaymedia.keycloak.graph.GraphClient;
import com.hiwaymedia.keycloak.graph.GraphException;
import com.hiwaymedia.keycloak.graph.GraphProfile;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Map;

/**
 * DEPRECATO: usa {@link FitpEnricherIdentityProviderMapper} sull'IdP FITP.
 *
 * Questo authenticator, montato in un Post Login Flow, gira DOPO la creazione dell'utente
 * da parte del First Login Flow. Se il First Login Flow legge l'email (review profile, ecc.),
 * fallisce prima ancora di raggiungere il Post Login. Il mapper risolve girando in
 * preprocessFederatedIdentity, prima di qualunque step del First Login Flow.
 *
 * Mantenuto per compatibilita binaria con installazioni esistenti e per "healing" di utenti
 * gia creati con record vuoto. Sara rimosso in v2.0.0.
 */
@Deprecated
public class FitpEnricherAuthenticator implements Authenticator {

    private static final Logger log = Logger.getLogger(FitpEnricherAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null) {
            log.debug("Nessun utente in context, skip");
            context.success();
            return;
        }

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
        int timeoutMs       = parseInt(cfg.get(FitpEnricherAuthenticatorFactory.CFG_TIMEOUT_MS), 8000);
        int retryCount      = parseInt(cfg.get(FitpEnricherAuthenticatorFactory.CFG_RETRY_COUNT), 1);
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
            GraphProfile p = new GraphClient(tenantId, clientId, clientSecret, timeoutMs, retryCount)
                    .fetchUserByOid(oid);

            if (p.email() != null) {
                user.setEmail(p.email());
                if (trustEmail) {
                    user.setEmailVerified(true);
                }
            }
            if (p.firstName() != null) {
                user.setFirstName(p.firstName());
            }
            if (p.lastName() != null) {
                user.setLastName(p.lastName());
            }

            log.infof("Profilo arricchito oid=%s email=%s firstName=%s lastName=%s",
                    oid, user.getEmail(), user.getFirstName(), user.getLastName());

            context.success();

        } catch (GraphException e) {
            log.errorf(e, "Errore Graph per oid=%s status=%d", oid, e.getStatusCode());
            handleFailure(context, failOnError);
        }
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
