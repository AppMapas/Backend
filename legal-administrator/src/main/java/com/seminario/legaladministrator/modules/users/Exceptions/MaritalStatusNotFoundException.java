package com.seminario.legaladministrator.modules.users.Exceptions;

public class MaritalStatusNotFoundException extends RuntimeException{
    public MaritalStatusNotFoundException(String message) {
        super(message);
    }
}
