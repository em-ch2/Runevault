package com.runevault;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class RequestQuotaLimiter {
 public enum Category { DASHBOARD_READ, TRANSACTION_CREATION, BRIDGE_AUTHORIZATION, BRIDGE_OTHER, ADMIN, UNAUTHENTICATED }
 private record Window(long startedAt, int count) {}

 private final RateLimitProperties limits;
 private final LongSupplier clock;
 private final Map<Category, Map<String, Window>> buckets = new EnumMap<>(Category.class);
 private long checks;

 @Autowired public RequestQuotaLimiter(RateLimitProperties limits) { this(limits, System::nanoTime); }
 RequestQuotaLimiter(RateLimitProperties limits, LongSupplier clock) {
  this.limits = limits;
  this.clock = clock;
  for (Category category : Category.values()) buckets.put(category, new HashMap<>());
 }

 // Returns 0 when admitted, or the seconds until this fixed window expires.
 public synchronized int retryAfter(Category category, String identity) {
  if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Quota identity is required");
  long now = clock.getAsLong();
  long duration = limits.windowSeconds() * 1_000_000_000L;
  Map<String, Window> entries = buckets.get(category);
  if (++checks % 64 == 0 || entries.size() >= limits.maxEntries())
   entries.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= duration);
  Window current = entries.get(identity);
  if (current == null || now - current.startedAt() >= duration) {
   if (current == null && entries.size() >= limits.maxEntries()) return limits.windowSeconds();
   entries.put(identity, new Window(now, 1));
   return 0;
  }
  if (current.count() >= limits.limit(category)) {
   long remaining = duration - (now - current.startedAt());
   return (int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
  }
  entries.put(identity, new Window(current.startedAt(), current.count() + 1));
  return 0;
 }
}
