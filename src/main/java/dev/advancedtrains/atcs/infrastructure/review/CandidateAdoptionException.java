package dev.advancedtrains.atcs.infrastructure.review;

public final class CandidateAdoptionException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CandidateAdoptionException(String message) {
        super(message);
    }
}
