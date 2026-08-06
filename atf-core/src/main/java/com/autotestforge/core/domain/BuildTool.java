package com.autotestforge.core.domain;

/** Build tool of the target project. */
public enum BuildTool {
    MAVEN,
    GRADLE_GROOVY,
    GRADLE_KOTLIN;

    public boolean isGradle() {
        return this != MAVEN;
    }
}
