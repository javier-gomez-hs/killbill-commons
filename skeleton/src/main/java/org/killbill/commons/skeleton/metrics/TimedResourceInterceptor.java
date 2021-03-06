/*
 * Copyright 2010-2014 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.commons.skeleton.metrics;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

import org.killbill.commons.metrics.MetricTag;

import com.codahale.metrics.MetricRegistry;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import com.sun.jersey.spi.container.ExceptionMapperContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * A method interceptor which times the execution of the annotated resource method.
 */
public class TimedResourceInterceptor implements MethodInterceptor {

    private final Provider<GuiceContainer> jerseyContainer;
    private final Provider<MetricRegistry> metricRegistry;
    private final String resourcePath;
    private final String metricName;
    private String httpMethod;

    public TimedResourceInterceptor(Provider<GuiceContainer> jerseyContainer,
                                    Provider<MetricRegistry> metricRegistry,
                                    String resourcePath,
                                    String metricName,
                                    String httpMethod) {
        this.jerseyContainer = jerseyContainer;
        this.metricRegistry = metricRegistry;
        this.resourcePath = resourcePath;
        this.metricName = metricName;
        this.httpMethod = httpMethod;
    }

    @Override
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        final long startTime = System.nanoTime();
        int responseStatus = 0;
        try {
            final Object response = invocation.proceed();
            if (response instanceof Response) {
                responseStatus = ((Response) response).getStatus();
            } else if (response == null || response instanceof Void) {
                responseStatus = Response.Status.NO_CONTENT.getStatusCode();
            } else {
                responseStatus = Response.Status.OK.getStatusCode();
            }

            return response;
        } catch (WebApplicationException e) {
            responseStatus = e.getResponse().getStatus();

            throw e;
        } catch (final Throwable e) {
            responseStatus = mapException(e);

            throw e;
        } finally {
            final long endTime = System.nanoTime();

            final ResourceTimer timer = timer(invocation);
            timer.update(responseStatus, endTime - startTime, TimeUnit.NANOSECONDS);
        }
    }

    private int mapException(final Throwable e) throws Exception {
        final ExceptionMapperContext exceptionMapperContext = jerseyContainer.get().getWebApplication().getExceptionMapperContext();
        final ExceptionMapper exceptionMapper = exceptionMapperContext.find(e.getClass());

        if (exceptionMapper != null) {
            return exceptionMapper.toResponse(e).getStatus();
        }
        // If there's no mapping for it, assume 500
        return Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
    }

    private ResourceTimer timer(MethodInvocation invocation) {
        final Map<String, Object> metricTags = metricTags(invocation);

        return new ResourceTimer(resourcePath, metricName, httpMethod, metricTags, metricRegistry.get());
    }

    private static Map<String, Object> metricTags(MethodInvocation invocation) {
        final LinkedHashMap<String, Object> metricTags = new LinkedHashMap<String, Object>();
        final Method method = invocation.getMethod();
        for (int i = 0; i < method.getParameterAnnotations().length; i++) {
            final Annotation[] parameterAnnotations = method.getParameterAnnotations()[i];

            final MetricTag metricTag = findMetricTagAnnotations(parameterAnnotations);
            if (metricTag != null) {
                final Object currentArgument = invocation.getArguments()[i];
                final Object tagValue;
                if (metricTag.property().trim().isEmpty()) {
                    tagValue = currentArgument;
                } else {
                    tagValue = getProperty(currentArgument, metricTag.property());
                }
                metricTags.put(metricTag.tag(), tagValue);
            }
        }

        return metricTags;
    }

    private static MetricTag findMetricTagAnnotations(Annotation[] parameterAnnotations) {
        for (final Annotation parameterAnnotation : parameterAnnotations) {
            if (parameterAnnotation instanceof MetricTag) {
                return (MetricTag) parameterAnnotation;
            }
        }
        return null;
    }

    private static Object getProperty(Object currentArgument, String property) {
        if (currentArgument == null) {
            return null;
        }

        try {
            final String[] methodNames = new String[] { "get" + capitalize(property), "is" + capitalize(property), property };
            Method propertyMethod = null;
            for (String methodName : methodNames) {
                try {
                    propertyMethod = currentArgument.getClass().getMethod(methodName);
                    break;
                } catch (NoSuchMethodException e) {}
            }
            if (propertyMethod == null) {
                throw handleReadPropertyError(currentArgument, property, null);
            }
            return propertyMethod.invoke(currentArgument);
        } catch (IllegalAccessException e) {
            throw handleReadPropertyError(currentArgument, property, e);
        } catch (InvocationTargetException e) {
            throw handleReadPropertyError(currentArgument, property, e);
        }
    }

    private static String capitalize(String property) {
        return property.substring(0, 1).toUpperCase() + property.substring(1);
    }

    private static IllegalArgumentException handleReadPropertyError(Object object, String property, Exception e) {
        return new IllegalArgumentException(String.format("Failed to read tag property \"%s\" value from object of type %s", property, object.getClass()), e);
    }
}
