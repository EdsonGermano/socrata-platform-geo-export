package com.socrata.geoexport.conversions

import java.io._

import com.socrata.geoexport.encoders.GeoEncoder
import com.socrata.soql.SoQLPackIterator

import scala.util.{Failure, Success, Try}
import com.rojoma.simplearm.v2.ResourceScope

object Converter {

  /**
    Take some layers streams and merge them into the outputstream using the encoder
    This will merge the layers into whatever representation the encoder decides to represent
    layers as.
  */
  def execute(
    rs: ResourceScope, layerStreams: Iterable[InputStream],
    encoder: GeoEncoder, os: OutputStream) : Try[OutputStream] = {

    Try(layerStreams.map { is =>
      new SoQLPackIterator(new DataInputStream(is))
    }).flatMap { features =>
      encoder.encode(rs, features, os)
    }
  }
}
