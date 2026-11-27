package com.muse.connect.aidoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将 REST 控制器标记为可被 AI 发现的 API 分组。
 * 优先通过 {@link #value()} 引用集中管理的 {@link AiDocMeta} 枚举项，而非硬编码字符串。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiApiGroup {

    /** 定义分组名称、描述、连接器类型与排序的集中式元数据枚举项。 */
    AiDocMeta value() default AiDocMeta.NONE;

    /** 可选的分组显示标题覆盖值。 */
    String name() default "";

    /** 可选的分组用途说明覆盖值。 */
    String description() default "";

    /** 可选的连接器类型标识覆盖值（如 "X"、"TELEGRAM"、"SSH"）。 */
    String connectorType() default "";

    /** 可选的排序权重覆盖值（-1 表示沿用枚举默认顺序）。 */
    int order() default -1;
}
