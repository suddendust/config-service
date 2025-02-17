package org.hypertrace.label.config.service;

import static java.util.function.Function.identity;

import com.google.common.util.concurrent.Striped;
import com.google.protobuf.Duration;
import com.google.protobuf.util.JsonFormat;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hypertrace.config.objectstore.ContextualConfigObject;
import org.hypertrace.config.service.change.event.api.ConfigChangeEventGenerator;
import org.hypertrace.config.service.v1.ConfigServiceGrpc;
import org.hypertrace.core.grpcutils.client.RequestContextClientCallCredsProviderFactory;
import org.hypertrace.core.grpcutils.context.RequestContext;
import org.hypertrace.label.config.service.v1.CreateLabelRequest;
import org.hypertrace.label.config.service.v1.CreateLabelResponse;
import org.hypertrace.label.config.service.v1.DeleteLabelRequest;
import org.hypertrace.label.config.service.v1.DeleteLabelResponse;
import org.hypertrace.label.config.service.v1.GetLabelRequest;
import org.hypertrace.label.config.service.v1.GetLabelResponse;
import org.hypertrace.label.config.service.v1.GetLabelsRequest;
import org.hypertrace.label.config.service.v1.GetLabelsResponse;
import org.hypertrace.label.config.service.v1.GetOrCreateLabelsRequest;
import org.hypertrace.label.config.service.v1.GetOrCreateLabelsResponse;
import org.hypertrace.label.config.service.v1.Label;
import org.hypertrace.label.config.service.v1.LabelData;
import org.hypertrace.label.config.service.v1.LabelsConfigServiceGrpc;
import org.hypertrace.label.config.service.v1.UpdateLabelRequest;
import org.hypertrace.label.config.service.v1.UpdateLabelResponse;

@Slf4j
public class LabelsConfigServiceImpl extends LabelsConfigServiceGrpc.LabelsConfigServiceImplBase {
  private static final Duration WAIT_TIME = Duration.newBuilder().setSeconds(5).build();
  private static final String LABELS_CONFIG_SERVICE_CONFIG = "labels.config.service";
  private static final String SYSTEM_LABELS = "system.labels";
  private static final int LABEL_LOCK_STRIPE_COUNT = 1000;
  private final LabelStore labelStore;
  private final List<Label> systemLabels;
  private final Map<String, Label> systemLabelsIdLabelMap;
  private final Map<String, Label> systemLabelsKeyLabelMap;
  private final Striped<Lock> stripedLabelsLock;

  public LabelsConfigServiceImpl(
      Channel configChannel, Config config, ConfigChangeEventGenerator configChangeEventGenerator) {
    stripedLabelsLock = Striped.lazyWeakLock(LABEL_LOCK_STRIPE_COUNT);
    labelStore =
        new LabelStore(
            ConfigServiceGrpc.newBlockingStub(configChannel)
                .withCallCredentials(
                    RequestContextClientCallCredsProviderFactory.getClientCallCredsProvider()
                        .get()),
            configChangeEventGenerator);
    List<? extends ConfigObject> systemLabelsObjectList = null;
    if (config.hasPath(LABELS_CONFIG_SERVICE_CONFIG)) {
      Config labelConfig = config.getConfig(LABELS_CONFIG_SERVICE_CONFIG);
      if (labelConfig.hasPath(SYSTEM_LABELS)) {
        systemLabelsObjectList = labelConfig.getObjectList(SYSTEM_LABELS);
      }
    }
    if (systemLabelsObjectList != null) {
      systemLabels = buildSystemLabelList(systemLabelsObjectList);
      systemLabelsIdLabelMap =
          systemLabels.stream().collect(Collectors.toUnmodifiableMap(Label::getId, identity()));
      systemLabelsKeyLabelMap =
          systemLabels.stream()
              .collect(
                  Collectors.toUnmodifiableMap((label) -> label.getData().getKey(), identity()));
    } else {
      systemLabels = Collections.emptyList();
      systemLabelsIdLabelMap = Collections.emptyMap();
      systemLabelsKeyLabelMap = Collections.emptyMap();
    }
  }

  private List<Label> buildSystemLabelList(List<? extends ConfigObject> configObjectList) {
    return configObjectList.stream()
        .map(LabelsConfigServiceImpl::buildLabelFromConfig)
        .collect(Collectors.toUnmodifiableList());
  }

  @SneakyThrows
  private static Label buildLabelFromConfig(ConfigObject configObject) {
    String jsonString = configObject.render();
    Label.Builder builder = Label.newBuilder();
    JsonFormat.parser().merge(jsonString, builder);
    return builder.build();
  }

  @Override
  public void createLabel(
      CreateLabelRequest request, StreamObserver<CreateLabelResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      Lock labelsLock = this.stripedLabelsLock.get(requestContext.getTenantId());
      if (labelsLock.tryLock(WAIT_TIME.getSeconds(), TimeUnit.SECONDS)) {
        try {
          LabelData labelData = request.getData();
          if (isDuplicateKey(requestContext, labelData.getKey())) {
            // Creating a label with a name that clashes with one of system labels name
            responseObserver.onError(new StatusRuntimeException(Status.ALREADY_EXISTS));
            return;
          }
          Label label =
              Label.newBuilder().setId(UUID.randomUUID().toString()).setData(labelData).build();
          if (request.hasCreatedByApplicationRuleId()) {
            label =
                label.toBuilder()
                    .setCreatedByApplicationRuleId(request.getCreatedByApplicationRuleId())
                    .build();
          }
          Label createdLabel = labelStore.upsertObject(requestContext, label).getData();
          responseObserver.onNext(CreateLabelResponse.newBuilder().setLabel(createdLabel).build());
          responseObserver.onCompleted();
        } finally {
          labelsLock.unlock();
        }
      } else {
        responseObserver.onError(new StatusRuntimeException(Status.ABORTED));
      }
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void getOrCreateLabels(
      GetOrCreateLabelsRequest request,
      StreamObserver<GetOrCreateLabelsResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      Lock labelsLock = this.stripedLabelsLock.get(requestContext.getTenantId());
      if (labelsLock.tryLock(WAIT_TIME.getSeconds(), TimeUnit.SECONDS)) {
        try {
          final Map<String, Label> existingLabelsMap = getLabelsMap(requestContext);
          List<Label> newLabels =
              request.getRequestsList().stream()
                  .filter(
                      labelRequest ->
                          !existingLabelsMap.containsKey(labelRequest.getData().getKey()))
                  .map(this::buildLabelFromRequest)
                  .collect(Collectors.toList());
          Map<String, Label> createdLabelsMap;
          if (!newLabels.isEmpty()) {
            createdLabelsMap =
                labelStore.upsertObjects(requestContext, newLabels).stream()
                    .map(org.hypertrace.config.objectstore.ConfigObject::getData)
                    .collect(
                        Collectors.toUnmodifiableMap(
                            label -> label.getData().getKey(), identity()));
          } else {
            createdLabelsMap = Collections.emptyMap();
          }
          final Map<String, Label> allLabelsMap = new HashMap<>();
          allLabelsMap.putAll(existingLabelsMap);
          allLabelsMap.putAll(createdLabelsMap);
          List<Label> allLabels =
              request.getRequestsList().stream()
                  .map(GetOrCreateLabelsRequest.LabelRequest::getData)
                  .map(data -> allLabelsMap.get(data.getKey()))
                  .collect(Collectors.toList());
          responseObserver.onNext(
              GetOrCreateLabelsResponse.newBuilder().addAllLabels(allLabels).build());
          responseObserver.onCompleted();
        } finally {
          labelsLock.unlock();
        }
      } else {
        responseObserver.onError(new StatusRuntimeException(Status.ABORTED));
      }
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  private Label buildLabelFromRequest(GetOrCreateLabelsRequest.LabelRequest request) {
    LabelData labelData = request.getData();
    Label.Builder labelBuilder =
        Label.newBuilder().setId(UUID.randomUUID().toString()).setData(labelData);
    if (request.hasCreatedByApplicationRuleId()) {
      labelBuilder.setCreatedByApplicationRuleId(request.getCreatedByApplicationRuleId());
    }
    return labelBuilder.build();
  }

  @Override
  public void getLabel(GetLabelRequest request, StreamObserver<GetLabelResponse> responseObserver) {
    RequestContext requestContext = RequestContext.CURRENT.get();
    String labelId = request.getId();
    try {
      Label label;
      if (systemLabelsIdLabelMap.containsKey(labelId)) {
        label = systemLabelsIdLabelMap.get(labelId);
      } else {
        label =
            labelStore
                .getData(requestContext, labelId)
                .orElseThrow(Status.NOT_FOUND::asRuntimeException);
      }
      responseObserver.onNext(GetLabelResponse.newBuilder().setLabel(label).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void getLabels(
      GetLabelsRequest request, StreamObserver<GetLabelsResponse> responseObserver) {
    RequestContext requestContext = RequestContext.CURRENT.get();
    List<Label> allLabels = new ArrayList<>(systemLabels);
    List<Label> tenantLabels =
        labelStore.getAllObjects(requestContext).stream()
            .map(ContextualConfigObject::getData)
            .collect(Collectors.toUnmodifiableList());
    allLabels.addAll(tenantLabels);
    responseObserver.onNext(GetLabelsResponse.newBuilder().addAllLabels(allLabels).build());
    responseObserver.onCompleted();
  }

  @Override
  public void updateLabel(
      UpdateLabelRequest request, StreamObserver<UpdateLabelResponse> responseObserver) {
    try {
      LabelData updateLabelData = request.getData();
      RequestContext requestContext = RequestContext.CURRENT.get();
      Lock labelsLock = this.stripedLabelsLock.get(requestContext.getTenantId());
      if (labelsLock.tryLock(WAIT_TIME.getSeconds(), TimeUnit.SECONDS)) {
        try {
          if (systemLabelsIdLabelMap.containsKey(request.getId())) {
            // Updating a system label will error
            responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT));
            return;
          }
          if (isDuplicateKey(requestContext, request.getId(), updateLabelData.getKey())) {
            responseObserver.onError(new StatusRuntimeException(Status.ALREADY_EXISTS));
            return;
          }
          Label oldLabel =
              labelStore
                  .getData(requestContext, request.getId())
                  .orElseThrow(Status.NOT_FOUND::asRuntimeException);
          Label updateLabel = oldLabel.toBuilder().setData(updateLabelData).build();
          Label updateLabelInRes = labelStore.upsertObject(requestContext, updateLabel).getData();
          responseObserver.onNext(
              UpdateLabelResponse.newBuilder().setLabel(updateLabelInRes).build());
          responseObserver.onCompleted();
        } finally {
          labelsLock.unlock();
        }
      } else {
        responseObserver.onError(new StatusRuntimeException(Status.ABORTED));
      }
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void deleteLabel(
      DeleteLabelRequest request, StreamObserver<DeleteLabelResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      Lock labelsLock = this.stripedLabelsLock.get(requestContext.getTenantId());
      labelsLock.lock();
      if (labelsLock.tryLock(WAIT_TIME.getSeconds(), TimeUnit.SECONDS)) {
        try {
          String labelId = request.getId();
          if (systemLabelsIdLabelMap.containsKey(labelId)) {
            // Deleting a system label
            responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT));
            return;
          }
          labelStore
              .deleteObject(requestContext, labelId)
              .orElseThrow(Status.NOT_FOUND::asRuntimeException);
          responseObserver.onNext(DeleteLabelResponse.newBuilder().build());
          responseObserver.onCompleted();
        } finally {
          labelsLock.unlock();
        }
      } else {
        responseObserver.onError(new StatusRuntimeException(Status.ABORTED));
      }
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  private boolean isDuplicateKey(RequestContext requestContext, String id, String key) {
    return Optional.ofNullable(getLabelsMap(requestContext).get(key)).stream()
        .anyMatch(label -> !label.getId().equals(id));
  }

  private boolean isDuplicateKey(RequestContext requestContext, String key) {
    return getLabelsMap(requestContext).containsKey(key);
  }

  private Map<String, Label> getLabelsMap(RequestContext requestContext) {
    Map<String, Label> existingLabelsMap = new HashMap<>();
    existingLabelsMap.putAll(systemLabelsKeyLabelMap);
    labelStore.getAllObjects(requestContext).stream()
        .map(ContextualConfigObject::getData)
        .forEach(label -> existingLabelsMap.put(label.getData().getKey(), label));
    return Collections.unmodifiableMap(existingLabelsMap);
  }
}
