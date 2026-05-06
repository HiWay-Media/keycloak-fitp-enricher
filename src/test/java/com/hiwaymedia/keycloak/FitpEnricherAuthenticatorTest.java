package com.hiwaymedia.keycloak;

import com.hiwaymedia.keycloak.graph.GraphClient;
import com.hiwaymedia.keycloak.graph.GraphException;
import com.hiwaymedia.keycloak.graph.GraphProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.UserModel;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FitpEnricherAuthenticatorTest {

    private AuthenticationFlowContext ctx;
    private UserModel user;
    private GraphClient graph;
    private FitpEnricherAuthenticator authenticator;

    @BeforeEach
    void setup() {
        ctx = mock(AuthenticationFlowContext.class);
        user = mock(UserModel.class);
        graph = mock(GraphClient.class);
        when(ctx.getUser()).thenReturn(user);

        AuthenticatorConfigModel cfgModel = mock(AuthenticatorConfigModel.class);
        Map<String, String> cfg = new HashMap<>();
        cfg.put(FitpEnricherAuthenticatorFactory.CFG_TENANT_ID, "tenant");
        cfg.put(FitpEnricherAuthenticatorFactory.CFG_CLIENT_ID, "client");
        cfg.put(FitpEnricherAuthenticatorFactory.CFG_CLIENT_SECRET, "secret");
        cfg.put(FitpEnricherAuthenticatorFactory.CFG_TRUST_EMAIL, "true");
        when(cfgModel.getConfig()).thenReturn(cfg);
        when(ctx.getAuthenticatorConfig()).thenReturn(cfgModel);

        authenticator = new FitpEnricherAuthenticator() {
            @Override
            protected GraphClient createGraphClient(String tenantId, String clientId, String clientSecret,
                                                    int timeoutMs, int retryCount) {
                return graph;
            }
        };
    }

    @Test
    void emailGiaPresenteRinominaUsernameSenzaChiamarGraph() throws GraphException {
        when(user.getEmail()).thenReturn("a@b.it");
        when(user.getUsername()).thenReturn("ffc44582-ceed-43c7-9c40-0f5b68b3e107");

        authenticator.authenticate(ctx);

        verify(graph, never()).fetchUserByOid(anyString());
        verify(user).setUsername("a@b.it");
        verify(ctx).success();
    }

    @Test
    void emailGiaPresenteEUsernameGiaUgualeNonRinomina() {
        when(user.getEmail()).thenReturn("a@b.it");
        when(user.getUsername()).thenReturn("a@b.it");

        authenticator.authenticate(ctx);

        verify(user, never()).setUsername(anyString());
        verify(ctx).success();
    }

    @Test
    void emailAssenteFetchaDaGraphEPoiRinomina() throws GraphException {
        when(user.getEmail()).thenReturn(null).thenReturn("u@v.it");
        when(user.getUsername()).thenReturn("ffc44582-ceed-43c7-9c40-0f5b68b3e107");
        when(graph.fetchUserByOid(anyString()))
                .thenReturn(new GraphProfile("u@v.it", "Mario", "Rossi"));

        authenticator.authenticate(ctx);

        verify(user).setEmail("u@v.it");
        verify(user).setEmailVerified(true);
        verify(user).setFirstName("Mario");
        verify(user).setLastName("Rossi");
        verify(user).setUsername("u@v.it");
        verify(ctx).success();
    }

    @Test
    void erroreGraphConFailOnErrorChiudeIlFlussoConFailure() throws GraphException {
        when(user.getEmail()).thenReturn(null);
        when(user.getUsername()).thenReturn("ffc44582-ceed-43c7-9c40-0f5b68b3e107");
        when(graph.fetchUserByOid(anyString()))
                .thenThrow(new GraphException("boom", 500));

        AuthenticatorConfigModel cfgModel = ctx.getAuthenticatorConfig();
        cfgModel.getConfig().put(FitpEnricherAuthenticatorFactory.CFG_FAIL_ON_ERROR, "true");

        authenticator.authenticate(ctx);

        verify(ctx).failure(AuthenticationFlowError.INTERNAL_ERROR);
        verify(user, never()).setUsername(anyString());
    }
}
