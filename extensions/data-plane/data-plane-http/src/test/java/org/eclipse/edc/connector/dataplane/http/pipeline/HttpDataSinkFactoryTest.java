/*
 *  Copyright (c) 2021 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - add functionalities - improvements
 *
 */

package org.eclipse.edc.connector.dataplane.http.pipeline;

import io.netty.handler.codec.http.HttpMethod;
import okhttp3.Request;
import org.eclipse.edc.connector.dataplane.http.params.HttpRequestParams;
import org.eclipse.edc.connector.dataplane.http.params.HttpRequestParamsProvider;
import org.eclipse.edc.connector.dataplane.http.testfixtures.HttpTestFixtures;
import org.eclipse.edc.connector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.http.EdcHttpClient;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.HttpDataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.connector.dataplane.http.testfixtures.HttpTestFixtures.createHttpResponse;
import static org.eclipse.edc.spi.types.domain.HttpDataAddress.HTTP_DATA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpDataSinkFactoryTest {

    private final EdcHttpClient httpClient = mock(EdcHttpClient.class);
    private final Monitor monitor = mock(Monitor.class);
    private final ExecutorService executorService = mock(ExecutorService.class);
    private final HttpRequestParamsProvider provider = mock(HttpRequestParamsProvider.class);

    private HttpDataSinkFactory factory;

    @BeforeEach
    void setUp() {
        factory = new HttpDataSinkFactory(httpClient, Executors.newFixedThreadPool(1), 5, mock(Monitor.class), provider);
    }

    @Test
    void verifyCanHandle() {
        assertThat(factory.canHandle(HttpTestFixtures.createRequest(HTTP_DATA).build())).isTrue();
    }

    @Test
    void verifyCannotHandle() {
        assertThat(factory.canHandle(HttpTestFixtures.createRequest("dummy").build())).isFalse();
    }

    @Test
    void verifyValidationFailsIfProviderThrows() {
        var errorMsg = "Test error message";
        var address = HttpDataAddress.Builder.newInstance().build();
        var request = createRequest(address);
        when(provider.provideSinkParams(request)).thenThrow(new EdcException(errorMsg));

        var result = factory.validate(request);

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureMessages()).hasSize(1);
        assertThat(result.getFailureMessages().get(0)).contains(errorMsg);
    }

    @Test
    void verifySuccessSinkCreation() {
        var address = HttpDataAddress.Builder.newInstance().build();
        var request = createRequest(address);
        var params = HttpRequestParams.Builder.newInstance()
                .baseUrl("http://some.base.url")
                .method(HttpMethod.POST.name())
                .contentType("application/json")
                .build();
        when(provider.provideSinkParams(request)).thenReturn(params);

        assertThat(factory.validate(request).succeeded()).isTrue();
        var sink = factory.createSink(request);
        assertThat(sink).isNotNull();

        var expected = HttpDataSink.Builder.newInstance()
                .params(params)
                .httpClient(httpClient)
                .monitor(monitor)
                .requestId(request.getId())
                .executorService(executorService)
                .build();

        // validate the generated data sink field by field using reflection
        Arrays.stream(HttpDataSink.class.getDeclaredFields()).forEach(f -> {
            f.setAccessible(true);
            try {
                assertThat(f.get(sink)).isEqualTo(f.get(expected));
            } catch (IllegalAccessException e) {
                throw new AssertionError("Comparison failed for field: " + f.getName());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "POST", "POST", "PUT" })
    void verifyCreateMethodDestination(String method) throws IOException {
        var address = HttpDataAddress.Builder.newInstance().build();
        var request = createRequest(address);
        var params = HttpRequestParams.Builder.newInstance()
                .baseUrl("http://some.base.url")
                .method(method)
                .contentType("application/json")
                .build();
        when(provider.provideSinkParams(request)).thenReturn(params);
        when(httpClient.execute(ArgumentMatchers.isA(Request.class))).thenReturn(createHttpResponse().build());

        var future = factory.createSink(request)
                .transfer(new InputStreamDataSource("test", new ByteArrayInputStream("test".getBytes())));

        assertThat(future).succeedsWithin(10, TimeUnit.SECONDS)
                .satisfies(result -> assertThat(result.succeeded()).isTrue());
    }

    private DataFlowRequest createRequest(DataAddress destination) {
        return DataFlowRequest.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .processId(UUID.randomUUID().toString())
                .sourceDataAddress(DataAddress.Builder.newInstance().type("test-type").build())
                .destinationDataAddress(destination)
                .build();
    }
}
