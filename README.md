# FITP Profile Enricher

Keycloak Authenticator SPI che, dopo il login via FITP/B2C, chiama Microsoft Graph
per recuperare email, firstName e lastName dell'utente e popolarli sull'utente Keycloak.

Risolve il caso in cui Azure AD B2C non emette questi claim nel token e non vogliamo
gestire il problema lato applicazione.

## Requisiti

- Keycloak 22.0.1 (Quarkus distribution)
- Java 17
- Gradle 8.x (oppure usare il wrapper `./gradlew` se presente)
- Un'app registration Azure con permission Microsoft Graph `User.Read.All` di tipo
  **Application** (non Delegated) e admin consent concesso

## Struttura

```
fitp-enricher/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── README.md
└── src/main/
    ├── java/com/hiwaymedia/keycloak/
    │   ├── FitpEnricherAuthenticator.java
    │   └── FitpEnricherAuthenticatorFactory.java
    └── resources/
        └── META-INF/services/
            └── org.keycloak.authentication.AuthenticatorFactory
```

## Build

```bash
gradle clean build
```

Genera `build/libs/fitp-enricher-1.0.0.jar`.

Se preferisci usare il Gradle wrapper (consigliato per CI/CD), generalo una volta:

```bash
gradle wrapper --gradle-version 8.5
```

Poi puoi sempre buildare con:

```bash
./gradlew clean build
```

## Deploy nell'immagine Keycloak

Builda una nuova immagine Docker basata su quella custom esistente:

```bash
docker build -t ghcr.io/hiway-media/keycloak-apple-identity-provider-druid-22.0.1-dev:v0.3.0 .
docker push ghcr.io/hiway-media/keycloak-apple-identity-provider-druid-22.0.1-dev:v0.3.0
```

Poi aggiorna il tag nella job definition Nomad (campo `Config.image`) e rideploy.

## Configurazione in Keycloak

### 1. Crea un Authentication Flow

- **Authentication > Flows > Create flow**
- Name: `fitp post login`
- Top level flow type: `Basic flow`

### 2. Aggiungi lo step

- **Add step > FITP Profile Enricher**
- Imposta requirement: **Required**
- Click ⚙️ e compila Tenant ID, Client ID, Client Secret

### 3. Aggancia il flow al provider FITP

- **Identity providers > FITP > Advanced settings**
- **Post login flow**: `fitp post login`
- **Save**

## Comportamento

- Idempotente (skip se l'utente ha gia l'email)
- Token Graph cachato in memoria (~1h)
- Fail-safe: in caso di errore Graph, default e lasciar passare il login

## Troubleshooting

Filtra i log Keycloak per `com.hiwaymedia.keycloak.FitpEnricherAuthenticator`.

Vedi tabella errori nel codice o nel commit di setup.
