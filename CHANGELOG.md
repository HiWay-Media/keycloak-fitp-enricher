# Changelog

Tutte le modifiche rilevanti a questo progetto sono documentate in questo file.

Il formato segue Keep a Changelog e Semantic Versioning.

## [0.3.3] - 2026-07-02

### Fixed

- `FEDERATED_IDENTITY.FEDERATED_USERNAME` non diventava l'email: in 0.3.2 usavo `serializedCtx.setUsername(email)`, ma in `SerializedBrokeredIdentityContext` sia `setUsername` sia `setModelUsername` scrivono l'attributo `UserModel.USERNAME` (→ `USER_ENTITY.USERNAME`), mentre `deserialize()` ricostruisce `context.getUsername()` (da cui deriva `FEDERATED_USERNAME`) dal campo **`brokerUsername`**. Corretto usando **`serializedCtx.setBrokerUsername(email)`**. Vale solo per i nuovi first-login.

## [0.3.2] - 2026-07-02

### Added

- `FitpBrokerEnricherAuthenticator` ora imposta anche il **brokered username** (`serializedCtx.setUsername(email)`), così `FEDERATED_IDENTITY.FEDERATED_USERNAME` per FITP diventa l'email — coerente con gli altri IdP (google/apple/facebook), che l'email la portano nel token. Nota: vale solo per i **nuovi** first-login; le righe federate esistenti non vengono riscritte.

## [0.3.1] - 2026-06-25

### Fixed

- `FitpBrokerEnricherAuthenticator`: l'OID passato a Microsoft Graph poteva includere il prefisso `<alias>.` (es. `fitp.<sub>`) che Keycloak applica allo username/id generato nel First Broker Login → Graph rispondeva `404 Request_ResourceNotFound` e il primo login falliva. Ora si usa il `sub` nudo (`getId()`, fallback `getBrokerUserId()`) e si rimuove difensivamente l'eventuale prefisso `<alias>.` (regressione introdotta in 0.3.0, dove il vecchio authenticator post-login leggeva il `sub` già nudo da `user.getUsername()`).

## [0.3.0] - 2026-06-24

### Added

- `FitpBrokerEnricherAuthenticator` (+ Factory): authenticator del **First Broker Login flow** che arricchisce il `BrokeredIdentityContext` da Microsoft Graph (email/firstName/lastName) **prima** di `Create User If Unique`. Va inserito come primo step `REQUIRED` del flow (con i due step di linking in un sub-flow `REQUIRED`). Risolve il primo login fallito su B2C senza email e i **duplicati di account** (la deduplica per email funziona solo se l'email è presente al broker-time). **Fail-closed** di default.

### Removed

- `FitpEnricherAuthenticator` (+ Factory): l'enrichment passa dal Post Login Flow al First Broker Login flow; l'update/rename post-login non è più necessario.
- `DiagOIDCIdentityProvider` (+ Factory): provider diagnostico temporaneo introdotto in 0.2.1, rimosso a diagnosi conclusa. La causa di "Token is no longer valid" era `nbf`/clock skew con `Allowed clock skew = 0`, risolta alzando l'Allowed clock skew sull'IdP.

### Changed

- Il componente unico del plugin è ora l'authenticator broker-flow `fitp-broker-enricher`.
- Smoke test e workflow di compatibilità aggiornati al nuovo provider id `fitp-broker-enricher`.

## [0.2.1] - 2026-06-23

### Added

- `DiagOIDCIdentityProvider` + `DiagOIDCIdentityProviderFactory`: override diagnostico del provider OIDC built-in (stesso id `oidc`, `order()=100`, nessuna modifica al realm) che logga `iat`/`exp`/`nbf` dell'id_token confrontati con l'ora del nodo per diagnosticare l'errore intermittente "Token is no longer valid" sul callback del broker FITP/B2C. Logga una riga `WARN [FITP-DIAG]` con `expiredBy`/`notYetValidFor`. **Codice diagnostico temporaneo**: da rimuovere a diagnosi conclusa.

## [0.2.0] - 2026-05-06

### Removed

- `FitpEnricherIdentityProviderMapper` e relativa registrazione SPI: il plugin torna a un unico componente, l'Authenticator post-login.

### Changed

- `FitpEnricherAuthenticator` ora imposta sempre `username = email` quando l'email è valorizzata (sull'utente o appena fetched da Graph). Questo "guarisce" anche gli utenti esistenti con username = OID/UUID al loro prossimo login.
- Rimosso early-return su email già presente: il rename username gira anche quando il fetch Graph è skippato.
- `FitpEnricherAuthenticator` e `FitpEnricherAuthenticatorFactory` non sono più deprecati: tornano a essere il punto di ingresso primario.

## [1.0.x]

### Added

- Nuovo `docs/OPERATIONS.md` con runbook operativo di build, deploy e verifica.
- Workflow GitHub Actions release (`.github/workflows/release.yml`) che su tag `v*` builda il jar Gradle e lo pubblica come asset di GitHub Release (con checksum `.sha512`).

### Changed

- `README.md` riscritto e riallineato al codice attuale e al flusso operativo reale.
- Aggiunta policy esplicita: ogni modifica richiede aggiornamento docs e changelog.
- Estesa documentazione con sezione dedicata alla release automatica via GitHub Actions.
- Semplificato `.github/workflows/release.yml`: build Gradle unico (`gradle build`) senza env dev/prod.
- Semplificato `.github/workflows/ci.yml`: build Gradle unico (`gradle build`) senza env dev/prod, aggiunto cache Gradle. Rimosso wrapper validation (non presente nei wrapper file) e action gradle/gradle-build-action (non compatibile senza wrapper).

## [1.0.0] - 2026-05-04

### Added

- Authenticator SPI `FITP Profile Enricher` per Keycloak.
- Integrazione Microsoft Graph via client credentials.
- Arricchimento profilo utente con `email`, `firstName`, `lastName`.
- Configurazioni runtime via `AuthenticatorFactory`.
- Registrazione provider SPI tramite service loader.
