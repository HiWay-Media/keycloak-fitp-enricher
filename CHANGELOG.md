# Changelog

Tutte le modifiche rilevanti a questo progetto sono documentate in questo file.

Il formato segue Keep a Changelog e Semantic Versioning.

## [Unreleased]

### Added

- Nuovo `docs/OPERATIONS.md` con runbook operativo di build, deploy e verifica.

### Changed

- `README.md` riscritto e riallineato al codice attuale e al flusso operativo reale.
- Aggiunta policy esplicita: ogni modifica richiede aggiornamento docs e changelog.

## [1.0.0] - 2026-05-04

### Added

- Authenticator SPI `FITP Profile Enricher` per Keycloak.
- Integrazione Microsoft Graph via client credentials.
- Arricchimento profilo utente con `email`, `firstName`, `lastName`.
- Configurazioni runtime via `AuthenticatorFactory`.
- Registrazione provider SPI tramite service loader.
