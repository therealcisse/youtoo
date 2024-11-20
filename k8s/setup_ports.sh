#!/bin/sh

set -e

kubectl -n youtoo port-forward svc/youtoo-ingestion 8181:8181 &
kubectl -n telemetry port-forward svc/prometheus-operator-grafana  3000:80 &
kubectl -n telemetry port-forward svc/seq  4000:80 &
kubectl -n telemetry port-forward svc/simple-jaeger-query 16686 &
kubectl -n telemetry port-forward svc/prometheus-operated 9090 &
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard-kong-proxy 8443:443 &
kubectl -n telemetry port-forward svc/opentelemetry-operator 9443:8443 &
kubectl -n telemetry port-forward svc/opentelemetry-operator 6060:8080 &
