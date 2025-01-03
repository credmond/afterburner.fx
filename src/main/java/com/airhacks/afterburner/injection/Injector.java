package com.airhacks.afterburner.injection;

/*
 * #%L
 * afterburner.fx
 * %%
 * Copyright (C) 2013 Adam Bien
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

import com.airhacks.afterburner.configuration.Configurator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author adam-bien.com
 */
public class Injector {

    private static final Map<Class<?>, Object> modelsAndServices = new WeakHashMap<>();
    private static final Set<Object> presenters = Collections.newSetFromMap(new WeakHashMap<>());

    private static Function<Class<?>, Object> instanceSupplier = getDefaultInstanceSupplier();

    private static Logger LOGGER = LoggerFactory.getLogger(Injector.class);

    private static final Configurator configurator = new Configurator();

    public static <T> T instantiatePresenter(Class<T> clazz, Function<String, Object> injectionContext) {
        @SuppressWarnings("unchecked")
        T presenter = registerExistingAndInject((T) instanceSupplier.apply(clazz), injectionContext);
        return presenter;
    }

    public static <T> T instantiatePresenter(Class<T> clazz) {
        return instantiatePresenter(clazz, f -> null);
    }

    public static void setInstanceSupplier(Function<Class<?>, Object> instanceSupplier) {
        Injector.instanceSupplier = instanceSupplier;
    }

    public static void setConfigurationSource(Function<Object, Object> configurationSupplier) {
        configurator.set(configurationSupplier);
    }

    public static void resetInstanceSupplier() {
        instanceSupplier = getDefaultInstanceSupplier();
    }

    public static void resetConfigurationSource() {
        configurator.forgetAll();
    }

    public static <T> T registerExistingAndInject(T instance) {
        return registerExistingAndInject(instance, x -> null);
    }

    /**
     * Caches the passed presenter internally and injects all fields
     *
     * @param <T> the class to initialize
     * @param instance An already existing (legacy) presenter interesting in
     * injection
     * @param injectionContext
     * @return presenter with injected fields
     */
    public static <T> T registerExistingAndInject(T instance, Function<String, Object> injectionContext) {
        T product = injectAndInitialize(instance, injectionContext);
        presenters.add(product);
        return product;
    }

    public static <T> T instantiateModelOrService(Class<T> clazz) {
        return instantiateModelOrService(clazz, x -> null);
    }

    @SuppressWarnings("unchecked")
    public static <T> T instantiateModelOrService(Class<T> clazz, Function<String, Object> injectionContext) {
        T product = (T) modelsAndServices.get(clazz);
        if (product == null) {
            product = injectAndInitialize((T) instanceSupplier.apply(clazz), injectionContext);
            modelsAndServices.putIfAbsent(clazz, product);
        }
        return clazz.cast(product);
    }

    public static <T> void setModelOrService(Class<T> clazz, T instance) {
        modelsAndServices.put(clazz, instance);
    }

    static <T> T injectAndInitialize(T product) {
        return injectAndInitialize(product, x -> null);
    }

    static <T> T injectAndInitialize(T product, Function<String, Object> injectionContext) {
        injectMembers(product, injectionContext);
        initialize(product);
        return product;
    }

    public static void injectMembers(final Object instance, Function<String, Object> injectionContext) {
        Class<? extends Object> clazz = instance.getClass();
        injectMembers(clazz, instance, injectionContext);
    }

    public static void injectMembers(Class<? extends Object> clazz, final Object instance, Function<String, Object> injectionContext) throws SecurityException {
        LOGGER.debug("Injecting members for class " + clazz + " and instance " + instance);
        Field[] fields = clazz.getDeclaredFields();
        for (final Field field : fields) {
            if (field.isAnnotationPresent(Inject.class)) {
                LOGGER.trace("Field annotated with @Inject found: " + field);
                Class<?> type = field.getType();
                String fieldName = field.getName();

                // First try the configurator
                Object value = configurator.getProperty(clazz, fieldName);
                LOGGER.trace("Value returned by configurator is: " + value);

                // Next try injection context
                if (value == null) {
                    value = injectionContext.apply(fieldName);
                }

                if ((value == null) && isNotPrimitiveOrString(type)) {
                    LOGGER.trace("Field is not a JDK class");
                    value = instantiateModelOrService(type, injectionContext);
                }

                if (value != null) {
                    LOGGER.trace("Value is a primitive, injecting...");
                    injectIntoField(field, instance, value);
                }
            }
        }
        Class<? extends Object> superclass = clazz.getSuperclass();
        if (superclass != null) {
            LOGGER.trace("Injecting members of: " + superclass);
            injectMembers(superclass, instance, injectionContext);
        }
    }

    static void injectIntoField(final Field field, final Object instance, final Object target) {
        boolean wasAccessible = field.canAccess(instance);
            try {
                field.setAccessible(true);
                field.set(instance, target);
            } catch (IllegalArgumentException | IllegalAccessException ex) {
                throw new IllegalStateException("Cannot set field: " + field + " with value " + target, ex);
            } finally {
                field.setAccessible(wasAccessible);
            }
    }

    static void initialize(Object instance) {
        Class<? extends Object> clazz = instance.getClass();
        invokeMethodWithAnnotation(clazz, instance, PostConstruct.class
        );
    }

    static void destroy(Object instance) {
        Class<? extends Object> clazz = instance.getClass();
        invokeMethodWithAnnotation(clazz, instance, PreDestroy.class
        );
    }

    static void invokeMethodWithAnnotation(Class<?> clazz, final Object instance, final Class<? extends Annotation> annotationClass) throws IllegalStateException, SecurityException {
        Method[] declaredMethods = clazz.getDeclaredMethods();
        for (final Method method : declaredMethods) {
            if (method.isAnnotationPresent(annotationClass)) {
                    boolean wasAccessible = method.canAccess(instance);
                    try {
                        method.setAccessible(true);
                        method.invoke(instance);
                    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                        throw new IllegalStateException("Problem invoking " + annotationClass + " : " + method, ex);
                    } finally {
                        method.setAccessible(wasAccessible);
                    }
            }
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            invokeMethodWithAnnotation(superclass, instance, annotationClass);
        }
    }

    public static void forgetAll() {
        Collection<Object> values = modelsAndServices.values();
        values.forEach(Injector::destroy);
        presenters.forEach(Injector::destroy);
        presenters.clear();
        modelsAndServices.clear();
        resetInstanceSupplier();
        resetConfigurationSource();
    }

    static Function<Class<? extends Object> , Object> getDefaultInstanceSupplier() {
        return (c) -> {
            try {
            	return c.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new IllegalStateException("Cannot instantiate view: " + c, ex);
            }
        };
    }

    private static boolean isNotPrimitiveOrString(Class<?> type) {
        return !type.isPrimitive() && !type.isAssignableFrom(String.class);
    }
}
