/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.memory

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.orca.q.DeadMessageCallback
import com.netflix.spinnaker.orca.q.Message
import com.netflix.spinnaker.orca.q.Queue
import com.netflix.spinnaker.orca.q.ScheduledAction
import com.netflix.spinnaker.orca.q.metrics.MonitoredQueue
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import org.threeten.extra.Temporals.chronoUnit
import java.io.Closeable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.TemporalAmount
import java.util.*
import java.util.UUID.randomUUID
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.PreDestroy

class InMemoryQueue(
  private val clock: Clock,
  override val ackTimeout: TemporalAmount = Duration.ofMinutes(1),
  override val deadMessageHandler: DeadMessageCallback,
  override val registry: Registry
) : MonitoredQueue, Closeable {

  private val log: Logger = getLogger(javaClass)

  private val queue = DelayQueue<Envelope>()
  private val unacked = DelayQueue<Envelope>()
  private val redeliveryWatcher = ScheduledAction(this::redeliver)

  override fun poll(callback: (Message, () -> Unit) -> Unit) {
    _lastQueuePoll.lazySet(clock.instant())
    queue.poll()?.let { envelope ->
      unacked.put(envelope.copy(scheduledTime = clock.instant().plus(ackTimeout)))
      callback.invoke(envelope.payload) {
        ack(envelope.id)
        ackCounter.increment()
      }
    }
  }

  override fun push(message: Message, delay: TemporalAmount) {
    queue.put(Envelope(message, clock.instant().plus(delay), clock))
    pushCounter.increment()
  }

  private fun ack(messageId: UUID) {
    unacked.removeIf { it.id == messageId }
  }

  override val queueDepth: Int
    get() = queue.size

  override val unackedDepth: Int
    get() = unacked.size

  private val _lastQueuePoll = AtomicReference<Instant?>()
  override val lastQueuePoll: Instant?
    get() = _lastQueuePoll.get()

  private val _lastRedeliveryPoll = AtomicReference<Instant?>()
  override val lastRedeliveryPoll: Instant?
    get() = _lastRedeliveryPoll.get()

  @PreDestroy override fun close() {
    log.info("stopping redelivery watcher for $this")
    redeliveryWatcher.close()
  }

  internal fun redeliver() {
    val now = clock.instant()
    _lastRedeliveryPoll.lazySet(now)
    unacked.pollAll {
      if (it.count >= Queue.maxRedeliveries) {
        deadMessageHandler.invoke(this, it.payload)
        deadMessageCounter.increment()
      } else {
        log.warn("redelivering unacked message ${it.payload}")
        queue.put(it.copy(scheduledTime = now, count = it.count + 1))
        redeliverCounter.increment()
      }
    }
  }

  private fun <T : Delayed> DelayQueue<T>.pollAll(block: (T) -> Unit) {
    var done = false
    while (!done) {
      val value = poll()
      if (value == null) {
        done = true
      } else {
        block.invoke(value)
      }
    }
  }
}

internal data class Envelope(
  val id: UUID,
  val payload: Message,
  val scheduledTime: Instant,
  val clock: Clock,
  val count: Int = 1
) : Delayed {
  constructor(payload: Message, scheduledTime: Instant, clock: Clock) :
    this(randomUUID(), payload, scheduledTime, clock)

  override fun compareTo(other: Delayed) =
    getDelay(MILLISECONDS).compareTo(other.getDelay(MILLISECONDS))

  override fun getDelay(unit: TimeUnit) =
    clock.instant().until(scheduledTime, unit.toChronoUnit())
}

private fun TimeUnit.toChronoUnit() = chronoUnit(this)
