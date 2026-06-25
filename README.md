# FITP Profile Enricher

> Versione corrente: **0.3.1**

Plugin Keycloak che, sul **primo login** via FITP/B2C, chiama Microsoft Graph per
recuperare `email`, `firstName`, `lastName` e li inietta nel *brokered identity context*
**prima** della creazione utente, così che creazione e deduplica per email funzionino
anche quando Azure AD B2C non emette l'email nel token.

Risolve il primo login fallito (B2C senza email) e i **duplicati di account** (la
deduplica per email è possibile solo se l'email è presente al broker-time).

Il plugin espone **un solo componente**: l'Authenticator `fitp-broker-enricher`, da
inserire come **primo step (REQUIRED)** del First Broker Login flow dell'IdP FITP,
prima di `Create User If Unique`.

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
├── docker-compose.yml              # dev locale (Keycloak 22.0.1)
├── docker-compose.test.yml         # smoke test parametrico per matrice di versioni
├── docs/
│   ├── OPERATIONS.md
│   └── ARCHITECTURE.md
├── scripts/
│   ├── smoke-test.sh               # verifica via Admin REST API che il provider sia registrato
│   └── test-matrix.sh              # itera lo smoke test su piu versioni di Keycloak
├── .github/workflows/
│   ├── ci.yml                      # build PR/push
│   ├── keycloak-compat.yml         # matrice di compatibilita Keycloak 22 -> 26
│   └── release.yml
└── src/main/
    ├── java/com/hiwaymedia/keycloak/
    │   ├── broker/FitpBrokerEnricherAuthenticator.java
    │   ├── broker/FitpBrokerEnricherAuthenticatorFactory.java
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

Output: `build/libs/fitp-enricher-0.3.1.jar` (Gradle) o `target/fitp-enricher-0.3.1.jar` (Maven).

## Deploy

1. Copia il jar in `providers/` del runtime Keycloak.
2. Esegui `kc.sh build` per registrare gli SPI.
3. Riavvia Keycloak.

## Configurazione in Keycloak

### 1. Inserisci l'enricher nel First Broker Login flow

Lo step va **prima** di `Create User If Unique`. Poiché un elemento `REQUIRED` disabilita
gli `ALTERNATIVE` allo stesso livello, i due step di linking vanno in un **sub-flow REQUIRED**:

```
First Broker Login flow
├─ FITP Broker Enricher                 REQUIRED   ← questo plugin
└─ sub-flow "create-or-link"            REQUIRED
   ├─ Create User If Unique             ALTERNATIVE
   └─ Automatically set existing user   ALTERNATIVE
```

Configura lo step `FITP Broker Enricher`:
  - `Azure Tenant ID`
  - `App Registration Client ID`
  - `App Registration Client Secret`
  - `Timeout HTTP (ms)` — default `8000`
  - `Numero retry` — default `1`
  - `Blocca login in caso di errore` — default `On` (fail-closed)
  - `Username dell'utente Keycloak` — default `email`

### 2. Aggancia il flow all'IdP FITP

- **Identity providers > FITP > First login flow** = il flow sopra.
- **Post login flow** = `none` (l'enrichment non è più post-login).
- Per marcare l'email come verificata: **Advanced settings > Trust Email = On**.

## Test di compatibilità

Verifica che il jar si carichi correttamente e che `fitp-broker-enricher` sia registrato nell'Admin REST API di Keycloak (`/admin/realms/master/authentication/authenticator-providers`) con tutte le 7 `ProviderConfigProperty`. È uno smoke test di **binary compatibility**: se l'SPI di Keycloak cambia firma in una versione futura, il provider non viene caricato e il test fallisce.

### Prerequisiti

- Docker daemon in esecuzione
- Jar compilato in `build/libs/fitp-enricher-0.3.1.jar` (`gradle build`) oppure `target/fitp-enricher-0.3.1.jar` (`mvn clean package` + `JAR_DIR=./target`)

### Singola versione

```bash
gradle build
docker compose -f docker-compose.test.yml up --abort-on-container-exit --exit-code-from smoke-tests
# altra versione:
KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:25.0.6 \
  docker compose -f docker-compose.test.yml up --abort-on-container-exit --exit-code-from smoke-tests
```

### Matrice di versioni in locale

```bash
./scripts/test-matrix.sh
# matrice custom:
IMAGES="quay.io/keycloak/keycloak:24.0.5 quay.io/keycloak/keycloak:26.1" ./scripts/test-matrix.sh
```

Default testati: `22.0.5`, `23.0.7`, `24.0.5`, `25.0.6`, `26.0`. Output: riepilogo PASS/FAIL per versione, exit code `1` se almeno una fallisce.

### Matrice in CI (GitHub Actions)

Il workflow [.github/workflows/keycloak-compat.yml](.github/workflows/keycloak-compat.yml) gira su push `main`, su ogni PR e via `workflow_dispatch`. Per ogni versione della matrice:

1. Builda il jar (`gradle build -x test`) e lo carica come artifact.
2. Avvia Postgres + Keycloak + plugin via `docker-compose.test.yml`.
3. Stampa nel log della Action il JSON di `fitp-broker-enricher` da admin provider info e da `config-description` (gruppi collassabili).
4. Esegue lo `scripts/smoke-test.sh` per verificare le 7 `ProviderConfigProperty`.
5. Su failure dumpa i log di Keycloak e dello smoke-test.

### Variabili supportate

| Variabile | Default | Note |
|---|---|---|
| `KEYCLOAK_IMAGE` | `quay.io/keycloak/keycloak:26.0` | immagine Keycloak da testare |
| `PLUGIN_JAR` | `fitp-enricher-0.3.1.jar` | nome del jar |
| `JAR_DIR` | `./build/libs` | usa `./target` per Maven |
| `IMAGES` | (lista default in `test-matrix.sh`) | override matrice locale |

## Comportamento

- Gira solo al **primo login** di un utente FITP (i login successivi matchano via federated link e non passano dal First Broker Login flow → nessuna chiamata Graph per-login).
- Recupera email/firstName/lastName da Microsoft Graph usando l'OID del brokered identity e li **inietta nel brokered context** prima di `Create User If Unique`.
- Con `Username dell'utente Keycloak = email`, imposta anche lo `username` del nuovo utente all'email (coerente con *Email as username*).
- **Token Graph cachato** in memoria (~1h).
- **Retry breve** su timeout / 429 / 503, fino a `retryCount` volte con backoff fisso 250ms. Mai retry su 401/403/404.
- **Fail-closed** (default `On`): se Graph è down o non restituisce email, il login si interrompe invece di creare un utente senza email (che fallirebbe la creazione e genererebbe duplicati). Con `Off` lascia proseguire il flow.

## Troubleshooting

Filtra i log Keycloak su:
- `com.hiwaymedia.keycloak.broker.FitpBrokerEnricherAuthenticator`
- `com.hiwaymedia.keycloak.graph.GraphClient`

Errori comuni:
- `401` su token endpoint: secret scaduto/non valido.
- `403` da Graph: permessi app insufficienti o admin consent mancante.
- `404` da Graph: tenant o oid errato.

## Documentazione correlata

- [CHANGELOG.md](CHANGELOG.md) — storico delle versioni.
- [docs/OPERATIONS.md](docs/OPERATIONS.md) — runbook di build, deploy e verifica.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — dettagli architetturali.
