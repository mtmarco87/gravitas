package com.gravitas.entities.bodies.celestial_body.runtime;

/** Per-step informational snapshot of tidal spin-state contributions. */
public final class TidalSpinState {

    public int contributorCount;
    public double alphaMagnitude;
    public String primaryContributor;
    public double primaryMagnitude;
    public String secondaryContributor;
    public double secondaryMagnitude;

    public void reset() {
        contributorCount = 0;
        alphaMagnitude = 0.0;
        primaryContributor = null;
        primaryMagnitude = 0.0;
        secondaryContributor = null;
        secondaryMagnitude = 0.0;
    }

    public void recordContribution(String partnerName, double contributionMagnitude) {
        if (partnerName == null || contributionMagnitude <= 0.0 || !Double.isFinite(contributionMagnitude)) {
            return;
        }

        contributorCount++;
        alphaMagnitude += contributionMagnitude;

        if (contributionMagnitude > primaryMagnitude) {
            secondaryContributor = primaryContributor;
            secondaryMagnitude = primaryMagnitude;
            primaryContributor = partnerName;
            primaryMagnitude = contributionMagnitude;
            return;
        }

        if (contributionMagnitude > secondaryMagnitude) {
            secondaryContributor = partnerName;
            secondaryMagnitude = contributionMagnitude;
        }
    }
}