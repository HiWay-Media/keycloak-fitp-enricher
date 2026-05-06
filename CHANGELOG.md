# Changelog

Tutte le modifiche rilevanti a questo progetto sono documentate in questo file.

Il formato segue Keep a Changelog e Semantic Versioning.

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
