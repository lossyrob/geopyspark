package geopyspark.geotrellis

import geopyspark.geotrellis._
import geopyspark.geotrellis.GeoTrellisUtils._

import protos.tileMessages._
import protos.tupleMessages._

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.distance._
import geotrellis.raster.histogram._
import geotrellis.raster.io.geotiff._
import geotrellis.raster.io.geotiff.compression._
import geotrellis.raster.rasterize._
import geotrellis.raster.render._
import geotrellis.raster.resample.ResampleMethod
import geotrellis.spark._
import geotrellis.spark.costdistance.IterativeCostDistance
import geotrellis.spark.io._
import geotrellis.spark.io.json._
import geotrellis.spark.mapalgebra.local._
import geotrellis.spark.mapalgebra.focal._
import geotrellis.spark.mask.Mask
import geotrellis.spark.pyramid._
import geotrellis.spark.reproject._
import geotrellis.spark.tiling._
import geotrellis.spark.util._
import geotrellis.util._
import geotrellis.vector._
import geotrellis.vector.io.wkb.WKB
import geotrellis.vector.triangulation._
import geotrellis.vector.voronoi._

import spray.json._
import spray.json.DefaultJsonProtocol._
import spire.syntax.cfor._

import com.vividsolutions.jts.geom.Coordinate
import org.apache.spark._
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.rdd._
import org.apache.spark.SparkContext._

import java.util.ArrayList
import scala.reflect._
import scala.collection.JavaConverters._


abstract class TiledRasterLayer[K: SpatialComponent: JsonFormat: ClassTag] extends TileLayer[K] {
  import Constants._

  type keyType = K

  def rdd: RDD[(K, MultibandTile)] with Metadata[TileLayerMetadata[K]]
  def zoomLevel: Option[Int]

  def repartition(numPartitions: Int): TiledRasterLayer[K] =
    withRDD(rdd.repartition(numPartitions))

  def bands(band: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { multibandTile => multibandTile.subsetBands(band) })

  def bands(bands: java.util.ArrayList[Int]): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { multibandTile => multibandTile.subsetBands(bands.asScala) })

  def getZoom: Integer =
    zoomLevel match {
      case None => null
      case Some(z) => new Integer(z)
    }

  /** Encode RDD as Avro bytes and return it with avro schema used */
  def toProtoRDD(): JavaRDD[Array[Byte]]

  def layerMetadata: String = rdd.metadata.toJson.prettyPrint

  def mask(wkbs: java.util.ArrayList[Array[Byte]]): TiledRasterLayer[K] = {
    val geometries: Seq[MultiPolygon] = wkbs
      .asScala.map({ wkb => WKB.read(wkb) })
      .flatMap({
        case p: Polygon => Some(MultiPolygon(p))
        case m: MultiPolygon => Some(m)
        case _ => None
      })
    mask(geometries)
  }

  protected def mask(geometries: Seq[MultiPolygon]): TiledRasterLayer[K]

  protected def reproject(target_crs: String, resampleMethod: ResampleMethod): TiledRasterLayer[K]
  protected def reproject(target_crs: String, layoutType: LayoutType, resampleMethod: ResampleMethod): TiledRasterLayer[K]
  def reproject(targetCRS: String, layoutDefinition: LayoutDefinition, resampleMethod: ResampleMethod): TiledRasterLayer[K]

  def tileToLayout(
    layoutDefinition: LayoutDefinition,
    resampleMethod: ResampleMethod
  ): TiledRasterLayer[K] =
    tileToLayout(layoutDefinition, None, resampleMethod)

  def tileToLayout(
    layoutType: LayoutType,
    resampleMethod: ResampleMethod
  ): TiledRasterLayer[K]

  def tileToLayout(
    layOutDefinition: LayoutDefinition,
    zoom: Option[Int],
    resampleMethod: ResampleMethod
  ): TiledRasterLayer[K]

  def pyramid(resampleMethod: ResampleMethod): Array[_] // Array[TiledRasterLayer[K]]

  def focal(
    operation: String,
    neighborhood: String,
    param1: Double,
    param2: Double,
    param3: Double
  ): TiledRasterLayer[K]

  def costDistance(
    sc: SparkContext,
    wkbs: java.util.ArrayList[Array[Byte]],
    maxDistance: Double
  ): TiledRasterLayer[K] = {
    val geometries = wkbs.asScala.map({ wkb => WKB.read(wkb) })

    costDistance(sc, geometries, maxDistance)
  }

  protected def costDistance(
    sc: SparkContext,
    geometries: Seq[Geometry],
    maxDistance: Double
  ): TiledRasterLayer[K]

  def hillshade(sc: SparkContext,
    azimuth: Double,
    altitude: Double,
    zFactor: Double,
    band: Int
  ): TiledRasterLayer[K]

  def localAdd(i: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y + i }) })

  def localAdd(d: Double): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y + d }) })

  def localAdd(other: TiledRasterLayer[K]): TiledRasterLayer[K] =
    withRDD(rdd.combineValues(other.rdd) {
      case (x: MultibandTile, y: MultibandTile) => {
        val tiles: Vector[Tile] =
          x.bands.zip(y.bands).map { case (b1, b2) => b1 + b2 }
        MultibandTile(tiles)
      }
    })

  def localAdd(others: ArrayList[TiledRasterLayer[K]]): TiledRasterLayer[K] =
    withRDD(rdd.combineValues(others.asScala.map(_.rdd)) { ts =>
      val bandCount = ts.head.bandCount
      val newBands = Array.ofDim[Tile](bandCount)
      cfor(0)(_ < bandCount, _ + 1) { b =>
        newBands(b) = ts.map(_.band(b)).localAdd
      }
      MultibandTile(newBands)
    })

  def localSubtract(i: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y - i }) })

  def reverseLocalSubtract(i: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y.-:(i) }) })

  def localSubtract(d: Double): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y - d }) })

  def reverseLocalSubtract(d: Double): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y.-:(d) }) })

  def localSubtract(other: TiledRasterLayer[K]): TiledRasterLayer[K] =
    withRDD(rdd.combineValues(other.rdd) {
      case (x: MultibandTile, y: MultibandTile) => {
        val tiles: Vector[Tile] =
          x.bands.zip(y.bands).map(tup => tup._1 - tup._2)
        MultibandTile(tiles)
      }
    })

  def localMultiply(i: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y * i }) })

  def localMultiply(d: Double): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y * d }) })

  def localMultiply(other: TiledRasterLayer[K]): TiledRasterLayer[K] =
    withRDD(rdd.combineValues(other.rdd) {
      case (x: MultibandTile, y: MultibandTile) => {
        val tiles: Vector[Tile] =
          x.bands.zip(y.bands).map(tup => tup._1 * tup._2)
        MultibandTile(tiles)
      }
    })

  def localDivide(i: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y / i }) })

  def localDivide(d: Double): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y / d }) })

  def reverseLocalDivide(i: Int): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y./:(i) }) })

  def reverseLocalDivide(d: Double): TiledRasterLayer[K] =
    withRDD(rdd.mapValues { x => MultibandTile(x.bands.map { y => y./:(d) }) })

  def localDivide(other: TiledRasterLayer[K]): TiledRasterLayer[K] =
    withRDD(rdd.combineValues(other.rdd) {
      case (x: MultibandTile, y: MultibandTile) => {
        val tiles: Vector[Tile] =
          x.bands.zip(y.bands).map(tup => tup._1 / tup._2)
        MultibandTile(tiles)
      }
    })

  def convertDataType(newType: String): TiledRasterLayer[_] =
    withRDD(rdd.convert(CellType.fromName(newType)))

  def normalize(oldMin: Double, oldMax: Double, newMin: Double, newMax: Double): TiledRasterLayer[K] =
    withRDD {
      rdd.mapValues { tile =>
        MultibandTile {
          tile.bands.map { band =>
            band.normalize(oldMin, oldMax, newMin, newMax)
          }
        }
      }
    }

  def singleTileLayerRDD: TileLayerRDD[K] = TileLayerRDD(
    rdd.mapValues({ v => v.band(0) }),
    rdd.metadata
  )

  def polygonalMin(geom: Array[Byte]): Int =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalMin(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalMin(multi)
    }

  def polygonalMinDouble(geom: Array[Byte]): Double =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalMinDouble(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalMinDouble(multi)
    }

  def polygonalMax(geom: Array[Byte]): Int =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalMax(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalMax(multi)
    }

  def polygonalMaxDouble(geom: Array[Byte]): Double =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalMaxDouble(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalMaxDouble(multi)
    }

  def polygonalMean(geom: Array[Byte]): Double =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalMean(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalMean(multi)
    }

  def polygonalSum(geom: Array[Byte]): Long =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalSum(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalSum(multi)
    }

  def polygonalSumDouble(geom: Array[Byte]): Double =
    WKB.read(geom) match {
      case poly: Polygon => singleTileLayerRDD.polygonalSumDouble(poly)
      case multi: MultiPolygon => singleTileLayerRDD.polygonalSumDouble(multi)
    }

  def isFloatingPointLayer(): Boolean = rdd.metadata.cellType.isFloatingPoint

  def getIntHistograms(): Histogram[Int] = rdd.histogramExactInt.head

  def getDoubleHistograms(): Histogram[Double] = rdd.histogram.head

  protected def withRDD(result: RDD[(K, MultibandTile)]): TiledRasterLayer[K]
}
