/*
 * Copyright 2012 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.zipkin.collector.builder

import ch.qos.logback.classic
import ch.qos.logback.classic.Level
import com.twitter.finagle.ThriftMux
import com.twitter.logging.Logger
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.twitter.zipkin.builder.Builder
import com.twitter.zipkin.collector.filter.{SamplerFilter, ServiceStatsFilter}
import com.twitter.zipkin.collector.sampler.AdjustableGlobalSampler
import com.twitter.zipkin.collector.{SpanReceiver, ZipkinCollector}
import com.twitter.zipkin.common.Span
import com.twitter.zipkin.receiver.scribe.ScribeReceiver
import com.twitter.zipkin.storage.Store
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable builder for ZipkinCollector
 *
 * CollectorInterface[T] stands up the actual server, and its filter is used to process the object
 * of type T into a Span in a worker thread.
 *
 * @tparam T type of object added to write queue
 */
case class CollectorServiceBuilder[T](
  storeBuilder: Builder[Store],
  receiver: Option[SpanReceiver.Processor => SpanReceiver] = None,
  scribeCategories: Set[String] = Set("zipkin"),
  sampleRate: AtomicReference[Float] = new AtomicReference[Float](1.0f),
  serverBuilder: ZipkinServerBuilder = ZipkinServerBuilder(9410, 9900),
  logLevel: String = "INFO"
) extends Builder[RuntimeEnvironment => ZipkinCollector] {

  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[classic.Logger].setLevel(Level.toLevel(logLevel))

  val log = Logger.get()

  def apply() = (runtime: RuntimeEnvironment) => {
    serverBuilder.apply().apply(runtime)

    log.info("Building store: %s".format(storeBuilder.toString))
    val store = storeBuilder.apply()

    val sampler = new SamplerFilter(new AdjustableGlobalSampler(sampleRate))

    val process = (spans: Seq[Span]) =>
      store.spanStore.apply(ServiceStatsFilter(sampler(spans)))

    val stats = serverBuilder.statsReceiver
    val impl = new ScribeReceiver(scribeCategories, process, stats)
    val server = ThriftMux.server
      .serveIface(new InetSocketAddress(serverBuilder.serverAddress, serverBuilder.serverPort), impl)

    // initialize any alternate receiver, such as kafka
    val rcv = receiver.map(_(process))

    new ZipkinCollector(server, store, rcv)
  }
}
