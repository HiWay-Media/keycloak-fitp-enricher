package com.hiwaymedia.keycloak.broker;

import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

/**
 * Override del provider OIDC built-in di Keycloak per iniettare il logging diagnostico
 * di {@link DiagOIDCIdentityProvider} SENZA dover riconfigurare l'IdP nel realm.
 *
 * Mantiene lo stesso id ("oidc", ereditato da {@link OIDCIdentityProviderFactory}) ma
 * dichiara un {@link #order()} maggiore: da Keycloak 21 l'override di un provider built-in
 * richiede order() superiore a quello di default (0), altrimenti vince il built-in.
 *
 * Effetto: TUTTI gli IdP di tipo "oidc" del server usano questa sottoclasse. Accettabile
 * perche aggiunge solo log. Rimuovere questo factory (e il provider) a diagnosi conclusa.
 */
public class DiagOIDCIdentityProviderFactory extends OIDCIdentityProviderFactory {

    @Override
    public OIDCIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new DiagOIDCIdentityProvider(session, new OIDCIdentityProviderConfig(model));
    }

    @Override
    public int order() {
        return 100; // > 0 (built-in OIDCIdentityProviderFactory) -> questo factory vince
    }
}
