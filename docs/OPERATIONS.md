# Operations Guide

Questa guida descrive i passaggi operativi per installare, configurare, verificare e manutenere il provider FITP Profile Enricher.

## 1. Prerequisiti Azure/Entra

- App registration con credential di tipo secret.
- Permission Microsoft Graph `User.Read.All` di tipo Application.
- Admin consent concesso.

## 2. Build artefatto

```bash
mvn clean package
```

Output atteso:

- `target/fitp-enricher-1.0.0.jar`

## 3. Deploy in Keycloak

1. Copiare il jar in `providers/` del runtime Keycloak.
2. Eseguire `kc.sh build` per registrare il provider SPI.
3. Riavviare Keycloak.

## 4. Configurazione flow

1. Creare un Basic flow post-login.
2. Aggiungere step `FITP Profile Enricher` con requirement `Required`.
3. Configurare i campi:
   - `graph.tenantId`
   - `graph.clientId`
   - `graph.clientSecret`
   - `graph.timeoutMs` (default `5000`)
   - `graph.failOnError` (default `false`)
   - `graph.trustEmail` (default `true`)
4. Associare il flow al provider FITP in `Identity providers > FITP > Advanced settings`.

## 5. Verifica post deploy

- Login con utente FITP/B2C senza email presente in Keycloak.
- Controllo utente in admin console: `email`, `firstName`, `lastName` valorizzati.
- Verifica log applicativi del logger:
  - `com.hiwaymedia.keycloak.FitpEnricherAuthenticator`

## 6. Incident response rapido

- `401` su token endpoint: secret scaduto/non valido.
- `403` da Graph: permessi app insufficienti o admin consent mancante.
- `404` da Graph: tenant o oid errato.

## 7. Regola documentazione e changelog

Per ogni modifica al codice:

1. Aggiornare README o documentazione in `docs/`.
2. Aggiornare `CHANGELOG.md` nella sezione `Unreleased`.
3. In fase di release, spostare le voci da `Unreleased` alla versione rilasciata.
