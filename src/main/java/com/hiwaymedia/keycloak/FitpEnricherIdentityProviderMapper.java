package com.hiwaymedia.keycloak;

import com.hiwaymedia.keycloak.graph.GraphClient;
import com.hiwaymedia.keycloak.graph.GraphException;
import com.hiwaymedia.keycloak.graph.GraphProfile;
import org.jboss.logging.Logger;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.IdentityProviderMapper;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Identity Provider Mapper che, su login via FITP/B2C, chiama Microsoft Graph
 * per recuperare email/firstName/lastName e li inietta nel BrokeredIdentityContext
 * PRIMA che il First Login Flow legga questi campi (es. IdpReviewProfileAuthenticator).
 *
 * Se {@code username.source = email}, anche lo username del nuovo utente Keycloak
 * viene impostato all'email recuperata, invece del default OID/sub di B2C.
 *
 * Si configura su Identity providers > FITP > Mappers.
 */
public class FitpEnricherIdentityProviderMapper extends AbstractIdentityProviderMapper {

    public static final String PROVIDER_ID = "fitp-enricher-mapper";

    public static final String CFG_TENANT_ID      = "graph.tenantId";
    public static final String CFG_CLIENT_ID      = "graph.clientId";
    public static final String CFG_CLIENT_SECRET  = "graph.clientSecret";
    public static final String CFG_TIMEOUT_MS     = "graph.timeoutMs";
    public static final String CFG_RETRY_COUNT    = "graph.retryCount";
    public static final String CFG_FAIL_ON_ERROR  = "graph.failOnError";
    public static final String CFG_TRUST_EMAIL    = "graph.trustEmail";
    public static final String CFG_USERNAME_SOURCE = "username.source";

    public static final String USERNAME_SOURCE_EMAIL = "email";
    public static final String USERNAME_SOURCE_OID   = "oid";

    private static final String CONTEXT_MARKER = "fitp-enricher.done";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Logger log = Logger.getLogger(FitpEnricherIdentityProviderMapper.class);

    private static final String[] COMPATIBLE_PROVIDERS = { IdentityProviderMapper.ANY_PROVIDER };

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = buildConfigProperties();

    private static List<ProviderConfigProperty> buildConfigProperties() {
        ProviderConfigProperty tenantId = new ProviderConfigProperty();
        tenantId.setName(CFG_TENANT_ID);
        tenantId.setLabel("Azure Tenant ID");
        tenantId.setType(ProviderConfigProperty.STRING_TYPE);
        tenantId.setHelpText("Tenant ID o domain del tenant Azure/B2C");

        ProviderConfigProperty clientId = new ProviderConfigProperty();
        clientId.setName(CFG_CLIENT_ID);
        clientId.setLabel("App Registration Client ID");
        clientId.setType(ProviderConfigProperty.STRING_TYPE);
        clientId.setHelpText("Application (client) ID dell'app registration con permission "
                + "Microsoft Graph User.Read.All di tipo Application e admin consent concesso");

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
        timeoutMs.setHelpText("Timeout per le chiamate a Graph e al token endpoint");

        ProviderConfigProperty retryCount = new ProviderConfigProperty();
        retryCount.setName(CFG_RETRY_COUNT);
        retryCount.setLabel("Numero retry");
        retryCount.setType(ProviderConfigProperty.STRING_TYPE);
        retryCount.setDefaultValue("1");
        retryCount.setHelpText("Numero di retry su timeout / 429 / 503. Backoff fisso 250ms.");

        ProviderConfigProperty failOnError = new ProviderConfigProperty();
        failOnError.setName(CFG_FAIL_ON_ERROR);
        failOnError.setLabel("Blocca login in caso di errore");
        failOnError.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        failOnError.setDefaultValue("false");
        failOnError.setHelpText("Se ON, il login fallisce quando Graph non risponde o l'utente non e trovato. "
                + "Se OFF (default), l'utente entra comunque (con profilo eventualmente vuoto).");

        ProviderConfigProperty trustEmail = new ProviderConfigProperty();
        trustEmail.setName(CFG_TRUST_EMAIL);
        trustEmail.setLabel("Marca email come verificata");
        trustEmail.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        trustEmail.setDefaultValue("true");
        trustEmail.setHelpText("Se ON, l'email recuperata da Graph viene marcata come verificata sull'utente. "
                + "Sicuro perche B2C/Entra verificano l'email durante il signup.");

        ProviderConfigProperty usernameSource = new ProviderConfigProperty();
        usernameSource.setName(CFG_USERNAME_SOURCE);
        usernameSource.setLabel("Username dell'utente Keycloak");
        usernameSource.setType(ProviderConfigProperty.LIST_TYPE);
        usernameSource.setOptions(List.of(USERNAME_SOURCE_EMAIL, USERNAME_SOURCE_OID));
        usernameSource.setDefaultValue(USERNAME_SOURCE_EMAIL);
        usernameSource.setHelpText("Se 'email', lo username dell'utente Keycloak viene impostato all'email "
                + "recuperata da Graph. Se 'oid', resta l'OID/sub di B2C (comportamento legacy).");

        return List.of(tenantId, clientId, clientSecret, timeoutMs, retryCount, failOnError, trustEmail, usernameSource);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "FITP Profile Enricher Mapper";
    }

    @Override
    public String getDisplayCategory() {
        return "Attribute Importer";
    }

    @Override
    public String[] getCompatibleProviders() {
        return COMPATIBLE_PROVIDERS;
    }

    @Override
    public String getHelpText() {
        return "Arricchisce il BrokeredIdentityContext chiamando Microsoft Graph "
             + "(email, firstName, lastName) PRIMA che il First Login Flow ne abbia bisogno. "
             + "Risolve il problema del primo login fallito su B2C che non emette email nei token.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode mode) {
        return mode == IdentityProviderSyncMode.IMPORT
                || mode == IdentityProviderSyncMode.FORCE
                || mode == IdentityProviderSyncMode.LEGACY;
    }

    @Override
    public void preprocessFederatedIdentity(KeycloakSession session, RealmModel realm,
                                            IdentityProviderMapperModel mapperModel,
                                            BrokeredIdentityContext context) {
        if (context.getEmail() != null && !context.getEmail().isEmpty()) {
            return;
        }
        Map<String, Object> data = context.getContextData();
        if (data.containsKey(CONTEXT_MARKER)) {
            return;
        }

        Map<String, String> cfg = mapperModel.getConfig();
        String tenantId     = cfg.get(CFG_TENANT_ID);
        String clientId     = cfg.get(CFG_CLIENT_ID);
        String clientSecret = cfg.get(CFG_CLIENT_SECRET);
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(clientSecret)) {
            log.error("FITP enricher mapper: config incompleta (tenantId/clientId/clientSecret obbligatori)");
            return;
        }

        int timeoutMs = parseInt(cfg.get(CFG_TIMEOUT_MS), 8000);
        int retryCount = parseInt(cfg.get(CFG_RETRY_COUNT), 1);
        boolean failOnError = parseBool(cfg.get(CFG_FAIL_ON_ERROR), false);

        String oid = firstNonBlank(context.getId(), context.getBrokerUserId(), context.getUsername());
        if (isBlank(oid)) {
            log.warn("FITP enricher mapper: nessun OID identificabile sul context, skip");
            return;
        }

        log.infof("Arricchimento profilo Graph (mapper) per oid=%s", oid);
        GraphProfile p;
        try {
            p = createGraphClient(tenantId, clientId, clientSecret, timeoutMs, retryCount).fetchUserByOid(oid);
        } catch (GraphException e) {
            log.errorf(e, "Errore Graph (mapper) per oid=%s status=%d", oid, e.getStatusCode());
            if (failOnError) {
                throw new IdentityBrokerException("FITP enricher failed to fetch profile from Graph", e);
            }
            return;
        }

        if (p.email() != null) {
            context.setEmail(p.email());
        }
        if (p.firstName() != null) {
            context.setFirstName(p.firstName());
        }
        if (p.lastName() != null) {
            context.setLastName(p.lastName());
        }
        if (USERNAME_SOURCE_EMAIL.equals(usernameSource(cfg)) && p.email() != null) {
            context.setModelUsername(p.email());
        }
        data.put(CONTEXT_MARKER, Boolean.TRUE);

        log.infof("Profilo arricchito (mapper) oid=%s email=%s firstName=%s lastName=%s",
                oid, p.email(), p.firstName(), p.lastName());
    }

    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm, UserModel user,
                              IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        Map<String, String> cfg = mapperModel.getConfig();
        boolean trustEmail = parseBool(cfg.get(CFG_TRUST_EMAIL), true);

        applyContextToUser(user, context, cfg, trustEmail, true);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm, UserModel user,
                                   IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        Map<String, String> cfg = mapperModel.getConfig();
        boolean trustEmail = parseBool(cfg.get(CFG_TRUST_EMAIL), true);

        applyContextToUser(user, context, cfg, trustEmail, false);
    }

    /**
     * @param onCreate true durante importNewUser (sovrascrivi sempre); false durante updateBrokeredUser
     *                 (heal-only: scrivi solo se il campo utente e vuoto / username e OID-like).
     */
    private void applyContextToUser(UserModel user, BrokeredIdentityContext context,
                                    Map<String, String> cfg, boolean trustEmail, boolean onCreate) {
        String email = context.getEmail();
        String firstName = context.getFirstName();
        String lastName = context.getLastName();

        if (email != null && (onCreate || isBlank(user.getEmail()))) {
            user.setEmail(email);
            if (trustEmail) {
                user.setEmailVerified(true);
            }
        }
        if (firstName != null && (onCreate || isBlank(user.getFirstName()))) {
            user.setFirstName(firstName);
        }
        if (lastName != null && (onCreate || isBlank(user.getLastName()))) {
            user.setLastName(lastName);
        }

        if (USERNAME_SOURCE_EMAIL.equals(usernameSource(cfg)) && email != null) {
            String currentUsername = user.getUsername();
            if (onCreate || isOidLike(currentUsername)) {
                user.setUsername(email);
            }
        }
    }

    /** Seam per i test: permette di iniettare un GraphClient mocked. */
    protected GraphClient createGraphClient(String tenantId, String clientId, String clientSecret,
                                            int timeoutMs, int retryCount) {
        return new GraphClient(tenantId, clientId, clientSecret, timeoutMs, retryCount);
    }

    private static String usernameSource(Map<String, String> cfg) {
        String v = cfg.get(CFG_USERNAME_SOURCE);
        return (v == null || v.isEmpty()) ? USERNAME_SOURCE_EMAIL : v;
    }

    private static boolean isOidLike(String s) {
        return s != null && UUID_PATTERN.matcher(s).matches();
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

    private static boolean parseBool(String s, boolean fallback) {
        if (s == null || s.isEmpty()) return fallback;
        return Boolean.parseBoolean(s.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
