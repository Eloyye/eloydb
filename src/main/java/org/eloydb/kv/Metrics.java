package org.eloydb.kv;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Minimal in-process metrics registry for M1 debugging. */
public final class Metrics {
  private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Supplier<Long>> gauges = new ConcurrentHashMap<>();

  public void increment(String name) {
    add(name, 1);
  }

  public void add(String name, long delta) {
    counters.computeIfAbsent(name, ignored -> new AtomicLong()).addAndGet(delta);
  }

  public void gauge(String name, Supplier<Long> supplier) {
    gauges.put(name, supplier);
  }

  public Map<String, Long> snapshot() {
    var result = new java.util.TreeMap<String, Long>();
    counters.forEach((name, value) -> result.put(name, value.get()));
    gauges.forEach((name, value) -> result.put(name, value.get()));
    return Map.copyOf(result);
  }
}
