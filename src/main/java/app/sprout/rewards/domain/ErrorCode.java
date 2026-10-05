package app.sprout.rewards.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the rewards contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in first"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "No Sprout account"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not in the vault"),
    NOT_ENOUGH_POINTS(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough points"),
    ALREADY_REFERRED(HttpStatus.CONFLICT, "Already referred"),
    INVALID_REFERRAL(HttpStatus.UNPROCESSABLE_ENTITY, "Not a usable code"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
