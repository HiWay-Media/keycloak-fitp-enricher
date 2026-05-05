# Migliorie proposte

Analisi delle criticità e delle migliorie individuate sul codebase attuale (`v1.1.0`).  
Ordinate per priorità: prima i bug funzionali, poi le dipendenze obsolete, infine qualità e copertura test.

---

## Bug funzionali

### 1. Cache token statica non è per-tenant/client (BUG — priorità alta)

**File:** `src/main/java/com/hiwaymedia/keycloak/graph/GraphClient.java`

`cachedToken` e `cachedTokenExpiresAt` sono campi `static` condivisi tra tutte le istanze di `GraphClient` nella stessa JVM. In un ambiente multi-realm (o con due mapper configurati con tenant/clientId diversi), il token acquisito per il tenant A sovrascrive quello per il tenant B, causando `401 Unauthorized` su Microsoft Graph al login successivo.

**Codice attuale (problematico):**
```java
private static volatile String cachedToken;
private static volatile Instant cachedTokenExpiresAt = Instant.EPOCH;
private static final Object TOKEN_LOCK = new Object();
```

**Soluzione:** usare una `ConcurrentHashMap` con chiave composta da `tenantId + ":" + clientId`, così ogni coppia tenant/client mantiene il proprio token in cache indipendentemente.

```java
private static final ConcurrentHashMap<String, String> TOKEN_CACHE = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Instant> TOKEN_EXPIRY = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Object> TOKEN_LOCKS = new ConcurrentHashMap<>();

private String cacheKey() {
    return tenantId + ":" + clientId;
}

private String getGraphToken() {
    String key = cacheKey();
    Instant now = Instant.now();
    String tok = TOKEN_CACHE.get(key);
    Instant exp = TOKEN_EXPIRY.getOrDefault(key, Instant.EPOCH);
    if (tok != null && now.isBefore(exp)) return tok;

    Object lock = TOKEN_LOCKS.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
        tok = TOKEN_CACHE.get(key);
        exp = TOKEN_EXPIRY.getOrDefault(key, Instant.EPOCH);
        if (tok != null && Instant.now().isBefore(exp)) return tok;
        return requestNewGraphToken();
    }
}
```

---

### 2. Skip troppo aggressivo in `preprocessFederatedIdentity` (BUG — priorità alta)

**File:** `src/main/java/com/hiwaymedia/keycloak/FitpEnricherIdentityProviderMapper.java`

La guardia iniziale controlla solo la presenza dell'email nel context. Se B2C emette l'email nel JWT ma non `givenName`/`surname`, il metodo esce subito e l'utente viene creato senza nome né cognome.

**Codice attuale (problematico):**
```java
@Override
public void preprocessFederatedIdentity(...) {
    if (context.getEmail() != null && !context.getEmail().isEmpty()) {
        return;  // ← salta anche se firstName/lastName sono assenti
    }
    ...
}
```

**Soluzione:** chiamare Graph anche quando l'email è già presente ma mancano altri campi rilevanti.

```java
@Override
public void preprocessFederatedIdentity(...) {
    boolean emailOk     = context.getEmail() != null && !context.getEmail().isEmpty();
    boolean firstNameOk = context.getFirstName() != null && !context.getFirstName().isEmpty();
    boolean lastNameOk  = context.getLastName() != null && !context.getLastName().isEmpty();

    if (emailOk && firstNameOk && lastNameOk) {
        return; // profilo già completo, skip
    }

    Map<String, Object> data = context.getContextData();
    if (data.containsKey(CONTEXT_MARKER)) {
        return; // già arricchito in questa sessione
    }
    // ... prosegue con la chiamata a Graph
}
```

---

### 3. `userPrincipalName` selezionato ma mai usato in `extractEmail` (BUG — priorità media)

**File:** `src/main/java/com/hiwaymedia/keycloak/graph/GraphClient.java`

Il campo `userPrincipalName` è incluso nel `$select` della query Graph ma non viene mai letto da `extractEmail()`. Per account federated Entra ID in cui `mail` è vuota e `otherMails` è vuoto, il UPN è spesso in formato email (`utente@azienda.it`) e rappresenta la fonte corretta.

**Codice attuale:**
```java
String url = graphBaseUrl + "/v1.0/users/" + oid
        + "?$select=id,mail,otherMails,givenName,surname,displayName,identities,userPrincipalName";

// ...

static String extractEmail(JsonNode data) {
    if (data.hasNonNull("mail")) return data.get("mail").asText();
    // otherMails...
    // identities...
    return null;  // ← userPrincipalName ignorato
}
```

**Soluzione:** aggiungere `userPrincipalName` come ultimo fallback in `extractEmail`, validando che abbia formato email (contiene `@` e non termina con `.onmicrosoft.com` che indica un UPN sintetico B2C).

```java
static String extractEmail(JsonNode data) {
    if (data.hasNonNull("mail")) return data.get("mail").asText();

    JsonNode otherMails = data.get("otherMails");
    if (otherMails != null && otherMails.isArray() && otherMails.size() > 0)
        return otherMails.get(0).asText();

    JsonNode identities = data.get("identities");
    if (identities != null && identities.isArray()) {
        for (JsonNode id : identities) {
            if ("emailAddress".equals(id.path("signInType").asText())
                    && id.hasNonNull("issuerAssignedId"))
                return id.get("issuerAssignedId").asText();
        }
    }

    // Fallback: UPN se formato email e non sintetico B2C
    if (data.hasNonNull("userPrincipalName")) {
        String upn = data.get("userPrincipalName").asText();
        if (upn.contains("@") && !upn.endsWith(".onmicrosoft.com"))
            return upn;
    }

    return null;
}
```

---

## Dipendenze obsolete

### 4. WireMock — groupId e versione obsoleti (priorità alta per sicurezza)

**File:** `pom.xml`

`com.github.tomakehurst:wiremock-jre8-standalone:2.35.1` è il ramo legacy pre-Java 17, abbandonato. Con Java 17 si usa il groupId ufficiale aggiornato.

```xml
<!-- Attuale (obsoleto) -->
<dependency>
    <groupId>com.github.tomakehurst</groupId>
    <artifactId>wiremock-jre8-standalone</artifactId>
    <version>2.35.1</version>
    <scope>test</scope>
</dependency>

<!-- Corretto -->
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock</artifactId>
    <version>3.12.1</version>
    <scope>test</scope>
</dependency>
```

> **Nota:** il cambio di groupId richiede di aggiornare gli import nelle classi di test da `com.github.tomakehurst.wiremock.*` a `com.github.tomakehurst.wiremock.*` — il package Java interno resta lo stesso in WireMock 3.x, quindi gli import non cambiano. Solo il `groupId` Maven/Gradle cambia.

---

### 5. Jackson — versione con CVE minori (priorità media)

**File:** `pom.xml`

Jackson `2.15.2` è superato. La versione corrente è `2.18.3` con fix di CVE minori e miglioramenti al parsing.

```xml
<!-- Attuale -->
<jackson.version>2.15.2</jackson.version>

<!-- Aggiornato -->
<jackson.version>2.18.3</jackson.version>
```

---

### 6. JUnit Jupiter — versione non corrente (priorità bassa)

**File:** `pom.xml`

JUnit `5.10.0` → `5.12.2` (corrente stabile).

```xml
<!-- Attuale -->
<version>5.10.0</version>

<!-- Aggiornato -->
<version>5.12.2</version>
```

---

### 7. Keycloak SPI — versione EOL (priorità alta per compatibilità)

**File:** `pom.xml`

Keycloak `22.0.1` è End-of-Life. Le versioni supportate attivamente sono `25.x` e `26.x`. L'aggiornamento delle dipendenze SPI è a scope `provided` quindi non impatta il jar prodotto, ma garantisce compilazione contro le API correnti ed evita incompatibilità runtime.

```xml
<!-- Attuale -->
<keycloak.version>22.0.1</keycloak.version>

<!-- Aggiornato (verificare compatibilità API SPI prima del deploy) -->
<keycloak.version>26.2.4</keycloak.version>
```

> **Attenzione:** Keycloak 25+ ha introdotto breaking change in alcune SPI (`IdentityProviderMapper`). Verificare che le firme dei metodi override siano compatibili dopo l'aggiornamento.

---

## Qualità e copertura test

### 8. `IdentityProviderSyncMode.INHERIT` non supportato (priorità media)

**File:** `src/main/java/com/hiwaymedia/keycloak/FitpEnricherIdentityProviderMapper.java`

`supportsSyncMode()` non include `INHERIT`, che alcune versioni di Keycloak usano come modalità default per i nuovi mapper. Il mapper potrebbe non essere selezionabile o ignorato silenziosamente.

**Codice attuale:**
```java
@Override
public boolean supportsSyncMode(IdentityProviderSyncMode mode) {
    return mode == IdentityProviderSyncMode.IMPORT
            || mode == IdentityProviderSyncMode.FORCE
            || mode == IdentityProviderSyncMode.LEGACY;
}
```

**Soluzione:**
```java
@Override
public boolean supportsSyncMode(IdentityProviderSyncMode mode) {
    return mode == IdentityProviderSyncMode.IMPORT
            || mode == IdentityProviderSyncMode.FORCE
            || mode == IdentityProviderSyncMode.LEGACY
            || mode == IdentityProviderSyncMode.INHERIT;
}
```

---

### 9. Mapper visibile per tutti gli IdP (`ANY_PROVIDER`) — rumore nella UI (priorità bassa)

**File:** `src/main/java/com/hiwaymedia/keycloak/FitpEnricherIdentityProviderMapper.java`

`COMPATIBLE_PROVIDERS = { ANY_PROVIDER }` fa comparire il mapper nell'elenco di tutti gli IdP (SAML, LDAP, social), generando confusione nella console di amministrazione. Il mapper è specifico per IdP OIDC/B2C.

**Soluzione:** restringere ai provider OIDC:
```java
private static final String[] COMPATIBLE_PROVIDERS = {
    "oidc",          // Generic OIDC
    "keycloak-oidc"  // Keycloak-to-Keycloak
};
```

Se il provider FITP usa un alias custom, l'alias va aggiunto all'array o si mantiene `ANY_PROVIDER` con un warning nella `getHelpText()`.

---

### 10. Test mancanti per `importNewUser` e `updateBrokeredUser` (priorità media)

**File:** `src/test/java/com/hiwaymedia/keycloak/FitpEnricherIdentityProviderMapperTest.java`

I test coprono bene `preprocessFederatedIdentity` ma non verificano la logica `applyContextToUser`:

- `importNewUser`: verifica che email/nome/cognome vengano sempre scritti sul `UserModel` (anche se già presenti).
- `updateBrokeredUser`: verifica la modalità heal-only (sovrascrive solo se il campo è vuoto; non sovrascrive username se già non è OID-like).
- `trustEmail=false`: verifica che `setEmailVerified(true)` non venga chiamato.
- `username.source=oid` su `updateBrokeredUser`: verifica che uno username già email non venga sovrascritto.

**Esempio test mancante:**
```java
@Test
void updateBrokeredUserDoesNotOverwriteExistingEmail() {
    GraphClient stub = mock(GraphClient.class);
    FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);

    BrokeredIdentityContext ctx = freshContext(OID);
    ctx.setEmail("new@graph.it");

    UserModel user = mock(UserModel.class);
    when(user.getEmail()).thenReturn("existing@user.it"); // email già presente

    mapper.updateBrokeredUser(null, null, user,
            modelWith(Map.of()), ctx);

    verify(user, never()).setEmail(anyString()); // non deve sovrascrivere
}

@Test
void importNewUserAlwaysOverwritesEmail() {
    GraphClient stub = mock(GraphClient.class);
    FitpEnricherIdentityProviderMapper mapper = mapperWithStub(stub);

    BrokeredIdentityContext ctx = freshContext(OID);
    ctx.setEmail("graph@b.it");

    UserModel user = mock(UserModel.class);
    when(user.getEmail()).thenReturn("old@user.it");

    mapper.importNewUser(null, null, user, modelWith(Map.of()), ctx);

    verify(user).setEmail("graph@b.it"); // importNewUser sovrascrive sempre
}
```

---

## Riepilogo priorità

| # | Descrizione | Tipo | Priorità |
|---|---|---|---|
| 1 | Cache token statica condivisa tra tenant diversi | Bug | 🔴 Alta |
| 2 | Skip `preprocessFederatedIdentity` su email già presente | Bug | 🔴 Alta |
| 3 | `userPrincipalName` ignorato in `extractEmail` | Bug | 🟡 Media |
| 4 | WireMock groupId e versione obsoleti | Dipendenza | 🔴 Alta |
| 5 | Jackson versione con CVE minori | Dipendenza | 🟡 Media |
| 6 | JUnit versione non corrente | Dipendenza | 🟢 Bassa |
| 7 | Keycloak SPI versione EOL | Dipendenza | 🔴 Alta |
| 8 | `INHERIT` sync mode non supportato | Qualità | 🟡 Media |
| 9 | Mapper visibile per tutti gli IdP | Qualità | 🟢 Bassa |
| 10 | Test mancanti per `importNewUser`/`updateBrokeredUser` | Test | 🟡 Media |
