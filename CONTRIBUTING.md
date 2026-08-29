# Contributing to ArkLlm

Thanks for your interest. ArkLlm is an Apache-2.0 project.

## Before you start

- Open an issue first for anything larger than a typo — it saves both of us time.
- Keep the host generic. This is a local-LLM *backend*, not a task-routing agent.
  Avoid adding product-specific workflows or hardcoded model assumptions.

## Development

- JDK 17, Android SDK 36 (minSdk 28), Gradle 9.3.1 wrapper (AGP 9.1.1).
- Build: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew test`
- A device E2E pass (bind + inference) is expected for service/contract changes —
  there is no mock-only gate.

## Logging

Every entry point, decision branch, and error must be traceable through logcat
alone (see the `XLog` shims in each module). If a bug has no log, that is a code
defect.

## License

By contributing you agree your work is licensed under the Apache License 2.0.
