package com.youtoo
package std
package utils

import zio.schema.*

transparent trait JsonSupport {
  given [T: Schema] => zio.json.JsonCodec[T] = zio.schema.codec.JsonCodec.jsonCodec(Schema[T])
}

