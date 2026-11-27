package com.muse.connect.aidoc;

import java.util.List;

/**
 * 标注了 {@link AiEndpoint} 的 Spring MVC 处理方法的不可变反射快照。
 */
public record AiEndpointDescriptor(
        String groupName,
        String groupDescription,
        String connectorType,
        int groupOrder,
        int endpointOrder,
        String httpMethod,
        String path,
        String summary,
        String description,
        List<ParamDescriptor> pathParams,
        List<ParamDescriptor> queryParams,
        String requestBodyType,
        boolean requestBodyRequired,
        List<ParamDescriptor> bodyFields,
        String exampleBody,
        String exampleResponse
) {
    public record ParamDescriptor(
            String name,
            String type,
            boolean required,
            String defaultValue,
            String description,
            String example
    ) {
    }
}
