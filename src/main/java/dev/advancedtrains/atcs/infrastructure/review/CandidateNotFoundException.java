package dev.advancedtrains.atcs.infrastructure.review;

public final class CandidateNotFoundException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public CandidateNotFoundException(String message) {
        super(message);
    }
}
