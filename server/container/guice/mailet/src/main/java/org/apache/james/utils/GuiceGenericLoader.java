/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.utils;

import java.util.stream.Stream;

import com.google.inject.Injector;

public class GuiceGenericLoader<T> {
    private final Injector injector;
    private final NamingScheme namingScheme;
    private final ExtendedClassLoader extendedClassLoader;

    public GuiceGenericLoader(Injector injector, ExtendedClassLoader extendedClassLoader, NamingScheme namingScheme) {
        this.injector = injector;
        this.namingScheme = namingScheme;
        this.extendedClassLoader = extendedClassLoader;
    }

    public T instanciate(ClassName className) throws Exception {
        Class<T> clazz = locateClass(className);
        return injector.getInstance(clazz);
    }

    private Class<T> locateClass(ClassName className) throws ClassNotFoundException {
        return namingScheme.toFullyQualifiedClassNames(className)
            .flatMap(this::tryLocateClass)
            .findFirst()
            .orElseThrow(() -> new ClassNotFoundException(className.getName()));
    }

    private Stream<Class<T>> tryLocateClass(FullyQualifiedClassName className) {
        try {
            return Stream.of(extendedClassLoader.locateClass(className));
        } catch (ClassNotFoundException e) {
            return Stream.empty();
        }
    }

}
