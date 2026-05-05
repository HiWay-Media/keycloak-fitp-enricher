package com.hiwaymedia.keycloak.graph;

/**
 * Errore nella chiamata a Microsoft Graph o al token endpoint.
 *
 * statusCode contiene lo status HTTP quando disponibile, oppure 0 per timeout / errori I/O.
 */
public class GraphException extends RuntimeException {

    private final int statusCode;

    public GraphException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GraphException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
