package com.seastella.servicerequest.api;

/** Request priority (reference spec section 17). */
public enum Priority {
    CRITICAL(1), HIGH(2), MEDIUM(3), LOW(4);

    private final int rank;

    Priority(int rank) { this.rank = rank; }

    /** Lower is more urgent - used for queue ordering. */
    public int rank() { return rank; }
}
