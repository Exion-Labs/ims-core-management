package com.ims.shared.exception;

public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super("UNAUTHORIZED", message);
    }

    public UnauthorizedException(String code, String message) {
        super(code, message);
    }
}
