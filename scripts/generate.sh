#!/usr/bin/env bash
# Regenerate the `generated` Maven module from openapi/v1.json.
#
# openapi/v1.json is a copy of the GuardianO3 API contract. It is updated by a
# pull request opened from the API repository whenever the contract changes;
# this script turns it into code. generated/ is never edited by hand: CI
# regenerates it and fails if the result differs from what is committed. The
# hand-written module, core/, is never touched.
#
# Runs the generator in Docker, so no JVM is needed.
#
# Usage: ./scripts/generate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="openapitools/openapi-generator-cli:v7.15.0"
# The parent pom's own <version>, the first one after its <artifactId>.
VERSION="$(sed -n '/<artifactId>guardiano3-sdk-parent<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p;}' "${ROOT}/pom.xml")"
REPO_URL="https://github.com/guardiano3/guardiano3-java"

MOUNT="$ROOT"
if command -v cygpath >/dev/null 2>&1; then
  # Git Bash on Windows: Docker wants a Windows path, and MSYS must not rewrite it.
  MOUNT="$(cygpath -m "$ROOT")"
  export MSYS_NO_PATHCONV=1
fi

rm -rf "${ROOT}/generated"
mkdir -p "${ROOT}/generated"

# Keep the library and its pom; drop the generator's CI files and its Gradle
# build. The Gradle wrapper ships a binary JAR that executes during a build;
# carrying an opaque executable for a build system this repository does not use
# is a supply-chain risk taken for nothing.
cat > "${ROOT}/generated/.openapi-generator-ignore" <<'IGNORE'
docs/**
test/**
tests/**
.github/**
.travis.yml
.gitlab-ci.yml
git_push.sh
.gitignore
gradlew
gradlew.bat
gradle/**
gradle.properties
build.gradle
settings.gradle
build.sbt
IGNORE

# hideGenerationTimestamp is load-bearing: without it every model carries
# `@Generated(date = "<now>")`, and the drift check would fail on every run.
#
# The packages are named rather than left at `org.openapitools.client`, so a
# customer whose application already carries another generated client does not
# get two `org.openapitools.client.ApiClient` classes on one classpath.
#
# The license/scm/developer options fill the pom metadata Maven Central
# requires. The parent options make the aggregator pom this module's parent, so
# the release profile there (signing, the Central upload) covers it too without
# editing a file the generator owns.
docker run --rm \
  -v "${MOUNT}:/work" \
  -u "$(id -u):$(id -g)" \
  "$IMAGE" generate \
    -i /work/openapi/v1.json \
    -g java \
    -o /work/generated \
    --skip-validate-spec \
    --additional-properties="groupId=com.guardiano3,artifactId=guardiano3-sdk,artifactVersion=${VERSION},library=okhttp-gson,hideGenerationTimestamp=true,invokerPackage=com.guardiano3.generated,apiPackage=com.guardiano3.generated.api,modelPackage=com.guardiano3.generated.model" \
    --additional-properties="artifactUrl=${REPO_URL},artifactDescription=Generated models and operations for the GuardianO3 API" \
    --additional-properties="licenseName=Apache-2.0,licenseUrl=https://www.apache.org/licenses/LICENSE-2.0.txt" \
    --additional-properties="scmConnection=scm:git:${REPO_URL}.git,scmDeveloperConnection=scm:git:${REPO_URL}.git,scmUrl=${REPO_URL}" \
    --additional-properties="parentGroupId=com.guardiano3,parentArtifactId=guardiano3-sdk-parent,parentVersion=${VERSION}" \
    --additional-properties="developerName=GuardianO3,developerEmail=support@guardiano3.com,developerOrganization=Proneuronet Solutions Private Limited,developerOrganizationUrl=https://guardiano3.com" \
    >/dev/null

echo "Regenerated generated/ (version ${VERSION})."
