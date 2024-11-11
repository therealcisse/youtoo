package com.youtoo

enum Env {
  case local, docker
}

object Env {
  def load: Env = sys.env.get("YOUTOOENVNAME") match {
    case Some("local") => Env.local
    case Some("docker") => Env.docker
    case _ => throw IllegalArgumentException("NO 'YOUTOOENVNAME' defined")
  }

  extension (e: Env)
    inline def name: String = e match {
      case Env.local => "local"
      case Env.docker => "docker"
    }

}
