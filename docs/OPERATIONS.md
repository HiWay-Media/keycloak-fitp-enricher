# Operations Guide

Guida operativa per installazione, configurazione e verifica del FITP Profile Enricher.

## 1. Prerequisiti Azure/Entra

- App registration con credential di tipo secret.
- Permission Microsoft Graph `User.Read.All` di tipo Application.
- Admin consent concesso.

## 2. Build artefatto

```bash
gradle clean build
# oppure
mvn clean package
```

Output:
- `build/libs/fitp-enricher-0.3.0.jar` (Gradle)
- `target/fitp-enricher-0.3.0.jar` (Maven)

## 3. Deploy in Keycloak

1. Copiare il jar in `providers/` del runtime Keycloak.
2. Eseguire `kc.sh build` per registrare i provider SPI.
3. Riavviare Keycloak.

## 4. Configurazione

Il plugin espone un solo componente: l'Authenticator `FITP Profile Enricher`, da inserire in un Post Login Flow agganciato all'IdP FITP.

1. Admin console > Authentication > Flows > crea/seleziona `fitp post login`.
2. Aggiungi step `FITP Profile Enricher` (Requirement: `REQUIRED`).
3. Configura lo step:
   - `graph.tenantId`
   - `graph.clientId`
   - `graph.clientSecret`
   - `graph.timeoutMs` (default `8000`)
   - `graph.retryCount` (default `1`)
   - `graph.failOnError` (default `false`)
   - `graph.trustEmail` (default `true`)
4. Identity providers > FITP > Advanced settings > Post login flow = `fitp post login`.

## 5. Verifica post deploy

1. Login con utente FITP/B2C nuovo o esistente.
2. Risultato atteso in admin console:
   - `username = <email>` (anche per utenti esistenti che avevano username = OID).
   - `email`, `firstName`, `lastName` valorizzati, `Email verified = Yes`.
3. Nei log Keycloak:
   - `Profilo arricchito oid=... email=... firstName=... lastName=...` (se Graph è stato chiamato).
   - `Username aggiornato: <oid> -> <email>` quando lo username viene riallineato.
4. Filtri log utili:
   - `com.hiwaymedia.keycloak.FitpEnricherAuthenticator`
   - `com.hiwaymedia.keycloak.graph.GraphClient`

## 6. Incident response rapido

- `401` su token endpoint: secret scaduto/non valido.
- `403` da Graph: permessi app insufficienti o admin consent mancante.
- `404` da Graph: tenant o oid errato.
- `HttpTimeoutException` ricorrenti: alza `graph.timeoutMs` a `10000` e/o `graph.retryCount` a `2`.

## 7. Regola documentazione e changelog

Per ogni modifica al codice:

1. Aggiornare README o documentazione in `docs/`.
2. Aggiornare `CHANGELOG.md`.

## 8. Release via GitHub Actions

Workflow: `.github/workflows/release.yml`. Trigger: push di tag `v*`. La pipeline builda con Gradle, raccoglie il jar, calcola `*.sha512` e pubblica come asset di GitHub Release.
