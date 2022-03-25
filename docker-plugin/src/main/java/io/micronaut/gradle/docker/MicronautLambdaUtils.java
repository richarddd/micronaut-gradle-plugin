/*
 * Copyright 2017-2022 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.gradle.docker;

/**
 * Utility class for Micronaut and AWS Lambda.
 * @author Sergio del Amo
 * @since 3.3.2
 */
public final class MicronautLambdaUtils {

    /**
     * Constructor.
     */
    private MicronautLambdaUtils() {

    }

    /**
     * Main class for Provided runtime and Application Type: Micronaut Application.
     */
    public static final String MICRONAUT_LAMBDA_RUNTIME = "io.micronaut.function.aws.runtime.MicronautLambdaRuntime";
}
