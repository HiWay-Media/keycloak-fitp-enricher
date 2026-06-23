package com.hiwaymedia.keycloak.broker;

import org.jboss.logging.Logger;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.common.util.Time;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.JsonWebToken;

import java.util.Arrays;

/**
 * Sottoclasse diagnostica di {@link OIDCIdentityProvider}.
 *
 * Prima di delegare alla validazione standard dell'id_token, logga i claim temporali
 * (iat/exp/nbf) confrontati con l'ora del nodo e con l'allowed clock skew configurato,
 * per capire perche scatta "Token is no longer valid"
 * ({@code !idToken.isActive(allowedClockSkew)} in OIDCIdentityProvider).
 *
 * La validazione NON viene modificata: si aggiunge solo il log e poi si chiama super.
 * Questa classe esiste per diagnostica temporanea; rimuoverla una volta chiusa l'analisi.
 */
public class DiagOIDCIdentityProvider extends OIDCIdentityProvider {

    private static final Logger log = Logger.getLogger(DiagOIDCIdentityProvider.class);

    public DiagOIDCIdentityProvider(KeycloakSession session, OIDCIdentityProviderConfig config) {
        super(session, config);
    }

    @Override
    public JsonWebToken validateToken(String encodedToken) {
        logTokenDiagnostics(encodedToken);
        return super.validateToken(encodedToken);
    }

    private void logTokenDiagnostics(String encodedToken) {
        String alias = getConfig() != null ? getConfig().getAlias() : "?";
        if (encodedToken == null) {
            log.warnf("[FITP-DIAG] idp=%s id_token nullo dalla token response", alias);
            return;
        }
        try {
            JsonWebToken t = new JWSInput(encodedToken).readJsonContent(JsonWebToken.class);
            int now = Time.currentTime();
            int skew = getConfig().getAllowedClockSkew();
            Long iat = t.getIat();
            Long exp = t.getExp();
            Long nbf = t.getNbf();
            boolean active = t.isActive(skew);

            // expiredBy > 0  => exp gia passato di N secondi (callback stantio / token a vita breve)
            // notYetValidFor > 0 => nbf nel futuro di N secondi (skew vero tra Keycloak e l'IdP)
            String expiredBy = exp != null ? String.valueOf(now - exp) : "n/a";
            String notYetValidFor = nbf != null ? String.valueOf(nbf - now) : "n/a";

            log.warnf("[FITP-DIAG] idp=%s iss=%s sub=%s aud=%s iat=%s exp=%s nbf=%s now=%d skew=%d active=%s expiredBy=%ss notYetValidFor=%ss",
                    alias, t.getIssuer(), t.getSubject(), Arrays.toString(t.getAudience()),
                    iat, exp, nbf, now, skew, active, expiredBy, notYetValidFor);
        } catch (Exception e) {
            log.warnf(e, "[FITP-DIAG] idp=%s impossibile decodificare l'id_token per diagnostica", alias);
        }
    }
}
