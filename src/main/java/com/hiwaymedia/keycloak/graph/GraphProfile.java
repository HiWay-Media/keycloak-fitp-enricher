package com.hiwaymedia.keycloak.graph;

/**
 * Snapshot dei campi profilo letti da Microsoft Graph che servono a popolare l'utente Keycloak.
 */
public record GraphProfile(String email, String firstName, String lastName) {
}
