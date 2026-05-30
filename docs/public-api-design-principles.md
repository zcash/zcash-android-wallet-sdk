# Android SDK Public API Design Principles

Status: draft

This document defines design principles for public Android SDK APIs. It is a
working reference for contributors who are adding, changing, or reviewing SDK
features that wallet applications will consume.

The SDK is a product. Its public APIs should be understandable and safe for
wallet teams that do not know this repository's internals, Rust/JNI boundaries,
database layout, or first-party app code.

Public SDK APIs should encapsulate the complex Zcash and wallet mechanics that
apps should not need to reimplement.

Use this document before implementation starts, while reviewing public API
pull requests, and when deciding whether feature logic belongs in the SDK, an
app, Rust, JNI, storage, or transport code.

Feature-specific design documents must apply these principles to concrete
types, methods, state diagrams, migration plans, and tests.

## Product Stance

The SDK must stand on its own.

An app developer should be able to discover a public capability, understand the
state machine or workflow it represents, and integrate it without reading:

- first-party wallet app source;
- SDK internals;
- Rust source;
- JNI carrier classes;
- database schemas;
- review threads or team chat history.

"Works for one app" is not enough. A public SDK API is ready only when it is
usable by arbitrary third-party wallet applications.

## Public Boundary

Public SDK consumers should depend on `sdk-lib` public types.

They should not depend on:

- `cash.z.ecc.android.sdk.internal.*`;
- `backend-lib`;
- `Jni*` carrier classes;
- Rust backend wrapper classes;
- raw database paths;
- opaque native handles;
- generated wire types unless a feature is explicitly designed around them;
- first-party app model classes.

The SDK may use those implementation details internally. The public layer maps
them into SDK-domain models before values cross the consumer boundary.

## SDK Surface Ownership

Place public API in the SDK surface that matches its stability, audience, and
consumer opt-in model.

This document is written from the Android SDK, so it names Android modules where
they exist today. The same consumer-facing expectations should apply to the iOS
SDK using Swift-native packaging and opt-in mechanisms.

In the Android SDK:

- `sdk-lib` is the main stable public SDK surface for wallet applications.
- `backend-lib` is native/JNI/backend implementation detail and should not be a
  direct dependency of wallet apps.
- `lightwallet-client-lib` exposes lower-level lightwalletd networking APIs.
  These APIs can be useful extension points, but wallet-feature APIs in
  `sdk-lib` should not leak raw network or unsafe representations when they can
  expose validated SDK-domain models instead.
- `sdk-incubator-lib` is the existing Android incubator surface for public but
  unstable APIs. It may be used as a compatibility mechanism, but should not be
  treated as the only long-term pattern for experimental SDK features.
- Demo, benchmark, and test modules are not public SDK feature surfaces.

Incubation and feature selection are separate concerns.

Incubation describes API lifecycle and stability: an API is public enough to try,
but its shape may change. Feature selection describes explicit consumer opt-in:
a wallet chooses whether a capability is included or enabled.

Incubating APIs should require explicit consumer opt-in. Prefer platform-native
feature or API opt-in mechanisms when they provide a clear consumer experience
and preserve Android/iOS parity. Android may use Kotlin opt-in annotations,
Gradle module/artifact boundaries, or Gradle feature mechanisms. The iOS SDK
should provide an equivalent consumer-facing opt-in using Swift-native
mechanisms, such as Swift Package products or targets today, and SwiftPM traits
if the SDK later adopts a compatible Swift tools version.

Feature selection can also apply to stable capabilities, such as optional
dependencies, binary-size-sensitive functionality, platform-specific support,
privacy-sensitive features, or functionality not every wallet needs.

Incubation status is not a reason to expose internals. Experimental APIs still
need clear ownership, typed models, documentation, and a migration path toward a
stable public surface.

## Representation Layers

Data should move upward through representation layers before it reaches app
code.

Common representation layers are:

- primitive values used by JNI, storage, or wire code;
- JNI carrier objects that cross the native boundary;
- generated network or protobuf models;
- unsafe network models whose suffix indicates they are not fully validated;
- internal SDK models used for orchestration and persistence;
- public SDK-domain models exposed to wallet applications.

Lower-layer representations may be appropriate inside adapters, repositories,
or backend wrappers. Public feature APIs should expose the highest meaningful
SDK-domain representation and keep lower-layer conversion internal.

## Capability-Oriented APIs

Expose features as narrow capabilities from established SDK entry points.

A caller should receive an object that gives access to one feature area and no
unrelated internals. For example, `Synchronizer` may expose a capability for a
wallet-scoped feature, while that capability may expose account-scoped objects
for operations that depend on `AccountUuid`.

Capabilities are also security boundaries. IO, storage, network transport,
hardware signing, and secret access should be represented by explicit capability
interfaces instead of hidden global dependencies or ad hoc callbacks. A reviewer
should be able to inspect the public capability and the SDK-internal function or
constructor signatures that implement it, and understand what authority the code
has: what it can read, what it can write, what it can send over the network, and
what secrets or signing authority it can request.

Good capability APIs:

- make the starting point obvious;
- make authority visible in function and constructor signatures;
- keep IO behind explicit interfaces owned by the public capability or passed to
  the SDK-internal logic that needs them;
- keep network transport, general storage, signing, and secret access as separate
  authorities;
- avoid giving transport or general storage capabilities access to spending keys
  or signing authority;
- avoid factory methods that imply a new independent backend when the feature is
  actually wallet-scoped;
- bind wallet and account context once instead of passing raw IDs repeatedly;
- hide lifecycle steps that consumers cannot use safely;
- expose only operations the caller is meant to perform.

Avoid APIs where callers manually assemble backend dependencies, open private
databases, pass around handles, or call implementation steps in an undocumented
order. These APIs obscure the authority being granted to the code and make it
harder to audit which operations can perform IO, access secrets, or cross trust
boundaries.

## State Machines And Workflows

Public APIs should model user-facing workflows, not only backend calls.

When a feature has meaningful states, recovery behavior, retries, or externally
visible phases, the API design must describe that state machine before
implementation is considered reviewable.

The design should answer:

- what states can exist;
- what transitions are allowed;
- what input the app must provide at each transition;
- what output the app should render or persist;
- which transitions are owned by the SDK;
- how interrupted operations resume;
- how stale, conflicting, or already-completed state is handled.

Typed wrappers around low-level calls are useful internally, but they are not a
substitute for a public workflow API.

## Business Logic Belongs Below The App

The app should primarily be a UI and policy layer.

SDK-owned logic includes:

- wallet and account data lookup;
- protocol validation;
- chain-height and sync gating;
- state transition rules;
- durable workflow state;
- retry and recovery decisions;
- construction and validation of protocol artifacts;
- mapping lower-layer failures into documented SDK failures.

App-owned logic includes:

- presentation;
- user consent and authentication prompts;
- app product policy;
- custom networking policy when a feature allows caller-supplied transport;
- hardware-device UI and data transfer;
- display of progress, errors, and results.

If a feature requires app-owned business logic, the design must explain why the
SDK cannot own it and how third-party apps can implement it safely.

## Source Of Truth Across Layers

Place rules in the layer that owns them.

Protocol and wallet rules should live as close as practical to the protocol and
wallet implementation, often in Rust. Kotlin should not duplicate constants,
byte-size rules, state transition rules, or recovery semantics when the Rust
layer can expose them.

When a rule must be mirrored in Kotlin:

- document the upstream source of truth;
- keep mirrored constants in a feature-specific location;
- add tests or review checks that catch drift;
- avoid scattering protocol constants through unrelated SDK code.

Public Kotlin APIs should remain independent of the current Rust/JNI plumbing
shape. A backend refactor should not force unnecessary consumer API churn.

## Strong Domain Types

A raw `String`, `Int`, `Long`, or `ByteArray` is not a domain model.

Public APIs should use types that encode semantic meaning. Existing SDK types
such as `AccountUuid`, `BlockHeight`, `Pczt`, `TransactionId`,
`UnifiedSpendingKey`, `Zatoshi`, and `ZcashNetwork` should be reused when they
represent the concept accurately.

Create feature-specific types when a value has distinct semantics, validation,
or lifecycle rules. Examples include identifiers, indexes, roots, hashes,
encoded protocol artifacts, external server endpoints, signatures, and persisted
workflow state.

Raw primitives are acceptable only when:

- the value is genuinely unstructured from the SDK's perspective;
- the KDoc documents source, format, valid range, and lifecycle;
- the parameter name cannot be confused with another value of the same type;
- validation is performed before crossing into lower layers.

Byte-bearing types must document whether their contents are:

- secret;
- safe to log;
- safe to persist;
- safe to transmit;
- display-only;
- stable across SDK versions;
- protocol-encoded or SDK-internal.

Sensitive types must redact `toString()`.

## Request And Result Objects

Prefer request objects for operations with multiple domain inputs.

Request objects:

- make call sites self-documenting;
- avoid long parameter lists;
- allow additive evolution while an API is experimental;
- support sealed variants for materially different flows;
- prevent ambiguous overloads.

Result types should distinguish normal outcomes from exceptional failures. Use
sealed result models when callers are expected to branch on a condition, and use
typed exceptions for failures that should interrupt the workflow.

## Error Handling

Public APIs should not leak raw lower-layer failures.

JNI `RuntimeException`, Rust error strings, network implementation exceptions,
and database exceptions should be mapped at the SDK boundary into documented
SDK exceptions or typed result values.

A public feature design should state:

- which methods throw;
- which outcomes are returned as sealed results;
- which failures are retryable;
- which failures indicate invalid caller input;
- which failures indicate stale wallet or chain state;
- which failures indicate internal SDK/backend errors.

Consumers should not need to parse exception messages to make product decisions.

## Storage, Lifecycle, And Recovery

If the SDK owns the workflow, the SDK must own enough state to resume it.

Public APIs should avoid making apps manage:

- SDK database paths;
- raw storage handles;
- native resource handles;
- internal row identifiers;
- partially-complete workflow artifacts;
- duplicated recovery snapshots that compete with SDK storage.

When a feature has durable state, the design must define:

- where it is stored;
- how it is migrated;
- how it is scoped to wallet, network, and account;
- how it is cleared;
- how interrupted operations resume;
- which data is secret and how it should be protected.

If any state remains app-managed, document why and provide typed models that make
safe handling possible.

## Transport Boundaries

Network transport can be replaceable without moving workflow logic into the app.

The SDK may provide convenience clients. It may also allow callers to supply
transport implementations for custom networking, privacy routing, endpoint
policy, or authentication.

Caller-supplied transport should be modeled as a port:

- the SDK emits typed transport requests;
- the app or convenience client performs I/O;
- the SDK validates typed responses;
- the SDK advances and persists workflow state.

The app should not own protocol state transitions merely because it owns an HTTP
client.

## External Signing And Hardware Flows

External signing is part of the public workflow, not an escape hatch.

When a feature supports PCZT signing, hardware wallets, QR transfer, or another
external signing system, the SDK API should model the flow explicitly:

- SDK prepares the signing request;
- app transfers it to the external signer;
- app returns the signed artifact or signature;
- SDK validates it against expected state;
- SDK advances or rejects the workflow with typed outcomes.

The app may own device UI and transfer mechanics. It should not own protocol
phase rules, replay checks, or artifact validation.

## Experimental APIs

New public APIs can be experimental, but they still need good design.

Use an explicit opt-in annotation for public APIs whose shape is expected to
evolve. Experimental status should not be used to justify exposing internals,
undocumented raw values, or unsafe lifecycle requirements.

Experimental API designs should still define:

- intended consumer workflows;
- typed models;
- error behavior;
- persistence ownership;
- compatibility expectations;
- migration path toward stable API.

## Documentation Requirements

Documentation is part of the API.

Every substantial public feature should include:

- KDoc on all public types and methods;
- a high-level integration guide;
- a state machine or workflow diagram when states matter;
- examples for the primary consumer flows;
- error handling guidance;
- notes on sensitive data and persistence;
- notes on transport or external signing boundaries if applicable;
- a changelog entry written for SDK consumers.

The documentation should state what the app does, what the SDK does, and what
the lower layers own.

## Testing And Review

Public API work should include tests at the right layer.

At minimum, reviewers should look for:

- compile-time coverage of public signatures;
- model validation tests;
- redacted `toString()` tests for sensitive models;
- adapter tests proving public models do not leak internal carriers;
- workflow tests for expected transitions and recovery;
- compatibility tests when changing existing stable APIs;
- targeted searches for forbidden public imports or `Jni*` leakage.

The API should be rejected if:

- consumers must import internals;
- consumers must depend on first-party app code;
- public signatures expose unexplained primitives;
- public signatures expose raw JNI or backend types;
- consumers must manage private SDK databases or handles;
- consumers must copy SDK business logic to use the feature;
- consumers must read lower-layer source to know valid call order;
- sensitive data can be accidentally logged through default string rendering.

The API is on track when a small sample wallet can implement the feature by
reacting to SDK state, rendering UI, and supplying documented app-owned inputs,
without copying private app logic or SDK internals.
