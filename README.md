# FITP Profile Enricher

Plugin Keycloak che, su login via FITP/B2C, chiama Microsoft Graph per recuperare
`email`, `firstName`, `lastName` dell'utente e li popola sull'utente Keycloak.

Risolve il caso in cui Azure AD B2C non emette questi claim nel token.

Da v1.1.0 il punto di ingresso primario e' un **Identity Provider Mapper** che gira
PRIMA del First Login Flow, garantendo che il primo login non fallisca con
`KC-SERVICES0020: Email is null`. Imposta anche `username = email` (configurabile).

Il vecchio Authenticator (`fitp-enricher`) in Post Login Flow e' deprecato ma ancora
disponibile per healing di utenti esistenti con record vuoto.

## Requisiti

- Keycloak 22.0.1 (Quarkus distribution)
- Java 17
- Gradle 8.x oppure Maven 3.9+
- Un'app registration Azure con permission Microsoft Graph `User.Read.All` di tipo
  **Application** (non Delegated) e admin consent concesso

## Struttura

```
fitp-enricher/
├── build.gradle.kts
├── pom.xml
├── settings.gradle.kts
├── README.md
├── docs/OPERATIONS.md
└── src/main/
    ├── java/com/hiwaymedia/keycloak/
    │   ├── FitpEnricherIdentityProviderMapper.java   (mapper, primario)
    │   ├── FitpEnricherAuthenticator.java            (deprecato)
    │   ├── FitpEnricherAuthenticatorFactory.java     (deprecato)
    │   └── graph/
    │       ├── GraphClient.java
    │       ├── GraphProfile.java
    │       └── GraphException.java
    └── resources/META-INF/services/
        ├── org.keycloak.broker.provider.IdentityProviderMapper
        └── org.keycloak.authentication.AuthenticatorFactory
```

## Build

```bash
gradle clean build
# oppure
mvn clean package
```

Output:
- Gradle: `build/libs/fitp-enricher-1.1.0.jar`
- Maven: `target/fitp-enricher-1.1.0.jar`

## Deploy

1. Copia il jar in `providers/` del runtime Keycloak.
2. Esegui `kc.sh build` per registrare gli SPI.
3. Riavvia Keycloak.

In alternativa builda l'immagine Docker custom e ridistribuiscila sul cluster.

## Configurazione in Keycloak (path primario, raccomandato)

### 1. Aggiungi il mapper sull'IdP FITP

- **Identity providers > FITP > Mappers > Add mapper**
- Mapper type: `FITP Profile Enricher Mapper`
- Sync mode: `Force` (cosi anche utenti gia esistenti con record vuoto vengono guariti al login successivo)
- Compila:
  - `Azure Tenant ID`
  - `App Registration Client ID`
  - `App Registration Client Secret`
  - `Timeout HTTP (ms)` — default `8000`
  - `Numero retry` — default `1`
  - `Blocca login in caso di errore` — default `Off`
  - `Marca email come verificata` — default `On`
  - `Username dell'utente Keycloak` — `email` (default) o `oid`

### 2. Verifica le advanced settings dell'IdP

- `Trust Email` puo' restare `Off`: il mapper imposta `emailVerified=true` direttamente sull'utente quando `Marca email come verificata` e' `On`.
- `Sync mode`: `Import` o `Force`. Per healing di utenti pre-esistenti scegli `Force`.

## Comportamento

- **Idempotente**: se l'email e' gia presente nel `BrokeredIdentityContext` (es. re-broker), salta la chiamata Graph.
- **Token Graph cachato** in memoria (~1h) condiviso tra mapper e authenticator.
- **Retry breve** su timeout / 429 / 503, fino a `retryCount` volte con backoff fisso 250ms. Mai retry su 401/403/404.
- **Fail-safe**: con `Blocca login in caso di errore = Off` il login passa anche se Graph e' down (utente con campi vuoti). Con `On` si interrompe il flow lanciando `IdentityBrokerException`.

## Migrazione da v1.0 (Authenticator in Post Login Flow)

1. Builda e deploya il jar 1.1.0.
2. Aggiungi il mapper sull'IdP FITP come descritto sopra.
3. Verifica un primo login da utente nuovo: lo username deve essere l'email, il record completo, niente `KC-SERVICES0020` nei log.
4. Una volta verificato, puoi rimuovere lo step `FITP Profile Enricher` dal flow `fitp post login` (Identity providers > FITP > Advanced settings > Post login flow).

## Path legacy/fallback (deprecato)

Il vecchio Authenticator in Post Login Flow rimane registrato per compatibilita binaria.
NON risolve il primo login fallito. Utile solo per "healing" di utenti gia creati con
record vuoto. Verra rimosso in v2.0.0.

## Troubleshooting

Filtra i log Keycloak su:
- `com.hiwaymedia.keycloak.FitpEnricherIdentityProviderMapper`
- `com.hiwaymedia.keycloak.graph.GraphClient`
- `com.hiwaymedia.keycloak.FitpEnricherAuthenticator` (legacy)

Errori comuni:
- `401` su token endpoint: secret scaduto/non valido.
- `403` da Graph: permessi app insufficienti o admin consent mancante.
- `404` da Graph: tenant o oid errato.
