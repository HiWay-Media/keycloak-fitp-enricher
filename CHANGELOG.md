# Changelog

Tutte le modifiche rilevanti a questo progetto sono documentate in questo file.

Il formato segue Keep a Changelog e Semantic Versioning.

## [Unreleased]

### Added

- Nuova documentazione tecnica `docs/ARCHITECTURE.md`: panoramica del sistema, diagrammi Mermaid (componenti, sequenza primo login, sequenza login successivi, logica interna GraphClient, modello dati/classi), tabella parametri di configurazione, spiegazione SPI service loader e nota sul componente legacy deprecato.
- Nuovo documento `docs/IMPROVEMENTS.md`: analisi delle criticità individuate su `v1.1.0` (bug cache token statica multi-tenant, skip aggressivo in `preprocessFederatedIdentity`, `userPrincipalName` ignorato, dipendenze obsolete, copertura test mancante) con codice di esempio per ogni fix.

## [1.1.0] - 2026-05-05

### Added

- Nuovo `FitpEnricherIdentityProviderMapper` che gira in `preprocessFederatedIdentity`, PRIMA del First Login Flow. Risolve il primo login fallito con `KC-SERVICES0020: Email is null` su B2C che non emette email nei token.
- Nuova opzione `username.source` (`email` default, `oid` legacy): impone `username = email` sull'utente Keycloak invece dell'OID/sub di B2C.
- Nuovo helper `com.hiwaymedia.keycloak.graph.GraphClient` con token caching condiviso e retry breve su timeout / 429 / 503.
- Nuova opzione `graph.retryCount` (default `1`) sia sul mapper sia sull'authenticator deprecato.
- Test unit con WireMock e Mockito (`GraphClientTest`, `FitpEnricherIdentityProviderMapperTest`).

### Changed

- Default `graph.timeoutMs` alzato da `5000` a `8000`.
- `FitpEnricherAuthenticator` ora delega a `GraphClient`; comportamento invariato.

### Deprecated

- `FitpEnricherAuthenticator` e `FitpEnricherAuthenticatorFactory`. Mantenuti registrati per compat e per healing di utenti gia esistenti con record vuoto. Verranno rimossi in v2.0.0.

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
