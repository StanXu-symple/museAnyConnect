package com.muse.connect.aidoc;

import java.util.List;

/**
 * Immutable reflection snapshot of an {@link AiEndpoint}-annotated Spring MVC handler method.
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
