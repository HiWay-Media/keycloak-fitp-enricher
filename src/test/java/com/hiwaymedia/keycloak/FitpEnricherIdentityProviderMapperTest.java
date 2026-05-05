package com.hiwaymedia.keycloak;

import com.hiwaymedia.keycloak.graph.GraphClient;
import com.hiwaymedia.keycloak.graph.GraphException;
import com.hiwaymedia.keycloak.graph.GraphProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.UserModel;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FitpEnricherIdentityProviderMapperTest {

    private static final String OID = "ffc44582-ceed-43c7-9c40-0f5b68b3e107";

    private FitpEnricherIdentityProviderMapper mapperWithStub(GraphClient stub) {
        return new FitpEnricherIdentityProviderMapper() {
            @Override
            protected GraphClient createGraphClient(String tenantId, String clientId, String clientSecret,
                                                    int timeoutMs, int retryCount) {
                return stub;
            }
        };
    }

    private IdentityProviderMapperModel modelWith(Map<String, String> overrides) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put(FitpEnricherIdentityProviderMapper.CFG_TENANT_ID, "tenant");
        cfg.put(FitpEnricherIdentityProviderMapper.CFG_CLIENT_ID, "cid");
        cfg.put(FitpEnricherIdentityProviderMapper.CFG_CLIENT_SECRET, "secret");
        cfg.putAll(overrides);
        IdentityProviderMapperModel m = new IdentityProviderMapperModel();
        m.setConfig(cfg);
        return m;
    }

    private BrokeredIdentityContext freshContext(String oid) {
        BrokeredIdentityContext ctx = new BrokeredIdentityContext(oid);
        return ctx;
    }

    @Test
    void preprocessSetsEmailFirstNameLastNameAndUsername() {
        GraphClient stub = mock(GraphClient.class);
        when(stub.fetchUserByOid(OID))
                .thenReturn(new GraphProfile("a@b.it", "Anna", "Bianchi"));

        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);
        BrokeredIdentityContext ctx = freshContext(OID);

        mapper.preprocessFederatedIdentity(null, null, modelWith(Map.of()), ctx);

        assertEquals("a@b.it", ctx.getEmail());
        assertEquals("Anna", ctx.getFirstName());
        assertEquals("Bianchi", ctx.getLastName());
        assertEquals("a@b.it", ctx.getModelUsername());
    }

    @Test
    void preprocessSkipsWhenEmailAlreadyPresent() {
        GraphClient stub = mock(GraphClient.class);
        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);

        BrokeredIdentityContext ctx = freshContext(OID);
        ctx.setEmail("already@there.it");

        mapper.preprocessFederatedIdentity(null, null, modelWith(Map.of()), ctx);

        verify(stub, never()).fetchUserByOid(anyString());
        assertEquals("already@there.it", ctx.getEmail());
    }

    @Test
    void preprocessSkipsOnSecondCallSameContext() {
        GraphClient stub = mock(GraphClient.class);
        when(stub.fetchUserByOid(OID))
                .thenReturn(new GraphProfile("a@b.it", "A", "B"));

        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);
        BrokeredIdentityContext ctx = freshContext(OID);

        IdentityProviderMapperModel m = modelWith(Map.of());
        mapper.preprocessFederatedIdentity(null, null, m, ctx);

        // Simula re-entry: rimuovo l'email per dimostrare che NON ricalcola se gia marcato
        ctx.setEmail(null);
        mapper.preprocessFederatedIdentity(null, null, m, ctx);

        verify(stub, times(1)).fetchUserByOid(OID);
    }

    @Test
    void preprocessKeepsOidUsernameWhenSourceIsOid() {
        GraphClient stub = mock(GraphClient.class);
        when(stub.fetchUserByOid(OID))
                .thenReturn(new GraphProfile("a@b.it", "A", "B"));

        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);
        BrokeredIdentityContext ctx = freshContext(OID);

        mapper.preprocessFederatedIdentity(null, null,
                modelWith(Map.of(FitpEnricherIdentityProviderMapper.CFG_USERNAME_SOURCE, "oid")), ctx);

        assertEquals("a@b.it", ctx.getEmail());
        assertNull(ctx.getModelUsername());
    }

    @Test
    void preprocessSwallowsErrorWhenFailOnErrorOff() {
        GraphClient stub = mock(GraphClient.class);
        when(stub.fetchUserByOid(OID))
                .thenThrow(new GraphException("boom", 500));

        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);
        BrokeredIdentityContext ctx = freshContext(OID);

        mapper.preprocessFederatedIdentity(null, null,
                modelWith(Map.of(FitpEnricherIdentityProviderMapper.CFG_FAIL_ON_ERROR, "false")), ctx);

        assertNull(ctx.getEmail());
    }

    @Test
    void preprocessThrowsWhenFailOnErrorOn() {
        GraphClient stub = mock(GraphClient.class);
        when(stub.fetchUserByOid(OID))
                .thenThrow(new GraphException("boom", 500));

        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);
        BrokeredIdentityContext ctx = freshContext(OID);

        assertThrows(IdentityBrokerException.class, () ->
                mapper.preprocessFederatedIdentity(null, null,
                        modelWith(Map.of(FitpEnricherIdentityProviderMapper.CFG_FAIL_ON_ERROR, "true")), ctx));
    }

    @Test
    void importNewUserAppliesEmailVerifiedAndUsernameWhenSourceEmail() {
        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(mock(GraphClient.class));
        BrokeredIdentityContext ctx = freshContext(OID);
        ctx.setEmail("u@v.it");
        ctx.setFirstName("U");
        ctx.setLastName("V");

        UserModel user = mock(UserModel.class);
        when(user.getUsername()).thenReturn(OID);

        mapper.importNewUser(null, null, user, modelWith(Map.of()), ctx);

        verify(user).setEmail("u@v.it");
        verify(user).setEmailVerified(true);
        verify(user).setFirstName("U");
        verify(user).setLastName("V");
        verify(user).setUsername("u@v.it");
    }

    @Test
    void updateBrokeredUserHealsEmptyFieldsOnly() {
        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(mock(GraphClient.class));
        BrokeredIdentityContext ctx = freshContext(OID);
        ctx.setEmail("u@v.it");
        ctx.setFirstName("U");
        ctx.setLastName("V");

        UserModel user = mock(UserModel.class);
        when(user.getUsername()).thenReturn(OID);
        when(user.getEmail()).thenReturn(""); // vuoto -> heal
        when(user.getFirstName()).thenReturn("Existing"); // popolato -> NON sovrascrivere
        when(user.getLastName()).thenReturn("");

        mapper.updateBrokeredUser(null, null, user, modelWith(Map.of()), ctx);

        verify(user).setEmail("u@v.it");
        verify(user, never()).setFirstName(anyString());
        verify(user).setLastName("V");
        verify(user).setUsername("u@v.it"); // username era OID-like -> heal
    }

    @Test
    void updateBrokeredUserDoesNotOverwriteCustomUsername() {
        FitpEnricherIdentityProviderMapper mapper = mapperWithStub(mock(GraphClient.class));
        BrokeredIdentityContext ctx = freshContext(OID);
        ctx.setEmail("u@v.it");

        UserModel user = mock(UserModel.class);
        when(user.getUsername()).thenReturn("custom-username"); // non OID-like
        when(user.getEmail()).thenReturn("");

        mapper.updateBrokeredUser(null, null, user, modelWith(Map.of()), ctx);

        verify(user, never()).setUsername(anyString());
    }
}
