package com.socrata.geoexport

import java.io._
import java.math.BigDecimal
import java.net.URL
import java.util.{Date, UUID}
import java.util.zip.{ZipEntry, ZipFile}

import com.socrata.soql.types._
import com.vividsolutions.jts.geom._
import org.apache.commons.io.IOUtils
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.simple.{SimpleFeatureIterator, SimpleFeatureSource, SimpleFeatureCollection}
import org.joda.time.{DateTimeZone, LocalTime, LocalDateTime, DateTime}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import scala.util.{Try, Success, Failure}
import scala.xml.Utility.{trim => xmltrim}
import com.socrata.geoexport.conversions.Converter
import org.apache.commons.io.output.ByteArrayOutputStream
import scala.xml.{NodeSeq, XML, Node}
import com.socrata.geoexport.encoders.ShapefileEncoder
import scala.collection.JavaConversions._;

class GeoIterator(sfi: SimpleFeatureIterator) extends Iterator [SimpleFeature] with AutoCloseable {
  def hasNext: Boolean = sfi.hasNext()
  def next: SimpleFeature = sfi.next()
  def remove(): Unit = ???
  def close: Unit = sfi.close()
}

class ShapefileTest extends TestBase {
  val ldt = LocalDateTime.parse("2015-03-22T01:23")
  val dt = DateTime.parse("2015-03-22T12:00:00-08:00")
  val THE_TIME_UTC = 1427025600000L
  val AN_HOUR = 60 * 60 * 1000

  val simpleSchema = List(
    ("a_name", SoQLText),
    ("a_number", SoQLNumber),
    ("a_bool", SoQLBoolean),
    ("a_ts", SoQLFixedTimestamp),
    ("a_fts", SoQLFloatingTimestamp),
    ("a_time", SoQLTime),
    ("a_date", SoQLDate),
    ("a_money", SoQLMoney)
  )

  val simpleRows = List(
    SoQLText("this is a name"),
    SoQLNumber(new BigDecimal(42.00)),
    SoQLBoolean(true),
    SoQLFixedTimestamp(dt),
    SoQLFloatingTimestamp(ldt.plusHours(1)),
    SoQLTime(ldt.toLocalTime),
    SoQLDate(ldt.toLocalDate),
    SoQLMoney((new BigDecimal(42.00)))
  )

  private def convertShapefile(layers: List[InputStream]): File = {
    val archive = new File(s"/tmp/test_geo_export_${UUID.randomUUID()}.zip")
    val outStream = new FileOutputStream(archive)
    Converter.execute(layers, List(), new ShapefileEncoder(), outStream) match {
      case Success(outstream) =>
        outStream.flush()
        archive
      case Failure(err) => throw err
    }
  }

  private def multiPointCoords(mp: MultiPoint) = {
    Range(0, mp.getNumGeometries)
          .map(mp.getGeometryN(_).asInstanceOf[Point])
          .map { p => (p.getX, p.getY)}.toList
  }

  private def readArchive(archive: File): Seq[(SimpleFeatureType, Seq[SimpleFeature])] = {

    val zip = new ZipFile(archive)
    val entries = zip.entries()
    val it = new Iterator[ZipEntry] { def hasNext = entries.hasMoreElements; def next = entries.nextElement }

    it.map { entry =>
      val in = zip.getInputStream(entry)
      val file = new File(s"/tmp/read_${entry.getName}")
      val out = new FileOutputStream(file)
      IOUtils.copy(in, out)
      IOUtils.closeQuietly(in)
      out.close()
      file
    }.toList.filter{_.getName.endsWith(".shp")}.map { f =>

      val store = new ShapefileDataStore(new URL("File", "", f.getAbsolutePath))

      try {
        val shapeFeatureSource: SimpleFeatureSource = store.getFeatureSource
        // Get the shape schema, also known as the feature type
        val it = new GeoIterator(shapeFeatureSource.getFeatures.features)
        try {
          (shapeFeatureSource.getSchema, it.toList)
        } finally {
          it.close()
        }
      } finally {
        store.dispose()
        f.delete()
      }
    }
  }

  private def verifyFeature(feature: SimpleFeature) = {
    feature.getAttribute("a_name").toString must be("this is a name")
    feature.getAttribute("a_number") must be(42.00)
    feature.getAttribute("a_bool") must be(java.lang.Boolean.TRUE)

    feature.getAttribute("a_ts_date").asInstanceOf[Date] must be(ldt.withTime(0, 0, 0, 0).toDate)
    feature.getAttribute("a_ts_time") must be("20:00:00.000")

    feature.getAttribute("a_fts_date").asInstanceOf[Date] must be(ldt.withTime(0, 0, 0, 0).toDate)
    feature.getAttribute("a_fts_time") must be("02:23:00.000")

    feature.getAttribute("a_time") must be("01:23:00.000")
    feature.getAttribute("a_date") must be(ldt.withTime(0, 0, 0, 0).toDate)

    feature.getAttribute("a_money") must be(42.00)
  }


  test("can convert non geometry soql values to shp") {
    val p = wkt("POINT (0 1)").asInstanceOf[Point]

    val soqlSchema = simpleSchema :+ (("a_point", SoQLPoint))
    val items = simpleRows :+ SoQLPoint(p)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>

        features.size must be(1)
        val feature = features(0)

        verifyFeature(feature)
    }
  }


  test("can convert a stream of a point soqlpack to shp") {
    val p = wkt("POINT (0 1)").asInstanceOf[Point]

    val soqlSchema = simpleSchema :+ (("a_point", SoQLPoint))
    val items = simpleRows :+ SoQLPoint(p)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>
        features.size must be(1)
        val feature = features(0)
        val point = feature.getDefaultGeometry.asInstanceOf[Point]
        point.getX must be(0)
        point.getY must be(1)
    }
  }

  test("can convert a stream of a line soqlpack to shp") {
    val line = wkt("LINESTRING (30 10, 10 30, 40 40)").asInstanceOf[LineString]

    val soqlSchema = simpleSchema :+ (("a_line", SoQLLine))
    val items = simpleRows :+ SoQLLine(line)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>

        features.size must be(1)
        val feature = features(0)
        val shpLine = feature.getDefaultGeometry.asInstanceOf[MultiLineString]
        shpLine.getGeometryN(0).getCoordinates must be(line.getCoordinates)
    }
  }

  test("can convert a stream of a polygon soqlpack to shp") {
    val poly = wkt("POLYGON ((30 10, 10 20, 20 40, 40 40, 30 10))").asInstanceOf[Polygon]

    val soqlSchema = simpleSchema :+ (("a_poly", SoQLPolygon))
    val items = simpleRows :+ SoQLPolygon(poly)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>
        features.size must be(1)

        val feature = features(0)
        val shpPoly = feature.getDefaultGeometry.asInstanceOf[MultiPolygon]

        shpPoly.getGeometryN(0).getCoordinates must be(poly.getCoordinates)
    }
  }

  test("can convert a stream of a multipoint soqlpack to shp") {
    val points = wkt("MULTIPOINT ((10 40), (40 30), (20 20), (30 10))").asInstanceOf[MultiPoint]

    val soqlSchema = simpleSchema :+ (("a_multipoint", SoQLMultiPoint))
    val items = simpleRows :+ SoQLMultiPoint(points)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>
        features.size must be(1)

        val feature = features(0)
        val shpPoints = feature.getDefaultGeometry.asInstanceOf[MultiPoint]

        val coords = multiPointCoords(shpPoints)

        coords must be(List((10.0,40.0), (40.0,30.0), (20.0,20.0), (30.0,10.0)))
    }
  }

  test("can convert a stream of a MultiLine soqlpack to shp") {
    val lines = wkt("MULTILINESTRING ((10 10, 20 20, 10 40), (40 40, 30 30, 40 20, 30 10))").asInstanceOf[MultiLineString]

    val soqlSchema = simpleSchema :+ (("a_multipoly", SoQLMultiLine))
    val items = simpleRows :+ SoQLMultiLine(lines)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>
        features.size must be(1)
        val feature = features(0)
        val shpLines = feature.getDefaultGeometry.asInstanceOf[MultiLineString]

        lines.getGeometryN(0).getCoordinates must be(shpLines.getGeometryN(0).getCoordinates)
        lines.getGeometryN(1).getCoordinates must be(shpLines.getGeometryN(1).getCoordinates)

    }
  }

  test("can convert a stream of a MultiPolygon soqlpack to shp") {
    val expectedPolys = wkt("MULTIPOLYGON (((30 20, 10 40, 45 40, 30 20)), ((15 5, 5 10, 10 20, 40 10, 15 5)))").asInstanceOf[MultiPolygon]

    val soqlSchema = simpleSchema :+ (("a_multipoly", SoQLMultiPolygon))
    val items = simpleRows :+ SoQLMultiPolygon(expectedPolys)
    val packed = pack(soqlSchema, List(items.toArray))

    val layers = List(packed)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((schema, features)) =>
        features.size must be(1)

        val feature = features(0)
        val actualPolys = feature.getDefaultGeometry.asInstanceOf[MultiPolygon]

        actualPolys.getGeometryN(0).getCoordinates must be(expectedPolys.getGeometryN(0).getCoordinates)
        actualPolys.getGeometryN(1).getCoordinates must be(expectedPolys.getGeometryN(1).getCoordinates)

    }
  }


  test("can convert multiple streams of soqlpack a multilayer to shp") {
    val expectedPoints = wkt("MULTIPOINT ((10 40), (40 30), (20 20), (30 10))").asInstanceOf[MultiPoint]
    val soqlPointSchema = simpleSchema :+ (("a_multipoint", SoQLMultiPoint))
    val pointRows = simpleRows :+ SoQLMultiPoint(expectedPoints)
    val packedMultipoints = pack(soqlPointSchema, List(pointRows.toArray))

    val expectedPolys = wkt("MULTIPOLYGON (((30 20, 10 40, 45 40, 30 20)), ((15 5, 5 10, 10 20, 40 10, 15 5)))").asInstanceOf[MultiPolygon]
    val soqlPolySchema = simpleSchema :+ (("a_multipoly", SoQLMultiPolygon))
    val polyRows = simpleRows :+ SoQLMultiPolygon(expectedPolys)
    val packedPolys = pack(soqlPolySchema, List(polyRows.toArray))



    val layers = List(packedMultipoints, packedPolys)
    val archive = convertShapefile(layers)
    readArchive(archive) match {
      case Seq((pointSchema, pointFeatures), (polySchema, polyFeatures)) =>
        pointFeatures.size must be(1)
        polyFeatures.size must be(1)

        val actualPointFeature = pointFeatures(0)
        val actualMultiPoint = actualPointFeature.getDefaultGeometry.asInstanceOf[MultiPoint]
        multiPointCoords(actualMultiPoint) must be(multiPointCoords(expectedPoints))

        val actualPolyFeature = polyFeatures(0)
        val actualPoly = actualPolyFeature.getDefaultGeometry.asInstanceOf[MultiPolygon]
        actualPoly.getGeometryN(0).getCoordinates must be(expectedPolys.getGeometryN(0).getCoordinates)
        actualPoly.getGeometryN(1).getCoordinates must be(expectedPolys.getGeometryN(1).getCoordinates)

        verifyFeature(actualPointFeature)
        verifyFeature(actualPolyFeature)
    }
  }


}