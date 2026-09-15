# CI/CD and release

## Why the workflow is documented, not installed

This repository is a **reading sample, not a buildable app** — no Gradle wrapper, no
manifest, no application module. Installing a workflow here would produce a permanently
failing build and a red badge that says nothing true about my engineering.

So the real pipeline is reproduced and annotated below instead.

## Continuous integration

GitHub Actions, on every push and pull request to `develop`:

```yaml
name: Android CI

on:
  push:
    branches: [ "develop" ]
  pull_request:
    branches: [ "develop" ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4
        with:
          # JUCE and aubio are git submodules — without this the native build
          # fails late, during CMake configure, with a confusing missing-header error
          # rather than an obvious "you forgot the submodules".
          submodules: recursive

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Install CMake
        run: sudo apt-get install -y cmake

      # Third-party native dependencies (Rubber Band, FFTW) are expensive to build and
      # change rarely. Caching them per-ABI is the single biggest win in this pipeline;
      # without it native compilation dominates every run.
      - name: Cache prebuilt native dependencies
        uses: actions/cache@v4
        with:
          path: .thirdparty-cache
          key: thirdparty-${{ runner.os }}-${{ hashFiles('**/build_android.sh') }}

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Unit tests
        run: ./gradlew testDebugUnitTest

      - name: Assemble
        run: ./gradlew assembleDebug
```

### What I changed and why

The pipeline did not start in good shape. Three fixes that mattered:

**Submodules were not checked out.** The native build failed during CMake configure with a
missing-header error that read like a local environment problem. `submodules: recursive`
on checkout fixed it, and the failure mode is what made it worth documenting — a confusing
error costs more time than an obvious one.

**A `rm -rf ~/.gradle/caches` step existed** as a workaround for a stale-dependency problem.
It defeated the Gradle cache entirely and added minutes to every run. Removing it meant
fixing the underlying version pinning instead of papering over it.

**Native dependencies were rebuilt every run.** Now cached per-ABI, keyed on the build
scripts so the cache invalidates when it should.

## Release

- **Signed AAB.** Keystore credentials come from CI secrets and the local environment — never
  from a file in the repository. (More on this below; it is the one thing I would change
  about how this project started.)
- **`debugSymbolLevel 'full'`** so native crashes symbolicate in Play Vitals. Without it a
  native stack trace from a user's device is unactionable.
- **Staged rollout**, monitored against Vitals crash-rate and ANR thresholds before widening.
- **Firebase Remote Config** gates new features independently of the rollout, so a problem
  feature can be disabled without shipping a new build.

## Branching

Branch per issue (`123-short-description`), PR into `develop`, with issue and PR templates
in the repository. `master` is the release branch.

## A note on secrets in CI

Signing credentials belong in CI secrets and a local untracked file, injected at build time.

I will be direct about this, because it is the kind of thing worth being direct about: an
audit of the private repository — the one that produced this showcase — found that
`gradle.properties`, **with keystore passwords in it, had been committed**, and that a
`secrets.xml` containing a live payment API key had been committed in 2023 and was still
present in history. Both were found, both were remediated, and the keys were rotated.

The lesson is not "use a secret manager", which everyone already knows. It is that
`.gitignore` does not untrack a file that is already committed, and that a secret which
enters history stays there until it is rotated — deleting the file achieves nothing. A
pre-commit secret scanner would have caught both on day one, and now runs.
