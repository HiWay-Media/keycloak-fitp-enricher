package com.hiwaymedia.keycloak.broker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FitpBrokerEnricherAuthenticatorTest {

    @Test
    void stripsAliasPrefixFromId() {
        // caso dev: id prefissato con "<alias>."
        assertEquals("f336f24e-083e-4749-b3e4-066c64a8ef44",
                FitpBrokerEnricherAuthenticator.resolveOid(
                        "fitp.f336f24e-083e-4749-b3e4-066c64a8ef44", null, "fitp"));
    }

    @Test
    void leavesBareSubUnchanged() {
        // caso prod: sub gia nudo, nessun prefisso da togliere
        assertEquals("30721002-c19f-4348-93e3-17cbe9eab029",
                FitpBrokerEnricherAuthenticator.resolveOid(
                        "30721002-c19f-4348-93e3-17cbe9eab029", null, "fitp"));
    }

    @Test
    void doesNotStripWhenPrefixIsNotTheAlias() {
        // "fitp." non e' l'alias (alias = "altro") -> nessuno strip
        assertEquals("fitp.abc", FitpBrokerEnricherAuthenticator.resolveOid("fitp.abc", null, "altro"));
    }

    @Test
    void fallsBackToBrokerUserIdWhenIdBlank() {
        assertEquals("abc", FitpBrokerEnricherAuthenticator.resolveOid(null, "abc", "fitp"));
    }

    @Test
    void handlesNullAlias() {
        assertEquals("fitp.abc", FitpBrokerEnricherAuthenticator.resolveOid("fitp.abc", null, null));
    }

    @Test
    void returnsNullWhenNothingAvailable() {
        assertNull(FitpBrokerEnricherAuthenticator.resolveOid(null, null, "fitp"));
    }
}
