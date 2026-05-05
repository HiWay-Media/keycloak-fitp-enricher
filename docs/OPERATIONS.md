# Operations Guide

Guida operativa per installazione, configurazione, verifica e migrazione del FITP Profile Enricher.

## 1. Prerequisiti Azure/Entra

- App registration con credential di tipo secret.
- Permission Microsoft Graph `User.Read.All` di tipo Application.
- Admin consent concesso.

## 2. Build artefatto

```bash
mvn clean package
# oppure
gradle clean build
```

Output:
- `target/fitp-enricher-1.1.0.jar` (Maven)
- `build/libs/fitp-enricher-1.1.0.jar` (Gradle)

## 3. Deploy in Keycloak

1. Copiare il jar in `providers/` del runtime Keycloak.
2. Eseguire `kc.sh build` per registrare i provider SPI.
3. Riavviare Keycloak.

## 4. Configurazione (path raccomandato)

Aggiungi il mapper sull'IdP:

1. Admin console > Identity providers > FITP > tab `Mappers` > `Add mapper`.
2. Mapper type: `FITP Profile Enricher Mapper`.
3. Sync mode: `Force`.
4. Configurare i campi:
   - `graph.tenantId`
   - `graph.clientId`
   - `graph.clientSecret`
   - `graph.timeoutMs` (default `8000`)
   - `graph.retryCount` (default `1`)
   - `graph.failOnError` (default `false`)
   - `graph.trustEmail` (default `true`)
   - `username.source` (`email` default, oppure `oid`)

## 5. Migrazione da v1.0 (Authenticator in Post Login Flow)

1. Build e deploy del jar 1.1.0.
2. Aggiungere il mapper sull'IdP FITP con la stessa coppia tenant/client/secret usata oggi nel Post Login step.
3. Verificare un primo login con un utente nuovo (vedi sezione 6).
4. Una volta confermato, rimuovere lo step `FITP Profile Enricher` dal flow `fitp post login` (Identity providers > FITP > Advanced settings > Post login flow > rimuovi step / disabilita flow).

NB: il vecchio Authenticator e relativa factory restano registrati nel jar per compatibilita binaria. Saranno rimossi in v2.0.0.

## 6. Verifica post deploy

1. Cancellare l'eventuale utente test pre-esistente con record vuoto.
2. Login con utente FITP/B2C senza email mai presente in Keycloak.
3. Risultato atteso:
   - Redirect al frontend correttamente loggato.
   - Utente in admin console con `username=<email>`, `email`, `firstName`, `lastName` valorizzati e `Email verified=Yes`.
   - **Nessun** `KC-SERVICES0020 Email is null` nei log.
4. Per utenti gia esistenti con record vuoto e `Sync mode=Force`, al login successivo il mapper esegue `updateBrokeredUser` e popola i campi vuoti (incluso lo username se ancora OID-like).
5. Filtri log:
   - `com.hiwaymedia.keycloak.FitpEnricherIdentityProviderMapper`
   - `com.hiwaymedia.keycloak.graph.GraphClient`

## 7. Incident response rapido

- `401` su token endpoint: secret scaduto/non valido.
- `403` da Graph: permessi app insufficienti o admin consent mancante.
- `404` da Graph: tenant o oid errato.
- `HttpTimeoutException` ricorrenti: alza `graph.timeoutMs` a `10000` e/o `graph.retryCount` a `2`. Se persistono, escalation Microsoft.

## 8. Regola documentazione e changelog

Per ogni modifica al codice:

1. Aggiornare README o documentazione in `docs/`.
2. Aggiornare `CHANGELOG.md` nella sezione `Unreleased`.
3. In fase di release, spostare le voci da `Unreleased` alla versione rilasciata.

## 9. Release via GitHub Actions

Workflow: `.github/workflows/release.yml`

Trigger:

- push di tag con pattern `v*`

Passi eseguiti dalla pipeline:

1. Setup Java 17 + cache Maven/Gradle.
2. Build (`gradle build` o `mvn -B clean package`).
3. Raccolta jar.
4. Creazione checksum `*.sha512`.
5. Create/update GitHub Release e upload asset.

Procedura operativa release:

1. Verificare changelog e versione.
2. Creare tag semantico, esempio `v1.1.0`.
3. Pushare il tag su origin.
4. Verificare gli asset nella pagina Releases di GitHub.
