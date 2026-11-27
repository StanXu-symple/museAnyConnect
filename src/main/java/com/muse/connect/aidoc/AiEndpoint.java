package com.muse.connect.aidoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将 Spring MVC 处理方法声明为可供 AI 调用的接口端点。
 * 优先通过 {@link #value()} 引用集中管理的 {@link AiDocMeta} 枚举项，而非硬编码字符串。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiEndpoint {

    /** 定义端点摘要、说明与排序的集中式元数据枚举项。 */
    AiDocMeta value() default AiDocMeta.NONE;

    /** 可选的单行端点摘要覆盖值。 */
    String summary() default "";

    /** 可选的详细使用规则或注意事项覆盖值。 */
    String description() default "";

    /** 可选的自定义 JSON 请求体示例（省略时将根据 @RequestBody 字段自动生成）。 */
    String exampleBody() default "";

    /** 可选的精简 JSON 响应示例。 */
    String exampleResponse() default "";

    /** 可选的组内排序权重覆盖值（-1 表示沿用枚举默认顺序）。 */
    int order() default -1;

    /** 设为 true 可在不移除注解的前提下临时将该端点从 AI 文档中隐藏。 */
    boolean hidden() default false;
}
