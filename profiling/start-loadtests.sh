#!/bin/sh

set -xe

tag=youtoo-profiling:latest
docker run -d --network youtoo_app-network -e YOUTOO_LOG_LEVEL=INFO -p 8181:8181 --env-file ../.env -p 10001:10001 --rm $tag

CONTAINER_ID=$(docker ps -q --filter "ancestor=$tag")
echo "Container ID: $CONTAINER_ID"

until [ "$(docker inspect --format='{{.State.Health.Status}}' $CONTAINER_ID)" == "healthy" ]; do
  echo "Waiting for the container to become healthy..."
  sleep 5
done

echo "Container is now healthy and ready on port 8181!"

pushd ..
sbt -DtestDuration=60 'loadtests/Gatling/test'
popd
