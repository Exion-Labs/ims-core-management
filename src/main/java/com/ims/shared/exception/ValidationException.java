package com.ims.shared.exception;

public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super("BAD_REQUEST", message);
    }

    public ValidationException(String code, String message) {
        super(code, message);
    }
}
