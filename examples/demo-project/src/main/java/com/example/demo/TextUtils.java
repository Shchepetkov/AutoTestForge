package com.example.demo;

/**
 * Small string helpers with plenty of edge cases (nulls, empty strings,
 * boundary lengths) - ideal for demonstrating generated negative scenarios.
 */
public final class TextUtils {

    private TextUtils() {
    }

    /** Reverses the given string; null stays null. */
    public static String reverse(String value) {
        if (value == null) {
            return null;
        }
        return new StringBuilder(value).reverse().toString();
    }

    /** A null or blank string is not a palindrome. Comparison ignores case. */
    public static boolean isPalindrome(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        return normalized.contentEquals(new StringBuilder(normalized).reverse());
    }

    /**
     * Truncates the value to {@code maxLength} characters, appending an
     * ellipsis when something was cut off.
     *
     * @throws IllegalArgumentException when maxLength is negative
     */
    public static String truncate(String value, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be non-negative");
        }
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
