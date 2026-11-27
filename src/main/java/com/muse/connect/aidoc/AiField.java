package com.muse.connect.aidoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional metadata for path variables, query parameters, or request body record/DTO fields.
 * Prefer referencing a centralized {@link AiDocMeta} entry via {@link #value()} instead of hardcoding strings.
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AiField {

    /** Centralized metadata enum entry defining description, required flag, and example value. */
    AiDocMeta value() default AiDocMeta.NONE;

    /** Optional override for parameter or field description. */
    String description() default "";

    /** Optional override to mark a request body field as required. */
    boolean required() default false;

    /** Optional override for example value. */
    String example() default "";
}
