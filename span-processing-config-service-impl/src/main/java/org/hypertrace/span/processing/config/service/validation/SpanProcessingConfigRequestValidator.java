package org.hypertrace.span.processing.config.service.validation;

import static org.hypertrace.config.validation.GrpcValidatorUtils.printMessage;
import static org.hypertrace.config.validation.GrpcValidatorUtils.validateNonDefaultPresenceOrThrow;
import static org.hypertrace.config.validation.GrpcValidatorUtils.validateRequestContextOrThrow;

import io.grpc.Status;
import org.hypertrace.core.grpcutils.context.RequestContext;
import org.hypertrace.span.processing.config.service.v1.ApiNamingRuleConfig;
import org.hypertrace.span.processing.config.service.v1.ApiNamingRuleInfo;
import org.hypertrace.span.processing.config.service.v1.CreateApiNamingRuleRequest;
import org.hypertrace.span.processing.config.service.v1.CreateExcludeSpanRuleRequest;
import org.hypertrace.span.processing.config.service.v1.DeleteApiNamingRuleRequest;
import org.hypertrace.span.processing.config.service.v1.DeleteExcludeSpanRuleRequest;
import org.hypertrace.span.processing.config.service.v1.ExcludeSpanRuleInfo;
import org.hypertrace.span.processing.config.service.v1.GetAllApiNamingRulesRequest;
import org.hypertrace.span.processing.config.service.v1.GetAllExcludeSpanRulesRequest;
import org.hypertrace.span.processing.config.service.v1.SegmentMatchingBasedConfig;
import org.hypertrace.span.processing.config.service.v1.SpanFilter;
import org.hypertrace.span.processing.config.service.v1.UpdateApiNamingRule;
import org.hypertrace.span.processing.config.service.v1.UpdateApiNamingRuleRequest;
import org.hypertrace.span.processing.config.service.v1.UpdateExcludeSpanRule;
import org.hypertrace.span.processing.config.service.v1.UpdateExcludeSpanRuleRequest;

public class SpanProcessingConfigRequestValidator {

  public void validateOrThrow(
      RequestContext requestContext, GetAllExcludeSpanRulesRequest request) {
    validateRequestContextOrThrow(requestContext);
  }

  public void validateOrThrow(RequestContext requestContext, CreateExcludeSpanRuleRequest request) {
    validateRequestContextOrThrow(requestContext);
    this.validateData(request.getRuleInfo());
  }

  public void validateOrThrow(RequestContext requestContext, UpdateExcludeSpanRuleRequest request) {
    validateRequestContextOrThrow(requestContext);
    this.validateUpdateRule(request.getRule());
  }

  public void validateOrThrow(RequestContext requestContext, DeleteExcludeSpanRuleRequest request) {
    validateRequestContextOrThrow(requestContext);
    validateNonDefaultPresenceOrThrow(request, DeleteExcludeSpanRuleRequest.ID_FIELD_NUMBER);
  }

  private void validateData(ExcludeSpanRuleInfo excludeSpanRuleInfo) {
    validateNonDefaultPresenceOrThrow(excludeSpanRuleInfo, ExcludeSpanRuleInfo.NAME_FIELD_NUMBER);
    this.validateSpanFilter(excludeSpanRuleInfo.getFilter());
  }

  private void validateUpdateRule(UpdateExcludeSpanRule updateExcludeSpanRule) {
    validateNonDefaultPresenceOrThrow(updateExcludeSpanRule, UpdateExcludeSpanRule.ID_FIELD_NUMBER);
    validateNonDefaultPresenceOrThrow(
        updateExcludeSpanRule, UpdateExcludeSpanRule.NAME_FIELD_NUMBER);
    this.validateSpanFilter(updateExcludeSpanRule.getFilter());
  }

  public void validateOrThrow(RequestContext requestContext, GetAllApiNamingRulesRequest request) {
    validateRequestContextOrThrow(requestContext);
  }

  public void validateOrThrow(RequestContext requestContext, CreateApiNamingRuleRequest request) {
    validateRequestContextOrThrow(requestContext);
    this.validateData(request.getRuleInfo());
  }

  public void validateOrThrow(RequestContext requestContext, UpdateApiNamingRuleRequest request) {
    validateRequestContextOrThrow(requestContext);
    this.validateUpdateRule(request.getRule());
  }

  public void validateOrThrow(RequestContext requestContext, DeleteApiNamingRuleRequest request) {
    validateRequestContextOrThrow(requestContext);
    validateNonDefaultPresenceOrThrow(request, DeleteExcludeSpanRuleRequest.ID_FIELD_NUMBER);
  }

  private void validateData(ApiNamingRuleInfo apiNamingRuleInfo) {
    validateNonDefaultPresenceOrThrow(apiNamingRuleInfo, ApiNamingRuleInfo.NAME_FIELD_NUMBER);
    this.validateConfig(apiNamingRuleInfo.getRuleConfig());
  }

  private void validateUpdateRule(UpdateApiNamingRule updateApiNamingRule) {
    validateNonDefaultPresenceOrThrow(updateApiNamingRule, UpdateApiNamingRule.ID_FIELD_NUMBER);
    validateNonDefaultPresenceOrThrow(updateApiNamingRule, UpdateApiNamingRule.NAME_FIELD_NUMBER);
    this.validateConfig(updateApiNamingRule.getRuleConfig());
  }

  private void validateConfig(ApiNamingRuleConfig ruleConfig) {
    switch (ruleConfig.getRuleConfigCase()) {
      case SEGMENT_MATCHING_BASED_CONFIG:
        SegmentMatchingBasedConfig segmentMatchingBasedConfig =
            ruleConfig.getSegmentMatchingBasedConfig();
        if (segmentMatchingBasedConfig.getRegexesCount() == 0
            || segmentMatchingBasedConfig.getRegexesCount()
                != segmentMatchingBasedConfig.getValuesCount()) {
          throw Status.INVALID_ARGUMENT
              .withDescription(
                  String.format(
                      "Invalid regex count or segment matching count : %s",
                      segmentMatchingBasedConfig))
              .asRuntimeException();
        }
        break;
      default:
        throw Status.INVALID_ARGUMENT
            .withDescription("Unexpected rule config case: " + printMessage(ruleConfig))
            .asRuntimeException();
    }
  }

  private void validateSpanFilter(SpanFilter filter) {
    switch (filter.getSpanFilterExpressionCase()) {
      case LOGICAL_SPAN_FILTER:
      case RELATIONAL_SPAN_FILTER:
        break;
      default:
        throw Status.INVALID_ARGUMENT
            .withDescription("Unexpected filter case: " + printMessage(filter))
            .asRuntimeException();
    }
  }
}
