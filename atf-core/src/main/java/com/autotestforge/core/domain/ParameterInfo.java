package com.autotestforge.core.domain;

/**
 * A single method parameter.
 *
 * @param name parameter name as declared in source
 * @param type parameter type (fully qualified when resolvable, simple name otherwise)
 */
public record ParameterInfo(String name, String type) {

    @Override
    public String toString() {
        return type + " " + name;
    }
}
