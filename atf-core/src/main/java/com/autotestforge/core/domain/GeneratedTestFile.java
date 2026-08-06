package com.autotestforge.core.domain;

/**
 * A single generated test class, ready to be written to
 * {@code src/test/java/<package>/<className>.java}.
 *
 * @param packageName package of the test class (mirrors the class under test)
 * @param className   simple name of the test class, e.g. {@code OrderServiceTest}
 * @param sourceCode  complete compilable Java source
 */
public record GeneratedTestFile(String packageName, String className, String sourceCode) {

    public String fullyQualifiedName() {
        return packageName == null || packageName.isBlank()
                ? className
                : packageName + "." + className;
    }
}
