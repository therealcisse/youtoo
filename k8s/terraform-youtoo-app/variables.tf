variable "jaeger_operator_chart_version" {
  type        = string
  description = "Version of the jaeger-operator chart"
  default     = "2.57.0"
}

variable "cert_manager_release" {
  type    = string
  default = "1.16.1"
}

variable "postgres_host" {
  type = string
}

variable "postgres_db_name" {
  type = string
}

variable "postgres_db_username" {
  type = string
}

variable "postgres_db_password" {
  type = string
}

