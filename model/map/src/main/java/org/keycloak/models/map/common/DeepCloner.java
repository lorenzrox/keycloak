/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.models.map.common;

import org.keycloak.models.map.common.delegate.DelegateProvider;
import org.keycloak.models.map.common.delegate.EntityFieldDelegate;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Helper class for deep cloning and fine-grained instantiation per interface and deep copying their properties.
 * <p>
 * This class is intended to be used by individual map storage implementations for copying
 * over entities into their native implementations.
 * <p>
 * For example, a {@code MapClientEntity} interface could be implemented by {@code MapClientEntityImpl}
 * (used by a file-based storage in this example) and an {@code HotRodClientEntityImpl} (for Infinispan).
 * Say that the Infinispan is stacked on top of the file-based storage to provide caching layer.
 * Upon first read, a {@code MapClientEntityImpl} could be obtained from file-based storage and passed
 * to Infinispan layer for caching. Infinispan, regardless of the actual implementation, need to store
 * the {@code MapClientEntity} data in a form that can be processed and sent over the wire in Infinispan
 * (say in an {@code InfinispanClientEntityImpl}). To achieve this, the Infinispan store has to clone
 * the file entity values from the {@code MapClientEntityImpl} to {@code InfinispanClientEntityImpl},
 * i.e. it performs deep cloning, using this helper class.
 * <p>
 * <i>Broader context:</i>
 * In tree store, map storages are agnostic to their neighbours. Therefore each implementation can be
 * provided with a record (a {@code MapClientEntity} instance in the example above) originating from
 * any other implementation. For a map storage to process the record (beyond read-only mode),
 * it needs to be able to clone it into its own entity. Each of the storages thus can benefit from
 * the {@code DeepCloner} capabilities.
 *
 * @author hmlnarik
 */
public class DeepCloner {

    /**
     * Marker for interfaces that could be requested for instantiation and cloning.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Root {}

    /**
     * Function that clones properties from {@code original} object to a {@code target} object and returns
     * the cloned object (usually the same as the {@code target}).
     * @param <V> Object class
     */
    @FunctionalInterface
    public interface Cloner<V> {
        /**
         * Function that clones properties from {@code original} object to a {@code target} object and returns
         * the cloned object (usually the same as the {@code target}).
         */
        V clone(V original, V target);
    }

    /**
     * Function that instantiates a delegation object of type {@code V} with the given delegate provider
     * @param <V> Object class
     */
    @FunctionalInterface
    public interface DelegateCreator<V> {
        /**
         * Function that instantiates a delegation object of type {@code V} with the given delegate provider.
         */
        V create(DelegateProvider<V> delegateProvider);
    }

    /**
     * Function that instantiates a delegation object of type {@code V} with the given per-field delegate provider
     * @param <V> Object class
     */
    @FunctionalInterface
    public interface EntityFieldDelegateCreator<V> {
        /**
         * Function that instantiates a delegation object of type {@code V} with the given per-field delegate provider.
         */
        V create(EntityFieldDelegate<V> entityDelegateProvider);
    }

    public static final DeepCloner DUMB_CLONER = new Builder().build();

    /**
     * Builder for the {@code DeepCloner} helper class.
     */
    public static class Builder {
        private final Map<Class<?>, Supplier<?>> parameterlessConstructors = new HashMap<>();
        private final Map<Class<?>, Function<DeepCloner, ?>> constructors = new HashMap<>();
        private final Map<Class<?>, Cloner<?>> clonersWithId = new HashMap<>(org.keycloak.models.map.common.AutogeneratedCloners.CLONERS_WITH_ID);
        private final Map<Class<?>, Cloner<?>> clonersWithoutId = new HashMap<>(org.keycloak.models.map.common.AutogeneratedCloners.CLONERS_WITHOUT_ID);
        private final Map<Class<?>, DelegateCreator<?>> delegateCreators = new HashMap<>(org.keycloak.models.map.common.AutogeneratedCloners.DELEGATE_CREATORS);
        private final Map<Class<?>, EntityFieldDelegateCreator<?>> entityFieldDelegateCreators = new HashMap<>(org.keycloak.models.map.common.AutogeneratedCloners.ENTITY_FIELD_DELEGATE_CREATORS);
        private Cloner<?> genericCloner = (from, to) -> { throw new IllegalStateException("Cloner not found for class " + (from == null ? "<null>" : from.getClass())); };

        /**
         * Returns a {@link DeepCloner} initialized with the respective constructors and cloners.
         * @return
         */
        public DeepCloner build() {
            return new DeepCloner(parameterlessConstructors, constructors, delegateCreators, entityFieldDelegateCreators, clonersWithId, clonersWithoutId, genericCloner);
        }

        private <V> void forThisClassAndAllMarkedParentsAndInterfaces(Class<V> rootClazz, Consumer<Class<?>> action) {
            action.accept(rootClazz);

            Stack<Class<?>> c = new Stack<>();
            c.push(rootClazz);
            while (! c.isEmpty()) {
                Class<?> cl = c.pop();
                if (cl == null) {
                    continue;
                }

                c.push(cl.getSuperclass());
                for (Class<?> iface : cl.getInterfaces()) {
                    c.push(iface);
                }

                if (cl.getAnnotation(Root.class) != null) {
                    action.accept(cl);
                }
            }
        }

        /**
         * Adds a method, often a constructor, that instantiates a record of type {@code V}.
         *
         * @param <V> Class or interface that would be instantiated by the given methods
         * @param clazz Class or interface that would be instantiated by the given methods
         * @param constructorNoParameters Parameterless function that creates a new instance of class {@code V}.
         *          If {@code null}, parameterless constructor is not available.
         * @return This builder.
         */
        public <V> Builder constructor(Class<V> clazz, Supplier<? extends V> constructorNoParameters) {
            if (constructorNoParameters != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.parameterlessConstructors.put(cl, constructorNoParameters));
            }
            return this;
        }

        /**
         * Adds a method, often a constructor, that instantiates a record of type {@code V}.
         *
         * @param <V> Class or interface that would be instantiated by the given methods
         * @param clazz Class or interface that would be instantiated by the given methods
         * @param constructor Function that creates a new instance of class {@code V}.
         *          If {@code null}, such a single-parameter constructor is not available.
         * @return This builder.
         */
        public <V> Builder constructorDC(Class<V> clazz, Function<DeepCloner, ? extends V> constructor) {
            if (constructor != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.constructors.put(cl, constructor));
            }
            return this;
        }

        /**
         * Adds a method that instantiates an per-field delegate of type {@code V}.
         *
         * @param <V> Class or interface that would be instantiated by the given methods
         * @param clazz Class or interface that would be instantiated by the given methods
         * @param constructor Function that creates a new instance of class {@code V}.
         *          If {@code null}, such a single-parameter constructor is not available.
         * @return This builder.
         */
        public <V> Builder delegateCreator(Class<V> clazz, EntityFieldDelegateCreator<V> delegateCreator) {
            if (delegateCreator != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.entityFieldDelegateCreators.put(cl, delegateCreator));
            }
            return this;
        }

        /**
         * Adds a method, often a constructor, that instantiates a delegate of type {@code V}.
         *
         * @param <V> Class or interface that would be instantiated by the given methods
         * @param clazz Class or interface that would be instantiated by the given methods
         * @param constructor Function that creates a new instance of class {@code V}.
         *          If {@code null}, such a single-parameter constructor is not available.
         * @return This builder.
         */
        public <V> Builder delegateCreator(Class<V> clazz, DelegateCreator<V> delegateCreator) {
            if (delegateCreator != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.delegateCreators.put(cl, delegateCreator));
            }
            return this;
        }

        /**
         * Adds a method that copies (as in a deep copy) an object properties from one object to another
         *
         * @param <V> Class or interface whose instance would be copied over to another instance by the given cloner
         * @param clazz Class or interface whose instance would be copied over to another instance by the given cloner
         * @param cloner A method for cloning with the following signature: {@code V deepClone(V from, V to)} which
         *          copies properties of an object {@code from} onto the object {@code to}. This
         *          function usually returns {@code to}
         * @return This builder.
         */
        public <V> Builder cloner(Class<? extends V> clazz, Cloner<?> cloner) {
            if (cloner != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.clonersWithId.put(cl, cloner));
            }
            return this;
        }

        /**
         * Adds a method that copies (as in a deep copy) an object properties from one object to another
         *
         * @param <V> Class or interface whose instance would be copied over to another instance by the given cloner
         * @param clazz Class or interface whose instance would be copied over to another instance by the given cloner
         * @param clonerWithId A method for cloning with the following signature: {@code V deepClone(V from, V to)} which
         *          copies properties of an object {@code from} onto the object {@code to}. This
         *          function usually returns {@code to}
         * @return This builder.
         */
        public <V> Builder cloner(Class<? extends V> clazz, Cloner<?> clonerWithId, Cloner<?> clonerWithoutId) {
            if (clonerWithId != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.clonersWithId.put(cl, clonerWithId));
            }
            if (clonerWithoutId != null) {
                forThisClassAndAllMarkedParentsAndInterfaces(clazz, cl -> this.clonersWithoutId.put(cl, clonerWithoutId));
            }
            return this;
        }

        /**
         * Adds a method that copies (as in a deep copy) an object properties to another object for any class
         * that is not covered by a specific cloner set via {@link #cloner(Class, BiFunction)} method.
         *
         * @param <V> Class or interface whose instance would be copied over to another instance by the given cloner
         * @param genericCloner A method for cloning which copies properties of an object onto another object. This
         *          function usually returns {@code to}
         * @return This builder.
         */
        public <V> Builder genericCloner(Cloner<V> genericCloner) {
            this.genericCloner = genericCloner;
            return this;
        }
    }

    private static final Logger LOG = Logger.getLogger(DeepCloner.class);

    private final Map<Class<?>, Supplier<?>> parameterlessConstructors;
    private final Map<Class<?>, Function<DeepCloner, ?>> constructors;
    private final Map<Class<?>, Cloner<?>> clonersWithId;
    private final Map<Class<?>, Cloner<?>> clonersWithoutId;
    private final Map<Class<?>, DelegateCreator<?>> delegateCreators;
    private final Map<Class<?>, EntityFieldDelegateCreator<?>> entityFieldDelegateCreators;
    private final Cloner<?> genericCloner;
    private final Map<Class<?>, Object> emptyInstances = new HashMap<>(AutogeneratedCloners.EMPTY_INSTANCES);

    private DeepCloner(Map<Class<?>, Supplier<?>> parameterlessConstructors,
      Map<Class<?>, Function<DeepCloner, ?>> constructors,
      Map<Class<?>, DelegateCreator<?>> delegateCreators,
      Map<Class<?>, EntityFieldDelegateCreator<?>> entityFieldDelegateCreators,
      Map<Class<?>, Cloner<?>> clonersWithId,
      Map<Class<?>, Cloner<?>> clonersWithoutId,
      Cloner<?> genericCloner) {
        this.parameterlessConstructors = parameterlessConstructors;
        this.constructors = constructors;
        this.clonersWithId = clonersWithId;
        this.clonersWithoutId = clonersWithoutId;
        this.delegateCreators = delegateCreators;
        this.genericCloner = genericCloner;
        this.entityFieldDelegateCreators = entityFieldDelegateCreators;
    }

    private <V> V getFromClassRespectingHierarchy(Map<Class<?>, V> map, Class<?> clazz) {
        // fast lookup
        V res = map.get(clazz);
        if (res != null) {
            return res;
        }

        // BFS on implemented supertypes and interfaces. Skip clazz as it has been looked up already
        LinkedList<Class<?>> ll = new LinkedList<>();
        ll.push(clazz.getSuperclass());
        for (Class<?> iface : clazz.getInterfaces()) {
            ll.push(iface);
        }

        while (! ll.isEmpty()) {
            Class<?> cl = ll.pollFirst();
            if (cl == null) {
                continue;
            }

            res = map.get(cl);
            if (res != null) {
                map.put(clazz, res);        // Wire clazz with the result for fast lookup next time
                return res;
            }

            ll.push(cl.getSuperclass());
            ll.addAll(Arrays.asList(cl.getInterfaces()));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <D, V extends D> D delegate(V delegate, DelegateProvider<D> delegateProvider) {
        return delegate((Class<V>) delegate.getClass(), delegateProvider);
    }

    public <D, V extends D> D delegate(Class<V> delegateClass, DelegateProvider<D> delegateProvider) {
        @SuppressWarnings("unchecked")
        DelegateCreator<D> delegateCreator = (DelegateCreator<D>) getFromClassRespectingHierarchy(delegateCreators, delegateClass);
        if (delegateCreator != null) {
            return delegateCreator.create(delegateProvider);
        }
        throw new IllegalStateException("Cannot create delegate for " + delegateClass);
    }

    @SuppressWarnings("unchecked")
    public <V> V entityFieldDelegate(V delegate, EntityFieldDelegate<V> delegateProvider) {
        return entityFieldDelegate((Class<V>) delegate.getClass(), delegateProvider);
    }

    public <V> V entityFieldDelegate(Class<V> delegateClass, EntityFieldDelegate<V> delegateProvider) {
        @SuppressWarnings("unchecked")
        EntityFieldDelegateCreator<V> delegateCreator = (EntityFieldDelegateCreator<V>) getFromClassRespectingHierarchy(entityFieldDelegateCreators, delegateClass);
        if (delegateCreator != null) {
            return delegateCreator.create(delegateProvider);
        }
        throw new IllegalStateException("Cannot create delegate for " + delegateClass);
    }

    public <V> V emptyInstance(Class<V> instanceClass) {
        @SuppressWarnings("unchecked")
        V emptyInstance = (V) getFromClassRespectingHierarchy(emptyInstances, instanceClass);
        if (emptyInstance != null) {
            return emptyInstance;
        }
        throw new IllegalStateException("Cannot create empty instance for " + instanceClass);
    }

    /**
     * Creates a new instance of the given class or interface if the parameterless constructor for that type is known.
     * @param <V> Type (class or a {@code @Root} interface) to create a new instance
     * @param clazz Type (class or a {@code @Root} interface) to create a new instance
     * @return A new instance
     * @throws IllegalStateException When the constructor is not known.
     */
    public <V> V newInstance(Class<V> clazz) {
        if (clazz == null) {
            return null;
        }

        V res;
        @SuppressWarnings("unchecked")
        Function<DeepCloner, V> c = (Function<DeepCloner, V>) getFromClassRespectingHierarchy(this.constructors, clazz);
        if (c == null) {
            @SuppressWarnings("unchecked")
            Supplier<V> s = (Supplier<V>) getFromClassRespectingHierarchy(this.parameterlessConstructors, clazz);
            if (s == null) {
                try {
                    res = clazz.newInstance();
                } catch (InstantiationException | IllegalAccessException ex) {
                    res = null;
                }
            } else {
                res = s.get();
            }
        } else {
            res = c.apply(this);
        }

        if (res == null) {
            throw new IllegalStateException("Cannot instantiate " + clazz);
        }

        return res;
    }

    /**
     * Returns a class type of an instance that would be instantiated by {@link #newInstance(java.lang.Class)} method.
     * @param <V> Type (class or a {@code @Root} interface) to create a new instance
     * @param clazz Type (class or a {@code @Root} interface) to create a new instance
     * @return See description
     */
    @SuppressWarnings("unchecked")
    public <V> Class<? extends V> newInstanceType(Class<V> valueType) {
        if (valueType == null) {
            return null;
        }
        try {
            V v = newInstance(valueType);
            return v == null ? null : (Class<? extends V>) v.getClass();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    /**
     * Deeply clones properties from the {@code from} instance to the {@code to} instance.
     * @param <V> Type (class or a {@code @Root} interface) to clone the instance
     * @param from Original instance
     * @param to Instance to copy the properties onto
     * @return Instance which has all the properties same as the {@code from}. Preferably, {@code to} is returned.
     *   However {@code from} is returned if the cloner is not known and generic cloner is not available.
     */
    public <V> V deepClone(V from, V to) {
        return deepClone(from, to, this.clonersWithId);
    }

    /**
     * Deeply clones properties from the {@code from} instance to the {@code to} instance excluding the ID field.
     * @param <V> Type (class or a {@code @Root} interface) to clone the instance
     * @param from Original instance
     * @param to Instance to copy the properties onto
     * @return Instance which has all the properties same as the {@code from}. Preferably, {@code to} is returned.
     *   However {@code from} is returned if the cloner is not known and generic cloner is not available.
     */
    public <V> V deepCloneNoId(V from, V to) {
        return deepClone(from, to, this.clonersWithoutId);
    }

    @SuppressWarnings("unchecked")
    private <V> V deepClone(V from, V to, Map<Class<?>, Cloner<?>> cloners) {
        Cloner<V> cloner = (Cloner<V>) getFromClassRespectingHierarchy(cloners, from.getClass());
        if (cloner != null) {
            return cloner.clone(from, to);
        }

        if (genericCloner != null) {
            LOG.debugf("Using generic cloner for %s", from.getClass());
            final V res = ((Cloner<V>) genericCloner).clone(from, to);

            if (res instanceof UpdatableEntity) {
                ((UpdatableEntity) res).clearUpdatedFlag();
            }

            return res;
        }

        return warnCloneNotSupported(from);
    }

    /**
     * Creates a new instance of the given type and copies its properties from the {@code from} instance
     * @param <V> Type (class or a {@code @Root} interface) to create a new instance and clone properties from
     * @param newId ID of the new object
     * @param from Original instance
     * @return Newly created instance or {@code null} if {@code from} is {@code null}.
     */
    @SuppressWarnings("unchecked")
    public <V extends AbstractEntity> V from(String newId, V from) {
        if (from == null) {
            return null;
        }
        final V res = newInstance((Class<V>) from.getClass());
        if (newId != null) {
            res.setId(newId);
        }
        return deepCloneNoId(from, res);
    }

    /**
     * Creates a new instance of the given type and copies its properties from the {@code from} instance
     * @param <V> Type (class or a {@code @Root} interface) to create a new instance and clone properties from
     * @param from Original instance
     * @return Newly created instance or {@code null} if {@code from} is {@code null}.
     */
    @SuppressWarnings("unchecked")
    public <V> V from(V from) {
        return from == null ? null : deepClone(from, newInstance((Class<V>) from.getClass()));
    }

    /**
     * Issues warning in the logs and returns the input parameter {@code o}
     * @param o
     * @return The {@code o} object
     */
    public static <T> T warnCloneNotSupported(T o) {
        if (o != null) {
            LOG.warnf("Cloning not supported for %s, returning the same instance!", o.getClass());
        }
        return o;
    }

}
