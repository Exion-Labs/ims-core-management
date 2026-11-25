package com.ims.shared.exception;

public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String message) {
        super("UNPROCESSABLE_ENTITY", message);
    }

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
