package com.ims.shared.exception;

public class InvalidStateTransitionException extends BusinessRuleException {

    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super("INVALID_STATE_TRANSITION",
                "Cannot transition " + entityType + " from " + fromState + " to " + toState);
    }
}
