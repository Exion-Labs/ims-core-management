package com.ims.shared.exception;

public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        super("NOT_FOUND", resourceType + " not found: " + id);
    }
}
