package vn.teasmart.backend.notification;

/** Immutable snapshot: no JPA entities, credentials or lazy reads after commit. */
public record EmailNotification(String recipient, String subject, String html) { }
