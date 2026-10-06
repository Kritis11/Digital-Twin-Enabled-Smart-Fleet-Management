package com.fleettwin.twin;

/** Component status and alert severity. Declared in order of severity so the worst can be picked by ordinal. */
public enum Status {
    OK, WARNING, CRITICAL;

    public Status worst(Status other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
