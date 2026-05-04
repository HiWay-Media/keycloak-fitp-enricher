# FITP Profile Enricher

Keycloak Authenticator SPI che, dopo il login via FITP/B2C, chiama Microsoft Graph
per recuperare email, firstName e lastName dell'utente e popolarli sull'utente Keycloak.

Risolve il caso in cui Azure AD B2C non emette questi claim nel token e non vogliamo
gestire il problema lato applicazione.

## Requisiti

- Keycloak 22.0.1 (Quarkus distribution)
- Java 17
- Maven 3.9+
- Un'app registration Azure con permission Microsoft Graph `User.Read.All` di tipo
  **Application** (non Delegated) e admin consent concesso

## Struttura

```
fitp-enricher/
├── pom.xml
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
mvn clean package
```

Genera `target/fitp-enricher-1.0.0.jar`.

## Deploy nell'immagine Keycloak

Builda una nuova immagine Docker basata su quella custom esistente:

```bash
docker build -t ghcr.io/hiway-media/keycloak-apple-identity-provider-druid-22.0.1-dev:v0.3.0 .
docker push ghcr.io/hiway-media/keycloak-apple-identity-provider-druid-22.0.1-dev:v0.3.0
```

Poi aggiorna il tag nella job definition Nomad (campo `Config.image`) e rideploy.

Il primo boot del nuovo container sara piu lento del solito (~30-60s extra) perche
Keycloak rifa l'augmentation Quarkus per registrare il nuovo provider.

## Configurazione in Keycloak

### 1. Crea un Authentication Flow

- **Authentication > Flows > Create flow**
- Name: `fitp post login`
- Description: `Arricchisce profilo via Microsoft Graph dopo login FITP`
- Top level flow type: `Basic flow`

### 2. Aggiungi lo step

Dentro al flow:

- **Add step > FITP Profile Enricher**
- Imposta requirement: **Required**
- Click sull'icona ⚙️ accanto allo step e compila:
  - **Azure Tenant ID**: il tenant ID (GUID o domain)
  - **App Registration Client ID**: il client ID dell'app
  - **App Registration Client Secret**: il client secret (mascherato)
  - **Timeout HTTP (ms)**: 5000 (default OK)
  - **Blocca login in caso di errore**: OFF (default; mettilo ON solo se preferisci
    che il login fallisca se Graph e irraggiungibile)
  - **Marca email come verificata**: ON (default; sicuro perche B2C verifica le email
    in fase di signup)

### 3. Aggancia il flow al provider FITP

- **Identity providers > FITP > Advanced settings**
- **Post login flow**: seleziona `fitp post login`
- **Save**

Lascia `First login flow` invariato (es. `Multiple Identity Provider`).

## Test

1. Cancella eventuali utenti "nudi" (con solo username GUID, senza email) creati dai
   tentativi precedenti
2. Apri una finestra anonima
3. Esegui il login dalla tua app via FITP
4. Verifica in Keycloak admin > Users che l'utente abbia email, firstName e lastName
   popolati

## Troubleshooting

Filtra i log Keycloak per il logger `com.hiwaymedia.keycloak.FitpEnricherAuthenticator`.

| Log | Significato | Fix |
|---|---|---|
| `Profilo arricchito oid=... email=...` | OK | Niente |
| `Token endpoint status=401` | client secret sbagliato/scaduto | Rigenera il secret in Azure e aggiorna la config |
| `Token endpoint status=400 AADSTS700016` | client ID sbagliato o app non esiste in quel tenant | Verifica tenantId/clientId |
| `Graph status=403 Insufficient privileges` | manca `User.Read.All` Application o admin consent | Aggiungi/concedi in Azure |
| `Graph status=404` | l'oid non esiste nel tenant | Verifica che il tenant configurato sia quello dove vivono gli utenti |

## Comportamento

- **Idempotente**: lo step verifica se l'utente ha gia l'email valorizzata. Se si,
  esce subito senza chiamare Graph. Quindi gira "davvero" solo al primo login dopo
  la creazione dell'utente.
- **Token cache**: il token Graph (validita 1h) e cachato in memoria del processo
  Keycloak. In ambiente cluster ogni nodo ha la propria cache, ma e comunque ~1
  chiamata token endpoint/ora/nodo, irrilevante.
- **Fail-safe**: in caso di errore Graph (timeout, 5xx, ecc.) di default il login
  prosegue comunque (utente nudo). Se preferisci bloccare, abilita "Blocca login in
  caso di errore" nella config.

## Sicurezza

Il client secret viene salvato nel DB Keycloak (tabella `authenticator_config_entry`)
in chiaro, mascherato solo nella UI. Per produzione considera l'uso di Vault SPI
Keycloak per esternalizzare il secret.
