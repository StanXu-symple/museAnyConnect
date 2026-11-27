package com.muse.connect.aidoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST controller as an AI-discoverable API group.
 * Prefer referencing a centralized {@link AiDocMeta} entry via {@link #value()} instead of hardcoding strings.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiApiGroup {

    /** Centralized metadata enum entry defining group name, description, connector type, and order. */
    AiDocMeta value() default AiDocMeta.NONE;

    /** Optional override for display title. */
    String name() default "";

    /** Optional override for group usage description. */
    String description() default "";

    /** Optional override for connector type key (e.g. "X", "TELEGRAM", "SSH"). */
    String connectorType() default "";

    /** Optional override for sort order (-1 uses the enum's order). */
    int order() default -1;
}
