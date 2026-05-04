# FITP Profile Enricher

Authenticator SPI per Keycloak che, dopo login via FITP/B2C, chiama Microsoft Graph
e valorizza automaticamente i campi utente:

- email
- firstName
- lastName

Obiettivo: evitare di gestire lato applicazione i casi in cui i claim profilo non
arrivano nel token dal provider esterno.

## Cosa fa

Il provider viene eseguito nel Post Login Flow del provider FITP.

1. Legge l'utente brokered creato/aggiornato da Keycloak.
2. Se l'utente ha gia email, esce subito (idempotente).
3. Se mancano dati, usa client credentials verso Microsoft Graph.
4. Recupera i dati profilo e aggiorna utente Keycloak.

## Requisiti

- Keycloak 22.0.1 (Quarkus distribution)
- Java 17
- Maven 3.9+
- App registration Azure/Entra con:
  - permission Microsoft Graph `User.Read.All` tipo Application
  - admin consent concesso

## Struttura progetto

```text
.
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/hiwaymedia/keycloak/
    │   ├── FitpEnricherAuthenticator.java
    │   └── FitpEnricherAuthenticatorFactory.java
    └── resources/META-INF/services/
        └── org.keycloak.authentication.AuthenticatorFactory
```

## Build

```bash
mvn clean package
```

Output: `target/fitp-enricher-1.0.0.jar`

## Installazione nel runtime Keycloak

### Opzione A: Keycloak container

1. Copia il jar in `/opt/keycloak/providers/` nell'immagine/container.
2. Esegui il build di Keycloak (augmentation):

```bash
/opt/keycloak/bin/kc.sh build
```

3. Avvia Keycloak normalmente.

Nota: il primo avvio dopo nuova estensione puo essere piu lento.

### Opzione B: distribuzione custom gia esistente

Se usi una pipeline con immagine custom, includi il jar nella build immagine,
pubblica il nuovo tag e fai redeploy dell'ambiente.

## Configurazione in Keycloak

### 1. Crea flow post-login

- Authentication > Flows > Create flow
- Name: `fitp post login`
- Top level flow type: Basic flow

### 2. Aggiungi execution

- Add step > FITP Profile Enricher
- Requirement: Required

Proprieta disponibili nello step:

| Campo UI | Chiave config | Obbligatorio | Default | Descrizione |
|---|---|---:|---|---|
| Azure Tenant ID | `graph.tenantId` | Si | - | Tenant GUID o domain |
| App Registration Client ID | `graph.clientId` | Si | - | Client ID app registration |
| App Registration Client Secret | `graph.clientSecret` | Si | - | Secret app registration |
| Timeout HTTP (ms) | `graph.timeoutMs` | No | `5000` | Timeout token endpoint + Graph |
| Blocca login in caso di errore | `graph.failOnError` | No | `false` | Se true il login fallisce su errore Graph |
| Marca email come verificata | `graph.trustEmail` | No | `true` | Se true imposta emailVerified |

### 3. Associa il flow al provider FITP

- Identity providers > FITP > Advanced settings
- Post login flow: `fitp post login`
- Save

## Comportamento tecnico

- Idempotente: se `email` e gia presente, non chiama Graph.
- Cache token Graph in memoria processo: evita richieste token ripetute.
- Refresh margin token: rinnovo anticipato di circa 60 secondi.
- Fallimento controllato:
  - `graph.failOnError=false`: login continua
  - `graph.failOnError=true`: login bloccato con errore interno

Ordine di estrazione email da Graph:

1. `mail`
2. `otherMails[0]`
3. `identities[]` con `signInType=emailAddress`

## Test rapido

1. Esegui login da utente senza profilo completo in Keycloak.
2. Verifica utente in admin console.
3. Controlla che siano valorizzati `email`, `firstName`, `lastName`.

## Troubleshooting

Logger utile: `com.hiwaymedia.keycloak.FitpEnricherAuthenticator`

| Errore log | Causa probabile | Azione |
|---|---|---|
| `Token endpoint status=401` | secret errato o scaduto | rigenera secret e aggiorna config |
| `AADSTS700016` | client ID o tenant errato | verifica tenant/client |
| `Graph status=403` | permessi Graph mancanti | aggiungi `User.Read.All` Application + admin consent |
| `Graph status=404` | utente non presente in tenant configurato | verifica tenant e oid |

## Sicurezza

Il valore di `graph.clientSecret` e conservato nella configurazione autenticatore
di Keycloak. In ambienti production valutare secret management esterno (es. vault).

## Documentazione e rilascio

- Documentazione operativa: `docs/OPERATIONS.md`
- Storico modifiche: `CHANGELOG.md`

## Release automatica con GitHub Actions

La pipeline di release e definita in `.github/workflows/release.yml`.

Comportamento:

1. Trigger su push di tag che matchano `v*` (esempio: `v1.0.1`).
2. Build Maven con `mvn -B clean package`.
3. Individuazione del jar in `target/`.
4. Generazione file checksum `.sha512`.
5. Creazione release GitHub (o update se esiste gia) con upload di jar + checksum.

Esempio rilascio:

```bash
git tag v1.0.1
git push origin v1.0.1
```

Regola di manutenzione per questo repository:

1. Ogni modifica funzionale deve aggiornare README o docs dedicate.
2. Ogni modifica deve aggiungere una voce in `CHANGELOG.md`.
