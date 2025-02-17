package org.hypertrace.config.service;

import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.hypertrace.config.service.v1.ContextSpecificConfig;

/** This class contains utility methods. */
@Slf4j
public class ConfigServiceUtils {

  private static final Value EMPTY_VALUE =
      Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();

  private ConfigServiceUtils() {
    // to prevent instantiation
  }

  /**
   * Deep merge the specified {@link Value} configs with overridingConfig taking precedence over
   * defaultConfig for the same keys.
   *
   * @param defaultConfig
   * @param overridingConfig
   * @return the resulting config obtained after merging defaultConfig and overridingConfig
   */
  public static Value merge(Value defaultConfig, Value overridingConfig) {
    if (isNull(defaultConfig)) {
      return overridingConfig;
    } else if (isNull(overridingConfig)) {
      return defaultConfig;
    }

    // Only if both - defaultConfig and overridingConfig are of kind Struct(Map), then merge
    // the common fields. Otherwise, just return the overridingConfig
    if (defaultConfig.getKindCase() == Value.KindCase.STRUCT_VALUE
        && overridingConfig.getKindCase() == Value.KindCase.STRUCT_VALUE) {
      Map<String, Value> defaultConfigMap = defaultConfig.getStructValue().getFieldsMap();
      Map<String, Value> overridingConfigMap = overridingConfig.getStructValue().getFieldsMap();

      Map<String, Value> resultConfigMap = new LinkedHashMap<>(defaultConfigMap);
      for (Map.Entry<String, Value> entry : overridingConfigMap.entrySet()) {
        resultConfigMap.put(
            entry.getKey(), merge(defaultConfigMap.get(entry.getKey()), entry.getValue()));
      }
      Struct struct = Struct.newBuilder().putAllFields(resultConfigMap).build();
      return Value.newBuilder().setStructValue(struct).build();
    } else {
      return overridingConfig;
    }
  }

  public static boolean isNull(Value value) {
    if (value == null) {
      log.error(
          "NULL Value encountered. This is unexpected and indicates a BUG in code.",
          new RuntimeException());
      return true;
    }
    return value.getKindCase() == Value.KindCase.NULL_VALUE;
  }

  public static Optional<Value> filterNull(Value value) {
    return isNull(value) ? Optional.empty() : Optional.of(value);
  }

  public static Value emptyValue() {
    return EMPTY_VALUE;
  }

  public static ContextSpecificConfig emptyConfig(String context) {
    return ContextSpecificConfig.newBuilder()
        .setConfig(EMPTY_VALUE)
        .setContext(context)
        .setCreationTimestamp(0)
        .setUpdateTimestamp(0)
        .build();
  }
}
