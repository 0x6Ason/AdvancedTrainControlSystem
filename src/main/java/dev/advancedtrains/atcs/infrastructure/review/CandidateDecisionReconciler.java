package dev.advancedtrains.atcs.infrastructure.review;

import java.sql.SQLException;

@FunctionalInterface
public interface CandidateDecisionReconciler {
    void reconcile() throws SQLException;
}
