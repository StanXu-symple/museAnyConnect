package com.muse.connect.aidoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.aidoc.AiEndpointDescriptor.ParamDescriptor;
import com.muse.connect.constant.AiDocConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描 Spring MVC 的 {@link RequestMappingHandlerMapping} 一次，发现所有标注了 {@link AiEndpoint} 的端点，
 * 并通过反射解析其 HTTP 方法、路由路径、参数列表及请求体结构。
 */
@Component
public class AiDocScanner {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper;
    private volatile List<AiEndpointDescriptor> cachedEndpoints;

    public AiDocScanner(
            @Qualifier(AiDocConstants.HANDLER_MAPPING_BEAN) RequestMappingHandlerMapping handlerMapping,
            ObjectMapper objectMapper
    ) {
        this.handlerMapping = handlerMapping;
        this.objectMapper = objectMapper;
    }

    public List<AiEndpointDescriptor> getEndpoints() {
        List<AiEndpointDescriptor> current = cachedEndpoints;
        if (current == null) {
            synchronized (this) {
                current = cachedEndpoints;
                if (current == null) {
                    current = List.copyOf(scan());
                    cachedEndpoints = current;
                }
            }
        }
        return current;
    }

    private List<AiEndpointDescriptor> scan() {
        List<AiEndpointDescriptor> result = new ArrayList<>();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handler = entry.getValue();
            Method method = handler.getMethod();
            AiEndpoint endpointAnn = AnnotatedElementUtils.findMergedAnnotation(method, AiEndpoint.class);
            if (endpointAnn == null || endpointAnn.hidden()) {
                continue;
            }

            AiApiGroup groupAnn = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), AiApiGroup.class);
            AiDocMeta groupMeta = groupAnn != null ? groupAnn.value() : AiDocMeta.NONE;
            String groupName = firstNonBlank(
                    groupAnn != null ? groupAnn.name() : RouteConstants.EMPTY,
                    groupMeta.title(),
                    handler.getBeanType().getSimpleName()
            );
            String groupDesc = firstNonBlank(
                    groupAnn != null ? groupAnn.description() : RouteConstants.EMPTY,
                    groupMeta.description()
            );
            String connectorType = firstNonBlank(
                    groupAnn != null ? groupAnn.connectorType() : RouteConstants.EMPTY,
                    groupMeta.connectorTypeName()
            ).toUpperCase();
            int groupOrder = (groupAnn != null && groupAnn.order() >= 0) ? groupAnn.order() : groupMeta.order();

            AiDocMeta endpointMeta = endpointAnn.value();
            String endpointSummary = firstNonBlank(endpointAnn.summary(), endpointMeta.title(), method.getName());
            String endpointDesc = firstNonBlank(endpointAnn.description(), endpointMeta.description());
            int endpointOrder = endpointAnn.order() >= 0 ? endpointAnn.order() : endpointMeta.order();

            RequestMappingInfo info = entry.getKey();
            Set<String> paths = extractPaths(info);
            Set<RequestMethod> httpMethods = info.getMethodsCondition().getMethods();
            if (paths.isEmpty()) {
                continue;
            }

            List<ParamDescriptor> pathParams = new ArrayList<>();
            List<ParamDescriptor> queryParams = new ArrayList<>();
            String bodyTypeName = null;
            boolean bodyRequired = false;
            List<ParamDescriptor> bodyFields = List.of();
            String synthesizedBody = RouteConstants.EMPTY;

            for (Parameter param : method.getParameters()) {
                AiField aiField = param.getAnnotation(AiField.class);
                AiDocMeta fieldMeta = aiField != null ? aiField.value() : AiDocMeta.NONE;
                String fieldDesc = firstNonBlank(aiField != null ? aiField.description() : RouteConstants.EMPTY, fieldMeta.description());
                String fieldExample = firstNonBlank(aiField != null ? aiField.example() : RouteConstants.EMPTY, fieldMeta.example());

                PathVariable pathVar = param.getAnnotation(PathVariable.class);
                if (pathVar != null) {
                    String name = firstNonBlank(pathVar.name(), pathVar.value(), param.getName());
                    pathParams.add(new ParamDescriptor(
                            name,
                            formatType(param.getParameterizedType()),
                            pathVar.required(),
                            RouteConstants.EMPTY,
                            fieldDesc,
                            fieldExample
                    ));
                    continue;
                }

                RequestParam reqParam = param.getAnnotation(RequestParam.class);
                if (reqParam != null) {
                    String name = firstNonBlank(reqParam.name(), reqParam.value(), param.getName());
                    String defaultVal = ValueConstants.DEFAULT_NONE.equals(reqParam.defaultValue())
                            ? RouteConstants.EMPTY
                            : reqParam.defaultValue();
                    boolean required = reqParam.required() && !StringUtils.hasText(defaultVal);
                    queryParams.add(new ParamDescriptor(
                            name,
                            formatType(param.getParameterizedType()),
                            required,
                            defaultVal,
                            fieldDesc,
                            fieldExample
                    ));
                    continue;
                }

                RequestBody reqBody = param.getAnnotation(RequestBody.class);
                if (reqBody != null) {
                    bodyTypeName = formatType(param.getParameterizedType());
                    bodyRequired = reqBody.required();
                    bodyFields = reflectBodyFields(param.getType());
                    synthesizedBody = buildExampleBody(param.getType(), bodyFields, fieldExample);
                }
            }

            String exampleBody = firstNonBlank(endpointAnn.exampleBody(), endpointMeta.example(), synthesizedBody);

            List<String> resolvedMethods = httpMethods.isEmpty()
                    ? List.of(HttpConstants.METHOD_GET)
                    : httpMethods.stream().map(Enum::name).sorted().toList();

            for (String path : paths.stream().sorted().toList()) {
                for (String httpMethod : resolvedMethods) {
                    result.add(new AiEndpointDescriptor(
                            groupName,
                            groupDesc,
                            connectorType,
                            groupOrder,
                            endpointOrder,
                            httpMethod,
                            path,
                            endpointSummary,
                            endpointDesc,
                            List.copyOf(pathParams),
                            List.copyOf(queryParams),
                            bodyTypeName,
                            bodyRequired,
                            List.copyOf(bodyFields),
                            exampleBody,
                            endpointAnn.exampleResponse().trim()
                    ));
                }
            }
        }

        result.sort(Comparator
                .comparingInt(AiEndpointDescriptor::groupOrder)
                .thenComparing(AiEndpointDescriptor::groupName)
                .thenComparingInt(AiEndpointDescriptor::endpointOrder)
                .thenComparing(AiEndpointDescriptor::path)
                .thenComparing(AiEndpointDescriptor::httpMethod));
        return result;
    }

    private Set<String> extractPaths(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null && !info.getPathPatternsCondition().getPatternValues().isEmpty()) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        if (info.getPatternsCondition() != null && !info.getPatternsCondition().getPatterns().isEmpty()) {
            return info.getPatternsCondition().getPatterns();
        }
        return Set.of();
    }

    private List<ParamDescriptor> reflectBodyFields(Class<?> bodyClass) {
        if (bodyClass == null || bodyClass == Object.class || bodyClass == String.class
                || Map.class.isAssignableFrom(bodyClass) || Collection.class.isAssignableFrom(bodyClass)) {
            return List.of();
        }

        List<ParamDescriptor> fields = new ArrayList<>();
        if (bodyClass.isRecord()) {
            for (RecordComponent component : bodyClass.getRecordComponents()) {
                AiField aiField = component.getAnnotation(AiField.class);
                AiDocMeta fieldMeta = aiField != null ? aiField.value() : AiDocMeta.NONE;
                boolean required = (aiField != null && aiField.required())
                        || fieldMeta.required()
                        || component.getType().isPrimitive();
                fields.add(new ParamDescriptor(
                        component.getName(),
                        formatType(component.getGenericType()),
                        required,
                        RouteConstants.EMPTY,
                        firstNonBlank(aiField != null ? aiField.description() : RouteConstants.EMPTY, fieldMeta.description()),
                        firstNonBlank(aiField != null ? aiField.example() : RouteConstants.EMPTY, fieldMeta.example())
                ));
            }
            return fields;
        }

        for (Field field : bodyClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            AiField aiField = field.getAnnotation(AiField.class);
            AiDocMeta fieldMeta = aiField != null ? aiField.value() : AiDocMeta.NONE;
            boolean required = (aiField != null && aiField.required())
                    || fieldMeta.required()
                    || field.getType().isPrimitive();
            fields.add(new ParamDescriptor(
                    field.getName(),
                    formatType(field.getGenericType()),
                    required,
                    RouteConstants.EMPTY,
                    firstNonBlank(aiField != null ? aiField.description() : RouteConstants.EMPTY, fieldMeta.description()),
                    firstNonBlank(aiField != null ? aiField.example() : RouteConstants.EMPTY, fieldMeta.example())
            ));
        }
        return fields;
    }

    private String buildExampleBody(Class<?> bodyClass, List<ParamDescriptor> bodyFields, String paramExample) {
        if (StringUtils.hasText(paramExample)) {
            return paramExample;
        }
        if (Map.class.isAssignableFrom(bodyClass)) {
            return AiDocConstants.EMPTY_JSON_OBJECT;
        }
        if (Collection.class.isAssignableFrom(bodyClass)) {
            return AiDocConstants.EMPTY_JSON_ARRAY;
        }
        if (bodyFields.isEmpty()) {
            return RouteConstants.EMPTY;
        }

        Map<String, Object> sample = new LinkedHashMap<>();
        for (ParamDescriptor field : bodyFields) {
            if (!field.required() && !StringUtils.hasText(field.example())) {
                continue;
            }
            sample.put(field.name(), sampleValueFor(field));
        }
        try {
            return objectMapper.writeValueAsString(sample);
        } catch (Exception ex) {
            return AiDocConstants.EMPTY_JSON_OBJECT;
        }
    }

    private Object sampleValueFor(ParamDescriptor field) {
        String type = field.type();
        String example = field.example();
        if (StringUtils.hasText(example)) {
            if (AiDocConstants.TYPE_BOOLEAN_PRIMITIVE.equals(type) || AiDocConstants.TYPE_BOOLEAN_WRAPPER.equals(type)) {
                return Boolean.parseBoolean(example);
            }
            if (AiDocConstants.TYPE_INT_PRIMITIVE.equals(type) || AiDocConstants.TYPE_INTEGER_WRAPPER.equals(type)
                    || AiDocConstants.TYPE_LONG_PRIMITIVE.equals(type) || AiDocConstants.TYPE_LONG_WRAPPER.equals(type)) {
                try {
                    return Long.parseLong(example);
                } catch (NumberFormatException ignored) {
                    return example;
                }
            }
            if (example.startsWith(AiDocConstants.JSON_OBJ_START) || example.startsWith(AiDocConstants.JSON_ARR_START)) {
                try {
                    return objectMapper.readValue(example, Object.class);
                } catch (Exception ignored) {
                    return example;
                }
            }
            return example;
        }
        if (AiDocConstants.TYPE_BOOLEAN_PRIMITIVE.equals(type) || AiDocConstants.TYPE_BOOLEAN_WRAPPER.equals(type)) {
            return false;
        }
        if (AiDocConstants.TYPE_INT_PRIMITIVE.equals(type) || AiDocConstants.TYPE_INTEGER_WRAPPER.equals(type)
                || AiDocConstants.TYPE_LONG_PRIMITIVE.equals(type) || AiDocConstants.TYPE_LONG_WRAPPER.equals(type)) {
            return 0;
        }
        if (type.startsWith(AiDocConstants.TYPE_MAP_PREFIX)) {
            return Map.of();
        }
        if (type.startsWith(AiDocConstants.TYPE_LIST_PREFIX) || type.startsWith(AiDocConstants.TYPE_SET_PREFIX)) {
            return List.of();
        }
        if (AiDocConstants.TYPE_OBJECT.equals(type)) {
            return null;
        }
        return AiDocConstants.PLACEHOLDER_OPEN + field.name() + AiDocConstants.PLACEHOLDER_CLOSE;
    }

    private String formatType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getSimpleName();
        }
        if (type instanceof ParameterizedType pt) {
            String raw = formatType(pt.getRawType());
            StringBuilder sb = new StringBuilder(raw).append('<');
            Type[] args = pt.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(AiDocConstants.MD_CODE_JOINER);
                }
                sb.append(formatType(args[i]));
            }
            return sb.append('>').toString();
        }
        return type.getTypeName();
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (StringUtils.hasText(c)) {
                return c.trim();
            }
        }
        return RouteConstants.EMPTY;
    }
}
