#!/usr/bin/env bash
set -eu -o pipefail
cd "$(dirname "$0")/../.."
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
test "$(git rev-parse 'monolith-v1^{commit}')" = 3ae96ef3daa5bbdd3b781cf9d743f3d62c4a3413
git archive monolith-v1 | tar -x -C "$scratch"
mvn -B -f "$scratch/pom.xml" package -Dmaven.test.skip=true
cp "$scratch"/target/*.jar "$scratch/app.jar"
# Same runtime stage, base JRE and flags as the current service images.
sed -n '/^FROM eclipse-temurin/,$p' Dockerfile | sed 's|--from=builder --chown=spring:spring /app/app.jar|--chown=spring:spring app.jar|' > "$scratch/Runtime.Dockerfile"
docker build -f "$scratch/Runtime.Dockerfile" -t flashsale-bench-monolith:3ae96ef "$scratch"
for service in order inventory; do
  docker build --build-arg SERVICE="$service-service" -t "flashsale-bench-$service:working" .
done
docker pull grafana/k6:1.0.0
