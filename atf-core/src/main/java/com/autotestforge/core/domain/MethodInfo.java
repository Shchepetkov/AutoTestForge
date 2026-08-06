package com.autotestforge.core.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Public method of a scanned class, in the form the AI generator needs to
 * design test scenarios: signature, thrown exceptions and documentation.
 *
 * @param name             method name
 * @param returnType       declared return type
 * @param parameters       ordered list of parameters
 * @param thrownExceptions declared checked exceptions
 * @param javadoc          Javadoc text, empty string when absent
 * @param annotations      simple names of the annotations on the method
 * @param isStatic         whether the method is static
 */
public record MethodInfo(
        String name,
        String returnType,
        List<ParameterInfo> parameters,
        List<String> thrownExceptions,
        String javadoc,
        List<String> annotations,
        boolean isStatic) {

    /** Human-readable signature used in prompts and logs, e.g. {@code BigDecimal total(Order order) throws PricingException}. */
    public String signature() {
        String params = parameters.stream()
                .map(ParameterInfo::toString)
                .collect(Collectors.joining(", "));
        String throwsClause = thrownExceptions.isEmpty()
                ? ""
                : " throws " + String.join(", ", thrownExceptions);
        return (isStatic ? "static " : "") + returnType + " " + name + "(" + params + ")" + throwsClause;
    }
}
