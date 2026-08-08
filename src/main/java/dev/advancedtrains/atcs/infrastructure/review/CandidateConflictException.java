package dev.advancedtrains.atcs.infrastructure.review;

public final class CandidateConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CandidateConflictException(String message) {
        super(message);
    }
}
