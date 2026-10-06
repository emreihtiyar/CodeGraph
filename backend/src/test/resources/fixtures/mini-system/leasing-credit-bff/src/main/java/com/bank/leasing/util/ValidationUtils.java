package com.bank.leasing.util;

public final class ValidationUtils {

    private ValidationUtils() {}

    public static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " zorunlu");
        }
    }
}
