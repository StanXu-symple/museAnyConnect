package com.muse.connect.aidoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Spring MVC handler method as an AI-callable endpoint.
 * Prefer referencing a centralized {@link AiDocMeta} entry via {@link #value()} instead of hardcoding strings.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiEndpoint {

    /** Centralized metadata enum entry defining summary, description, and order. */
    AiDocMeta value() default AiDocMeta.NONE;

    /** Optional override for one-line endpoint summary. */
    String summary() default "";

    /** Optional override for detailed usage rules or caveats. */
    String description() default "";

    /** Optional custom JSON request body example (auto-generated from @RequestBody fields when omitted). */
    String exampleBody() default "";

    /** Optional concise JSON response example. */
    String exampleResponse() default "";

    /** Optional override for sort order within the group (-1 uses the enum's order). */
    int order() default -1;

    /** Set true to temporarily exclude this endpoint from AI documentation without removing the annotation. */
    boolean hidden() default false;
}
