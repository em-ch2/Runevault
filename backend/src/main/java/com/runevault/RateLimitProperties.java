package com.runevault;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "runevault.rate-limit")
public record RateLimitProperties(
 @Min(1) int windowSeconds,
 @Min(1) int maxEntries,
 @Min(1) int dashboardReads,
 @Min(1) int transactionCreation,
 @Min(1) int bridgeAuthorization,
 @Min(1) int bridgeOther,
 @Min(1) int adminOperations,
 @Min(1) int unauthenticated) {
 int limit(RequestQuotaLimiter.Category category) {
  return switch (category) {
   case DASHBOARD_READ -> dashboardReads;
   case TRANSACTION_CREATION -> transactionCreation;
   case BRIDGE_AUTHORIZATION -> bridgeAuthorization;
   case BRIDGE_OTHER -> bridgeOther;
   case ADMIN -> adminOperations;
   case UNAUTHENTICATED -> unauthenticated;
  };
 }
}
