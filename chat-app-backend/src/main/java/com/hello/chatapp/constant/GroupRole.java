package com.hello.chatapp.constant;

public enum GroupRole {
    LEADER(1),
    CO_LEADER(2),
    ELDER(3),
    MEMBER(4);

    private final int rank;

    GroupRole(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public boolean isSameOrHigherThan(GroupRole other) {
        return other != null && rank <= other.rank;
    }
}
