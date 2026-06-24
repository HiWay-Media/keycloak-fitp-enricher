package com.hiwaymedia.keycloak.broker;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Espone la config dell'enricher broker-flow nella UI Keycloak.
 * Da inserire come PRIMO step (REQUIRED) del First Broker Login flow dell'IdP FITP,
 * prima di "Create User If Unique".
 */
public class FitpBrokerEnricherAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "fitp-broker-enricher";

    public static final String CFG_TENANT_ID      = "graph.tenantId";
    public static final String CFG_CLIENT_ID      = "graph.clientId";
    public static final String CFG_CLIENT_SECRET  = "graph.clientSecret";
    public static final String CFG_TIMEOUT_MS     = "graph.timeoutMs";
    public static final String CFG_RETRY_COUNT    = "graph.retryCount";
    public static final String CFG_FAIL_ON_ERROR  = "graph.failOnError";
    public static final String CFG_USERNAME_SOURCE = "username.source";

    public static final String USERNAME_SOURCE_EMAIL = "email";
    public static final String USERNAME_SOURCE_OID   = "oid";

    private static final FitpBrokerEnricherAuthenticator SINGLETON = new FitpBrokerEnricherAuthenticator();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "FITP Broker Enricher";
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
        return "Primo step del First Broker Login flow sull'IdP FITP. Recupera email/firstName/lastName "
             + "da Microsoft Graph e li inietta nel brokered context PRIMA di 'Create User If Unique', "
             + "cosi creazione e deduplica per email funzionano anche se B2C non emette l'email nel token.";
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
        tenantId.setHelpText("Tenant ID o domain del tenant Azure/B2C");

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
        failOnError.setDefaultValue("true");
        failOnError.setHelpText("Default ON (fail-closed): se Graph non risponde o non c'e' email, il login "
                + "si interrompe invece di creare un utente senza email (che poi fallirebbe la creazione). "
                + "Se OFF, lascia proseguire il flow.");

        ProviderConfigProperty usernameSource = new ProviderConfigProperty();
        usernameSource.setName(CFG_USERNAME_SOURCE);
        usernameSource.setLabel("Username dell'utente Keycloak");
        usernameSource.setType(ProviderConfigProperty.LIST_TYPE);
        usernameSource.setOptions(List.of(USERNAME_SOURCE_EMAIL, USERNAME_SOURCE_OID));
        usernameSource.setDefaultValue(USERNAME_SOURCE_EMAIL);
        usernameSource.setHelpText("Se 'email', imposta lo username del nuovo utente all'email recuperata "
                + "(coerente con 'Email as username'). Se 'oid', lascia il default OID/sub di B2C.");

        return List.of(tenantId, clientId, clientSecret, timeoutMs, retryCount, failOnError, usernameSource);
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
