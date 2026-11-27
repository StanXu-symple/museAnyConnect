package com.muse.connect.aidoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 路径变量、查询参数或请求体 Record/DTO 字段的可选元数据注解。
 * 优先通过 {@link #value()} 引用集中管理的 {@link AiDocMeta} 枚举项，而非硬编码字符串。
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AiField {

    /** 定义字段描述、必填标记与示例值的集中式元数据枚举项。 */
    AiDocMeta value() default AiDocMeta.NONE;

    /** 可选的参数或字段说明覆盖值。 */
    String description() default "";

    /** 可选覆盖项，用于将请求体字段标记为必填。 */
    boolean required() default false;

    /** 可选的示例值覆盖项。 */
    String example() default "";
}
