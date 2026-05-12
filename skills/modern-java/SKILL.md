---
name: modern-java
description: Apply modern Java and JUnit best practices when writing, reviewing, or refactoring Java code. Use this skill whenever the user writes Java, asks for a Java code review, modernizes legacy Java, designs Java APIs, or writes JVM tests, even when they do not explicitly say "best practices." Trigger on records, sealed types, pattern matching, virtual threads, HttpClient, JUnit, Mockito, AssertJ, or any `.java` file content.
---

# Modern Java Best Practices

Apply modern Java and JUnit practices for production code, libraries, and JVM tests. Prefer stable language and library features.

## Design

- Default concrete classes to `final`. Open deliberately.
- Prefer `sealed` over `abstract` for closed type families.
- Prefer composition over inheritance. Inherit only for sealed algebraic data types, language-required cases such as exceptions, or framework hooks designed for extension.
- Replace inheritance with constructor delegation, functional-interface strategies, or sealed interface plus record variants.
- Make illegal states unrepresentable through types and constructor validation.

## Records And Sealed Types

Use records for value objects, DTOs, and events. Validate in compact constructors. Wrap mutable inputs with `List.copyOf`, `Set.copyOf`, or `Map.copyOf`.

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch");
        }
        return new Money(amount.add(other.amount), currency);
    }
}
```

Use sealed interfaces for closed sets. Combine them with switch expressions for compile-time exhaustiveness.

```java
public sealed interface PaymentResult {
    record Success(TransactionId id) implements PaymentResult {}
    record Declined(String reason) implements PaymentResult {}
    record Error(Throwable cause) implements PaymentResult {}
}
```

## Pattern Matching And Switch

Use pattern `instanceof` and switch expressions to replace cast-heavy code and long type-dispatch chains.

```java
if (obj instanceof Order order && order.isPaid()) {
    ship(order);
}
```

Use arrow-form switch and handle `null` deliberately when inputs may be nullable.

```java
return switch (input) {
    case null -> defaultValue;
    case String s -> s.trim();
    default -> input.toString();
};
```

## Null Safety

- Adopt JSpecify: `@NullMarked` at package level and `@Nullable` only where null is part of the contract.
- Return `Optional<T>` for absent return values. Do not use `Optional` as a field, parameter, or collection element.
- Return empty collections, never `null`.
- Use `Objects.requireNonNull(x, "x")` in constructors and boundary methods.

## Immutability

- Prefer `final` fields.
- Use `List.copyOf`, `Set.copyOf`, and `Map.copyOf` for defensive input copies.
- Use `List.of`, `Set.of`, and `Map.of` for literals.
- Prefer `Stream.toList()` when an unmodifiable result is desired.
- Never expose mutable internal state from accessors.

## Collections And Streams

Use sequenced collection APIs for first, last, and reversed access when the target Java version supports them.

```java
var first = list.getFirst();
var last = list.getLast();
var reversed = list.reversed();
```

Use stream gatherers for windowed or stateful stream operations when available in the target runtime.

```java
List<List<Integer>> windows = Stream.of(1, 2, 3, 4, 5)
    .gather(Gatherers.windowFixed(2))
    .toList();
```

- Prefer `.toList()` over `collect(Collectors.toList())` when an unmodifiable list is acceptable.
- Use `Collectors.toUnmodifiableMap` and `Collectors.toUnmodifiableSet` for immutable terminal collection.
- Use `mapMulti` when it is clearer or cheaper than `flatMap`.
- Avoid parallel streams without measurement.
- Avoid side effects in `map` and `filter`.

## Concurrency

Use virtual threads for I/O-bound work.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = urls.stream()
        .map(url -> executor.submit(() -> fetch(url)))
        .toList();
}
```

- Do not pool virtual threads.
- Use `ReentrantLock` rather than `synchronized` around blocking I/O on virtual threads.
- Use platform threads or `ForkJoinPool` for CPU-bound work.
- Prefer scoped values over `ThreadLocal` for immutable request-scoped context when available in the target Java version.

```java
private static final ScopedValue<UserId> CURRENT_USER = ScopedValue.newInstance();

ScopedValue.where(CURRENT_USER, userId).run(() -> handle(request));
```

## Error Handling

- Throw the most specific exception with useful context: `throw new ConfigLoadException("Cannot read " + path, cause);`
- Use try-with-resources for every `AutoCloseable`.
- Catch `Exception` only at boundaries such as HTTP handlers, scheduled tasks, and message consumers.
- Never catch `Throwable`. Never catch and ignore.
- Model expected failures as sealed result types, not exceptions.

## I/O And HTTP

```java
HttpClient client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .connectTimeout(Duration.ofSeconds(5))
    .build();

HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com"))
    .timeout(Duration.ofSeconds(10))
    .GET()
    .build();
```

- Reuse a single `HttpClient`.
- Always set timeouts.
- Use NIO.2 APIs such as `Path` and `Files`; avoid `java.io.File` in new code.
- Use `path.resolve(...)`, never string concatenation, for filesystem paths.

## Performance

- Default to G1 unless measurements justify another collector.
- Consider ZGC for latency-sensitive services after measuring.
- Profile with JFR before optimizing. Benchmark hot code with JMH.
- Prefer algorithmic improvements and allocation reduction over micro-optimizations.
- Do not add caching without clear ownership, invalidation, and memory bounds.

## Security

- Use `SecureRandom`, never `Random` or `Math.random()`, for security-sensitive randomness.
- Never log credentials, tokens, secrets, or PII.
- Never disable TLS validation.
- Avoid reflective mutation of `final` fields.
- Keep dependencies current and scan for known vulnerabilities.

## Modules And Packaging

- Use `--release N`, not separate `--source` and `--target`, for cross-compilation.
- Use `jlink` when a minimal shippable runtime image is useful.
- Use JPMS for libraries; it is optional but encouraged for applications.
- Treat repeated `--add-opens` or `--add-exports` as a design smell, not a normal default.

## Logging And Observability

- Use the SLF4J API with Logback or Log4j 2 implementation. Avoid direct `java.util.logging` usage in application code.
- Use parameterized messages: `log.info("user {} ordered {}", id, sku);`
- Prefer structured JSON logs in production.
- Use OpenTelemetry for traces, metrics, and logs.
- Run JFR continuously in production where operationally feasible.
- Never use `e.printStackTrace()`.

## API Design

- Return interface types such as `List`, `Map`, and `Collection`.
- Validate at boundaries; trust internal callers after validation.
- Prefer static factories such as `Money.usd(10)` over public constructors for value types.
- Avoid boolean parameters in public APIs; use enums, named methods, or builder methods.
- Return collections, not arrays, unless an API boundary requires arrays.

## Documentation

Use Markdown Javadoc when the target Java version supports it.

```java
/// Returns the discounted price.
///
/// - Discounts apply **before** tax.
/// - See [pricing rules](https://example.com).
Money discounted(Order order);
```

Document contracts, nullability, threading, exceptions, and side effects. Do not document obvious implementation details.

## Tooling

- Use one build tool team-wide: Maven or Gradle.
- Pin a JDK toolchain.
- CI should include formatting, compilation, tests, dependency scanning, and static analysis.
- Consider Error Prone, NullAway, SpotBugs, and OWASP Dependency-Check where appropriate.
- Format with Spotless plus google-java-format or palantir-java-format.

## Testing With JUnit

Use current JUnit Jupiter conventions for unit and integration tests. Check current JUnit documentation before relying on version-specific migration details.

### Setup

```kotlin
testImplementation(platform("org.junit:junit-bom:6.0.3"))
testImplementation("org.junit.jupiter:junit-jupiter")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

### Naming And Organization

- Use class suffix `Test` for unit tests and `IT` for integration tests.
- Use method names that describe behavior, such as `debits_account_when_payment_succeeds`.
- Use `@DisplayName` for human-readable reports when it improves output.
- Use `@Nested` for grouping related behavior.

### Assertions

- Prefer AssertJ for fluent domain assertions.
- Use `assertAll` to surface multiple failures at once.

```java
assertAll("user",
    () -> assertEquals("Ada", user.name()),
    () -> assertNotNull(user.email()));
```

### Parameterized Tests

Use `@CsvSource`, `@ValueSource`, `@MethodSource`, and related sources for focused input matrices.

```java
@ParameterizedTest(name = "[{index}] {0} + {1} = {2}")
@CsvSource({
    "1, 2, 3",
    "0, 0, 0",
    "-1, 1, 0"
})
void adds(int a, int b, int expected) {
    assertEquals(expected, calc.add(a, b));
}
```

Use class-level parameterization when every test in a class should run for each input.

```java
@ParameterizedClass
@ValueSource(strings = {"v1", "v2"})
class ApiContractTest {
    @Parameter
    String version;

    @Test
    void responds_200_on_health() {
        // uses version
    }

    @Test
    void rejects_unknown_endpoint() {
        // uses version
    }
}
```

### Lifecycle

- Use `@BeforeEach` and `@AfterEach` for per-test setup.
- Avoid `@BeforeAll` unless setup is expensive and read-only.
- Use `@TempDir Path tmp` for filesystem tests.
- Use `@Timeout` to fail hangs.

### Cancellation And Fail Fast

- Use supported fail-fast runner or build-tool options when quick feedback matters.
- Use JUnit's cancellation APIs only for extensions or listeners that need cooperative cancellation.

### Test Doubles

- Prefer hand-written fakes for code you own.
- Use Mockito for framework or third-party collaborators.
- Do not mock value types, records, or types you fully control.
- Do not mock the system under test.
- Avoid over-verification that couples tests to implementation details.

### What To Test

- Test behavior, not implementation.
- Test public APIs in unit tests.
- Keep a test pyramid: many unit tests, fewer integration tests, very few end-to-end tests.
- Cover edge cases: empty, null, max, min, boundary, and concurrent cases.
- Use property-based testing, such as jqwik, for rich input domains.
- Do not test simple getters, setters, or framework behavior.

### Concurrency Tests

```java
await().atMost(2, SECONDS).until(() -> queue.size() == 5);
```

- Inject `Clock` instead of asserting on wall time.
- Do not use `Thread.sleep` in tests.

### JUnit Migration Watch List

- Prefer Jupiter APIs over legacy JUnit 4 APIs.
- Avoid adding new Vintage-engine tests.
- Replace JUnit 4 `@Before`, `@After`, and `@RunWith` with `@BeforeEach`, `@AfterEach`, and `@ExtendWith`.
- Use current `ExtensionContext.Store` APIs when updating extensions.
- Recheck CSV parsing and locale conversion behavior when migrating parameterized tests.

## Quick Replacement Cheatsheet

| Replace                                               | With                                              |
| ----------------------------------------------------- | ------------------------------------------------- |
| Boilerplate value class                               | `record`                                          |
| Manual `instanceof` plus cast                         | Pattern `instanceof`                              |
| Long type-dispatch `if`/`else` chain                  | Switch expression with patterns                   |
| `synchronized` around blocking I/O on virtual threads | `ReentrantLock`                                   |
| `ThreadLocal<T>` for immutable request context        | Scoped value where supported                      |
| `Collectors.toList()`                                 | `.toList()` or an explicit unmodifiable collector |
| `new Random()` for tokens                             | `SecureRandom`                                    |
| `java.util.Date` / `Calendar`                         | `java.time.*`                                     |
| `extends BaseService` for code reuse                  | Inject collaborator                               |
| `Optional<T>` field or parameter                      | `@Nullable T` or sealed result type               |
| JUnit 4 `@Before` / `@After` / `@RunWith`             | `@BeforeEach` / `@AfterEach` / `@ExtendWith`      |

## Completion Criteria

Before finishing Java work, confirm:

- Stable Java APIs were preferred.
- Data models, nullability, immutability, and exceptions make illegal states hard to represent.
- Tests use current JUnit Jupiter conventions.
- Relevant formatting, compilation, tests, and static checks ran, or blockers were reported explicitly.
