# Changelog

All notable changes to this SDK. Versions follow [semver](https://semver.org/);
while the version is 0.x, a minor release may contain breaking changes.

## 0.1.0 (unreleased)

- First release: generated models and operations for the GuardianO3 `/v1` API
  (`guardiano3-sdk`), and a hand-written core (`guardiano3-sdk-core`) with an
  OkHttp interceptor for authentication, idempotency keys and retries with
  jitter, typed errors, cursor pagination and live sessions.
- Requires Java 11+. Defaults to the production API, `https://api.guardiano3.com`.
