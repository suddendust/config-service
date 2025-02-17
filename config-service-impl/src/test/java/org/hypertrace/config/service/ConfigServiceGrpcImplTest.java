package org.hypertrace.config.service;

import static org.hypertrace.config.service.ConfigServiceUtils.emptyValue;
import static org.hypertrace.config.service.TestUtils.CONTEXT1;
import static org.hypertrace.config.service.TestUtils.RESOURCE_NAME;
import static org.hypertrace.config.service.TestUtils.RESOURCE_NAMESPACE;
import static org.hypertrace.config.service.TestUtils.TENANT_ID;
import static org.hypertrace.config.service.TestUtils.getConfig1;
import static org.hypertrace.config.service.TestUtils.getConfig2;
import static org.hypertrace.config.service.TestUtils.getConfigResourceContext;
import static org.hypertrace.config.service.TestUtils.getExpectedMergedConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Value;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hypertrace.config.service.store.ConfigStore;
import org.hypertrace.config.service.v1.ContextSpecificConfig;
import org.hypertrace.config.service.v1.DeleteConfigRequest;
import org.hypertrace.config.service.v1.DeleteConfigResponse;
import org.hypertrace.config.service.v1.GetAllConfigsRequest;
import org.hypertrace.config.service.v1.GetAllConfigsResponse;
import org.hypertrace.config.service.v1.GetConfigRequest;
import org.hypertrace.config.service.v1.GetConfigResponse;
import org.hypertrace.config.service.v1.UpsertAllConfigsResponse.UpsertedConfig;
import org.hypertrace.config.service.v1.UpsertConfigRequest;
import org.hypertrace.config.service.v1.UpsertConfigResponse;
import org.hypertrace.core.grpcutils.client.GrpcClientRequestContextUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfigServiceGrpcImplTest {

  private static Value config1 = getConfig1();
  private static Value config2 = getConfig2();
  private static Value mergedConfig = getExpectedMergedConfig();
  private static ConfigResourceContext configResourceWithoutContext = getConfigResourceContext();
  private static ConfigResourceContext configResourceWithContext =
      getConfigResourceContext(CONTEXT1);

  @Test
  void upsertConfig() throws IOException {
    ConfigStore configStore = mock(ConfigStore.class);
    when(configStore.writeConfig(any(ConfigResourceContext.class), anyString(), any(Value.class)))
        .thenAnswer(
            invocation -> {
              Value config = invocation.getArgument(2, Value.class);
              return UpsertedConfig.newBuilder()
                  .setConfig(config)
                  .setCreationTimestamp(123)
                  .setUpdateTimestamp(456)
                  .build();
            });

    ConfigServiceGrpcImpl configServiceGrpc = new ConfigServiceGrpcImpl(configStore);

    StreamObserver<UpsertConfigResponse> responseObserver = mock(StreamObserver.class);
    Runnable runnableWithoutContext1 =
        () -> configServiceGrpc.upsertConfig(getUpsertConfigRequest("", config1), responseObserver);
    Runnable runnableWithoutContext2 =
        () -> configServiceGrpc.upsertConfig(getUpsertConfigRequest("", config2), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnableWithoutContext1);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnableWithoutContext2);

    Runnable runnableWithContext =
        () ->
            configServiceGrpc.upsertConfig(
                getUpsertConfigRequest(CONTEXT1, config2), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnableWithContext);

    ArgumentCaptor<UpsertConfigResponse> upsertConfigResponseCaptor =
        ArgumentCaptor.forClass(UpsertConfigResponse.class);
    verify(responseObserver, times(3)).onNext(upsertConfigResponseCaptor.capture());
    verify(responseObserver, times(3)).onCompleted();
    verify(responseObserver, never()).onError(any(Throwable.class));
    List<UpsertConfigResponse> actualResponseList = upsertConfigResponseCaptor.getAllValues();
    assertEquals(3, actualResponseList.size());
    assertEquals(config1, actualResponseList.get(0).getConfig());
    assertEquals(config2, actualResponseList.get(1).getConfig());
    assertEquals(config2, actualResponseList.get(2).getConfig());
    assertEquals(123L, actualResponseList.get(0).getCreationTimestamp());
    assertEquals(456L, actualResponseList.get(0).getUpdateTimestamp());
  }

  @Test
  void getConfig() throws IOException {
    ConfigStore configStore = mock(ConfigStore.class);
    when(configStore.getConfig(eq(configResourceWithoutContext)))
        .thenReturn(ContextSpecificConfig.newBuilder().setConfig(config1).build());
    when(configStore.getConfig(eq(configResourceWithContext)))
        .thenReturn(
            ContextSpecificConfig.newBuilder().setConfig(config2).setContext(CONTEXT1).build());
    ConfigServiceGrpcImpl configServiceGrpc = new ConfigServiceGrpcImpl(configStore);
    StreamObserver<GetConfigResponse> responseObserver = mock(StreamObserver.class);

    Runnable runnableWithoutContext =
        () -> configServiceGrpc.getConfig(getGetConfigRequest(), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnableWithoutContext);

    Runnable runnableWithContext =
        () -> configServiceGrpc.getConfig(getGetConfigRequest(CONTEXT1), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnableWithContext);

    ArgumentCaptor<GetConfigResponse> getConfigResponseCaptor =
        ArgumentCaptor.forClass(GetConfigResponse.class);
    verify(responseObserver, times(2)).onNext(getConfigResponseCaptor.capture());
    verify(responseObserver, times(2)).onCompleted();
    verify(responseObserver, never()).onError(any(Throwable.class));

    List<GetConfigResponse> actualResponseList = getConfigResponseCaptor.getAllValues();
    assertEquals(2, actualResponseList.size());
    assertEquals(
        GetConfigResponse.newBuilder().setConfig(config1).build(), actualResponseList.get(0));
    assertEquals(
        GetConfigResponse.newBuilder().setConfig(mergedConfig).build(), actualResponseList.get(1));
  }

  @Test
  void getAllConfigs() throws IOException {
    ConfigStore configStore = mock(ConfigStore.class);
    List<ContextSpecificConfig> contextSpecificConfigList = new ArrayList<>();
    contextSpecificConfigList.add(ContextSpecificConfig.newBuilder().setConfig(config1).build());
    contextSpecificConfigList.add(
        ContextSpecificConfig.newBuilder().setContext(CONTEXT1).setConfig(config2).build());
    when(configStore.getAllConfigs(
            new ConfigResource(RESOURCE_NAME, RESOURCE_NAMESPACE, TENANT_ID)))
        .thenReturn(contextSpecificConfigList);
    ConfigServiceGrpcImpl configServiceGrpc = new ConfigServiceGrpcImpl(configStore);
    StreamObserver<GetAllConfigsResponse> responseObserver = mock(StreamObserver.class);

    Runnable runnable =
        () -> configServiceGrpc.getAllConfigs(getGetAllConfigsRequest(), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnable);

    ArgumentCaptor<GetAllConfigsResponse> getAllConfigsResponseCaptor =
        ArgumentCaptor.forClass(GetAllConfigsResponse.class);
    verify(responseObserver, times(1)).onNext(getAllConfigsResponseCaptor.capture());
    verify(responseObserver, times(1)).onCompleted();
    verify(responseObserver, never()).onError(any(Throwable.class));

    GetAllConfigsResponse actualResponse = getAllConfigsResponseCaptor.getValue();
    assertEquals(contextSpecificConfigList, actualResponse.getContextSpecificConfigsList());
  }

  @Test
  void deleteConfig() throws IOException {
    ConfigStore configStore = mock(ConfigStore.class);
    ContextSpecificConfig deletedConfig =
        ContextSpecificConfig.newBuilder().setConfig(config2).setContext(CONTEXT1).build();
    when(configStore.getConfig(eq(configResourceWithContext))).thenReturn(deletedConfig);
    ConfigServiceGrpcImpl configServiceGrpc = new ConfigServiceGrpcImpl(configStore);
    StreamObserver<DeleteConfigResponse> responseObserver = mock(StreamObserver.class);

    Runnable runnable =
        () -> configServiceGrpc.deleteConfig(getDeleteConfigRequest(), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnable);

    verify(configStore, times(1))
        .writeConfig(eq(configResourceWithContext), eq(""), eq(emptyValue()));
    verify(responseObserver, times(1))
        .onNext(eq(DeleteConfigResponse.newBuilder().setDeletedConfig(deletedConfig).build()));
    verify(responseObserver, times(1)).onCompleted();
    verify(responseObserver, never()).onError(any(Throwable.class));
  }

  @Test
  void deleteDefaultContextConfig() throws IOException {
    ConfigStore configStore = mock(ConfigStore.class);
    ContextSpecificConfig deletedConfig =
        ContextSpecificConfig.newBuilder().setConfig(config2).build();
    when(configStore.getConfig(eq(configResourceWithoutContext))).thenReturn(deletedConfig);
    ConfigServiceGrpcImpl configServiceGrpc = new ConfigServiceGrpcImpl(configStore);
    StreamObserver<DeleteConfigResponse> responseObserver = mock(StreamObserver.class);

    Runnable runnable =
        () ->
            configServiceGrpc.deleteConfig(
                getDefaultContextDeleteConfigRequest(), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnable);

    verify(configStore, times(1))
        .writeConfig(eq(configResourceWithoutContext), eq(""), eq(emptyValue()));
    verify(responseObserver, times(1))
        .onNext(eq(DeleteConfigResponse.newBuilder().setDeletedConfig(deletedConfig).build()));
    verify(responseObserver, times(1)).onCompleted();
    verify(responseObserver, never()).onError(any(Throwable.class));
  }

  @Test
  void deletingNonExistingConfigShouldThrowError() throws IOException {
    ConfigStore configStore = mock(ConfigStore.class);
    ContextSpecificConfig emptyConfig = ConfigServiceUtils.emptyConfig(CONTEXT1);
    when(configStore.getConfig(eq(configResourceWithContext))).thenReturn(emptyConfig);
    ConfigServiceGrpcImpl configServiceGrpc = new ConfigServiceGrpcImpl(configStore);
    StreamObserver<DeleteConfigResponse> responseObserver = mock(StreamObserver.class);

    Runnable runnable =
        () -> configServiceGrpc.deleteConfig(getDeleteConfigRequest(), responseObserver);
    GrpcClientRequestContextUtil.executeInTenantContext(TENANT_ID, runnable);

    ArgumentCaptor<Throwable> throwableArgumentCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver, times(1)).onError(throwableArgumentCaptor.capture());
    Throwable throwable = throwableArgumentCaptor.getValue();
    assertTrue(throwable instanceof StatusException);
    assertEquals(Status.NOT_FOUND, ((StatusException) throwable).getStatus());

    verify(configStore, never())
        .writeConfig(any(ConfigResourceContext.class), anyString(), any(Value.class));
    verify(responseObserver, never()).onNext(any(DeleteConfigResponse.class));
    verify(responseObserver, never()).onCompleted();
  }

  private UpsertConfigRequest getUpsertConfigRequest(String context, Value config) {
    return UpsertConfigRequest.newBuilder()
        .setResourceName(RESOURCE_NAME)
        .setResourceNamespace(RESOURCE_NAMESPACE)
        .setContext(context)
        .setConfig(config)
        .build();
  }

  private GetConfigRequest getGetConfigRequest(String... contexts) {
    return GetConfigRequest.newBuilder()
        .setResourceName(RESOURCE_NAME)
        .setResourceNamespace(RESOURCE_NAMESPACE)
        .addAllContexts(Arrays.asList(contexts))
        .build();
  }

  private GetAllConfigsRequest getGetAllConfigsRequest() {
    return GetAllConfigsRequest.newBuilder()
        .setResourceName(RESOURCE_NAME)
        .setResourceNamespace(RESOURCE_NAMESPACE)
        .build();
  }

  private DeleteConfigRequest getDeleteConfigRequest() {
    return DeleteConfigRequest.newBuilder()
        .setResourceName(RESOURCE_NAME)
        .setResourceNamespace(RESOURCE_NAMESPACE)
        .setContext(CONTEXT1)
        .build();
  }

  private DeleteConfigRequest getDefaultContextDeleteConfigRequest() {
    return DeleteConfigRequest.newBuilder()
        .setResourceName(RESOURCE_NAME)
        .setResourceNamespace(RESOURCE_NAMESPACE)
        .build();
  }
}
