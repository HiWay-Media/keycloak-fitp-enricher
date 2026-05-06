# FITP Profile Enricher

> Versione corrente: **0.2.0**

Plugin Keycloak che, su login via FITP/B2C, chiama Microsoft Graph per recuperare
`email`, `firstName`, `lastName` dell'utente, li popola sull'utente Keycloak e
imposta sempre `username = email`.

Risolve il caso in cui Azure AD B2C non emette questi claim nel token, e impedisce
che lo username resti l'OID/UUID di B2C.

Il plugin espone **un solo componente**: l'Authenticator `fitp-enricher`, da inserire
in un Post Login Flow sull'IdP FITP.

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
├── CHANGELOG.md
├── docs/
│   ├── OPERATIONS.md
│   └── ARCHITECTURE.md
└── src/main/
    ├── java/com/hiwaymedia/keycloak/
    │   ├── FitpEnricherAuthenticator.java
    │   ├── FitpEnricherAuthenticatorFactory.java
    │   └── graph/
    │       ├── GraphClient.java
    │       ├── GraphProfile.java
    │       └── GraphException.java
    └── resources/META-INF/services/
        └── org.keycloak.authentication.AuthenticatorFactory
```

## Build

```bash
gradle clean build
# oppure
mvn clean package
```

Output: `build/libs/fitp-enricher-0.2.0.jar` (Gradle) o `target/fitp-enricher-0.2.0.jar` (Maven).

## Deploy

1. Copia il jar in `providers/` del runtime Keycloak.
2. Esegui `kc.sh build` per registrare gli SPI.
3. Riavvia Keycloak.

## Configurazione in Keycloak

### 1. Crea il Post Login Flow

- **Authentication > Flows > Create flow**
- Nome: `fitp post login`, Top level flow type: `Generic`.
- Aggiungi step `FITP Profile Enricher` con Requirement `REQUIRED`.
- Configura lo step:
  - `Azure Tenant ID`
  - `App Registration Client ID`
  - `App Registration Client Secret`
  - `Timeout HTTP (ms)` — default `8000`
  - `Numero retry` — default `1`
  - `Blocca login in caso di errore` — default `Off`
  - `Marca email come verificata` — default `On`

### 2. Aggancia il flow all'IdP FITP

- **Identity providers > FITP > Advanced settings > Post login flow** = `fitp post login`.

## Comportamento

- Su ogni login via FITP, l'authenticator gira nel Post Login Flow.
- Se l'utente non ha email, viene chiamata Microsoft Graph per recuperare email/firstName/lastName.
- Se l'email è valorizzata e diversa dallo username corrente, lo `username` viene riallineato a `email`. Il rename gira **anche quando il fetch Graph è skippato** (email già presente), così gli utenti esistenti con username = OID/UUID vengono guariti automaticamente al loro prossimo login.
- **Token Graph cachato** in memoria (~1h).
- **Retry breve** su timeout / 429 / 503, fino a `retryCount` volte con backoff fisso 250ms. Mai retry su 401/403/404.
- **Fail-safe**: con `Blocca login in caso di errore = Off` il login passa anche se Graph è down (utente con campi vuoti). Con `On` si interrompe il flow.

## Troubleshooting

Filtra i log Keycloak su:
- `com.hiwaymedia.keycloak.FitpEnricherAuthenticator`
- `com.hiwaymedia.keycloak.graph.GraphClient`

Errori comuni:
- `401` su token endpoint: secret scaduto/non valido.
- `403` da Graph: permessi app insufficienti o admin consent mancante.
- `404` da Graph: tenant o oid errato.

## Documentazione correlata

- [CHANGELOG.md](CHANGELOG.md) — storico delle versioni.
- [docs/OPERATIONS.md](docs/OPERATIONS.md) — runbook di build, deploy e verifica.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — dettagli architetturali.
