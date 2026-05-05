package com.hiwaymedia.keycloak;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * DEPRECATO: usa {@link FitpEnricherIdentityProviderMapperFactory} (mapper sull'IdP FITP).
 *
 * Espone i campi di config nella UI Keycloak per il vecchio Authenticator in Post Login Flow.
 * Mantenuto per compatibilita; verra rimosso in v2.0.0.
 */
@Deprecated
public class FitpEnricherAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "fitp-enricher";

    public static final String CFG_TENANT_ID     = "graph.tenantId";
    public static final String CFG_CLIENT_ID     = "graph.clientId";
    public static final String CFG_CLIENT_SECRET = "graph.clientSecret";
    public static final String CFG_TIMEOUT_MS    = "graph.timeoutMs";
    public static final String CFG_RETRY_COUNT   = "graph.retryCount";
    public static final String CFG_FAIL_ON_ERROR = "graph.failOnError";
    public static final String CFG_TRUST_EMAIL   = "graph.trustEmail";

    private static final FitpEnricherAuthenticator SINGLETON = new FitpEnricherAuthenticator();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "FITP Profile Enricher";
    }

    @Override
    public String getReferenceCategory() {
        return "enricher";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "DEPRECATO: usa il mapper 'FITP Profile Enricher Mapper' sull'IdP FITP. "
             + "Questo authenticator gira nel Post Login Flow, dopo la creazione utente: "
             + "se il First Login Flow legge l'email il login fallisce con 'Email is null'. "
             + "Mantenuto solo per healing legacy di utenti gia creati con record vuoto.";
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return new Requirement[]{ Requirement.REQUIRED, Requirement.DISABLED };
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty tenantId = new ProviderConfigProperty();
        tenantId.setName(CFG_TENANT_ID);
        tenantId.setLabel("Azure Tenant ID");
        tenantId.setType(ProviderConfigProperty.STRING_TYPE);
        tenantId.setHelpText("Tenant ID o domain del tenant, es: 1c7ef0e8-1ff2-4ac9-9c20-944a0297e57e "
                + "oppure il domain equivalente");

        ProviderConfigProperty clientId = new ProviderConfigProperty();
        clientId.setName(CFG_CLIENT_ID);
        clientId.setLabel("App Registration Client ID");
        clientId.setType(ProviderConfigProperty.STRING_TYPE);
        clientId.setHelpText("Application (client) ID dell'app registration con permission Microsoft Graph "
                + "User.Read.All come Application (NON Delegated) e admin consent concesso");

        ProviderConfigProperty clientSecret = new ProviderConfigProperty();
        clientSecret.setName(CFG_CLIENT_SECRET);
        clientSecret.setLabel("App Registration Client Secret");
        clientSecret.setType(ProviderConfigProperty.PASSWORD);
        clientSecret.setHelpText("Client secret dell'app registration. Mascherato nella UI.");

        ProviderConfigProperty timeoutMs = new ProviderConfigProperty();
        timeoutMs.setName(CFG_TIMEOUT_MS);
        timeoutMs.setLabel("Timeout HTTP (ms)");
        timeoutMs.setType(ProviderConfigProperty.STRING_TYPE);
        timeoutMs.setDefaultValue("8000");
        timeoutMs.setHelpText("Timeout in millisecondi per le chiamate a Graph e al token endpoint");

        ProviderConfigProperty retryCount = new ProviderConfigProperty();
        retryCount.setName(CFG_RETRY_COUNT);
        retryCount.setLabel("Numero retry");
        retryCount.setType(ProviderConfigProperty.STRING_TYPE);
        retryCount.setDefaultValue("1");
        retryCount.setHelpText("Numero di retry su timeout / 429 / 503. Backoff fisso 250ms tra i tentativi.");

        ProviderConfigProperty failOnError = new ProviderConfigProperty();
        failOnError.setName(CFG_FAIL_ON_ERROR);
        failOnError.setLabel("Blocca login in caso di errore");
        failOnError.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        failOnError.setDefaultValue("false");
        failOnError.setHelpText("Se ON, il login fallisce quando Graph non risponde o l'utente non e trovato. "
                + "Se OFF (default), l'utente entra comunque (con profilo vuoto se la chiamata fallisce).");

        ProviderConfigProperty trustEmail = new ProviderConfigProperty();
        trustEmail.setName(CFG_TRUST_EMAIL);
        trustEmail.setLabel("Marca email come verificata");
        trustEmail.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        trustEmail.setDefaultValue("true");
        trustEmail.setHelpText("Se ON, l'email recuperata da Graph viene marcata come verificata. "
                + "Sicuro perche B2C/Entra verificano l'email durante il signup.");

        return List.of(tenantId, clientId, clientSecret, timeoutMs, retryCount, failOnError, trustEmail);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
        // niente
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // niente
    }

    @Override
    public void close() {
        // niente
    }
}
