/*
 * Copyright 2021 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.parquet.types

import com.spotify.scio.ScioContext
import com.spotify.scio.coders.{Coder, CoderMaterializer}
import com.spotify.scio.io.{ScioIO, Tap, TapOf, TapT}
import com.spotify.scio.parquet.read.{ParquetRead, ReadSupportFactory}
import com.spotify.scio.parquet.{BeamInputFile, GcsConnectorUtil}
import com.spotify.scio.util.ScioUtil
import com.spotify.scio.values.SCollection
import magnolify.parquet.ParquetType
import org.apache.beam.sdk.io.hadoop.SerializableConfiguration
import org.apache.beam.sdk.io.{
  DefaultFilenamePolicy,
  DynamicFileDestinations,
  FileBasedSink,
  FileSystems,
  WriteFiles
}
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.Job
import org.apache.parquet.filter2.predicate.FilterPredicate
import org.apache.parquet.hadoop.ParquetInputFormat
import org.apache.parquet.hadoop.metadata.CompressionCodecName

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

final case class ParquetTypeIO[T: ClassTag: Coder: ParquetType](
  path: String
) extends ScioIO[T] {
  override type ReadP = ParquetTypeIO.ReadParam[T]
  override type WriteP = ParquetTypeIO.WriteParam[T]
  override val tapT: TapT.Aux[T, T] = TapOf[T]

  private val tpe: ParquetType[T] = implicitly[ParquetType[T]]

  override protected def read(sc: ScioContext, params: ReadP): SCollection[T] = {
    if (params.predicate != null) {
      ParquetInputFormat.setFilterPredicate(params.conf, params.predicate)
    }

    val coder = CoderMaterializer.beam(sc, implicitly[Coder[T]])

    sc.applyTransform(
      ParquetRead.read(
        ReadSupportFactory.typed,
        new SerializableConfiguration(params.conf),
        path,
        identity[T]
      )
    ).setCoder(coder)
  }

  override protected def write(data: SCollection[T], params: WriteP): Tap[T] = {
    val job = Job.getInstance()
    if (ScioUtil.isLocalRunner(data.context.options.getRunner)) {
      GcsConnectorUtil.setCredentials(job)
    }

    val resource =
      FileBasedSink.convertToFileResourceIfPossible(ScioUtil.pathWithShards(path))
    val prefix = StaticValueProvider.of(resource)
    val usedFilenamePolicy =
      DefaultFilenamePolicy.fromStandardParameters(prefix, null, params.suffix, false)
    val destinations = DynamicFileDestinations.constant[T](usedFilenamePolicy)
    val sink =
      new ParquetTypeSink[T](prefix, destinations, tpe, job.getConfiguration, params.compression)
    val t = WriteFiles.to(sink).withNumShards(params.numShards)
    data.applyInternal(t)
    tap(ParquetTypeIO.ReadParam[T]())
  }

  override def tap(params: ReadP): Tap[tapT.T] =
    ParquetTypeTap(ScioUtil.addPartSuffix(path), params)
}

object ParquetTypeIO {
  object ReadParam {
    private[types] val DefaultPredicate = null
    private[types] val DefaultConfiguration = new Configuration()
  }
  final case class ReadParam[T] private (
    predicate: FilterPredicate = null,
    conf: Configuration = ReadParam.DefaultConfiguration
  )

  object WriteParam {
    private[types] val DefaultNumShards = 0
    private[types] val DefaultSuffix = ".parquet"
    private[types] val DefaultCompression = CompressionCodecName.GZIP
    private[types] val DefaultConfiguration = new Configuration()
  }

  final case class WriteParam[T] private (
    numShards: Int = WriteParam.DefaultNumShards,
    suffix: String = WriteParam.DefaultSuffix,
    compression: CompressionCodecName = WriteParam.DefaultCompression,
    conf: Configuration = WriteParam.DefaultConfiguration
  )
}

case class ParquetTypeTap[T: ClassTag: Coder: ParquetType](
  path: String,
  params: ParquetTypeIO.ReadParam[T]
) extends Tap[T] {
  override def value: Iterator[T] = {
    val tpe = implicitly[ParquetType[T]]
    val xs = FileSystems.`match`(path).metadata().asScala.toList
    xs.iterator.flatMap { metadata =>
      val reader = tpe.readBuilder(BeamInputFile.of(metadata.resourceId())).build()
      new Iterator[T] {
        private var current: T = reader.read()
        override def hasNext: Boolean = current != null
        override def next(): T = {
          val r = current
          current = reader.read()
          r
        }
      }
    }
  }

  override def open(sc: ScioContext): SCollection[T] = sc.read(ParquetTypeIO[T](path))(params)
}
