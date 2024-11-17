resource "kubernetes_namespace" "observability" {
  lifecycle {
    ignore_changes = [
      metadata
    ]
  }

  metadata {
    name = "observability"
  }
}

resource "helm_release" "cert_manager" {
  name              = "cert-manager"
  repository        = "https://charts.jetstack.io"
  chart             = "cert-manager"
  namespace         = kubernetes_namespace.observability.metadata[0].name
  create_namespace  = false
  version           = var.cert_manager_release
  dependency_update = true

  set {
    name  = "installCRDs"
    value = true
  }

  values = [
    yamlencode({
      replicaCount = 2
    })
  ]

  wait = true
}

resource "helm_release" "jaeger_operator" {
  depends_on = [
    helm_release.cert_manager
  ]

  name       = "jaeger-operator"
  repository = "https://jaegertracing.github.io/helm-charts"
  chart      = "jaeger-operator"
  version    = var.jaeger_operator_chart_version
  namespace  = kubernetes_namespace.observability.metadata[0].name

  create_namespace = false # Namespace is created earlier using kubernetes_namespace resource

  timeout = 3600

  set {
    name  = "rbac.clusterRole"
    value = true
  }

  values = [
    # You can add custom values if needed for the operator, for example:
    # "rbac.create=true"
    # "clusterRole.create=true"
  ]

  wait = true
}

resource "time_sleep" "wait_for_jaegar" {
  depends_on = [
    helm_release.jaeger_operator
  ]

  create_duration = "30s"
}

resource "kubectl_manifest" "jaeger" {
  depends_on = [
    time_sleep.wait_for_jaegar
  ]

  yaml_body = <<YAML
apiVersion: jaegertracing.io/v1
kind: Jaeger
metadata:
  namespace: ${kubernetes_namespace.observability.metadata[0].name}
  name: simple-jaeger
spec:
  strategy: allInOne
  allInOne:
    options:
      query:
        base-path: "/jaeger"
  storage:
    type: memory
    options:
      memory:
        max-traces: "10000"
  ingress:
    enabled: true

YAML

}