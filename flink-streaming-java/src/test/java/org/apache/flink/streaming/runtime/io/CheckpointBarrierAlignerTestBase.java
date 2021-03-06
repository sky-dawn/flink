/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointMetrics;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.FreeingBufferRecycler;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.operators.testutils.DummyCheckpointInvokable;
import org.apache.flink.runtime.operators.testutils.DummyEnvironment;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Future;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/** Tests for the behavior of the {@link CheckpointBarrierAligner}. */
public abstract class CheckpointBarrierAlignerTestBase {

    protected static final int PAGE_SIZE = 512;

    private static final Random RND = new Random();

    private static int sizeCounter = 1;

    CheckpointedInputGate inputGate;

    static long testStartTimeNanos;

    private MockInputGate mockInputGate;

    @Before
    public void setUp() {
        testStartTimeNanos = System.nanoTime();
    }

    protected CheckpointedInputGate createBarrierBuffer(
            int numberOfChannels, BufferOrEvent[] sequence, AbstractInvokable toNotify)
            throws IOException {
        mockInputGate = new MockInputGate(numberOfChannels, Arrays.asList(sequence));
        return createBarrierBuffer(mockInputGate, toNotify);
    }

    protected CheckpointedInputGate createBarrierBuffer(
            int numberOfChannels, BufferOrEvent[] sequence) throws IOException {
        return createBarrierBuffer(numberOfChannels, sequence, new DummyCheckpointInvokable());
    }

    abstract CheckpointedInputGate createBarrierBuffer(InputGate gate, AbstractInvokable toNotify)
            throws IOException;

    @After
    public void ensureEmpty() throws Exception {
        assertFalse(inputGate.pollNext().isPresent());
        assertTrue(inputGate.isFinished());

        inputGate.close();
    }

    // ------------------------------------------------------------------------
    //  Tests
    // ------------------------------------------------------------------------

    /**
     * Validates that the buffer behaves correctly if no checkpoint barriers come, for a single
     * input channel.
     */
    @Test
    public void testSingleChannelNoBarriers() throws Exception {
        BufferOrEvent[] sequence = {
            createBuffer(0), createBuffer(0),
            createBuffer(0), createEndOfPartition(0)
        };
        inputGate = createBarrierBuffer(1, sequence);

        for (BufferOrEvent boe : sequence) {
            assertEquals(boe, inputGate.pollNext().get());
        }

        assertEquals(0L, inputGate.getAlignmentDurationNanos());
    }

    /**
     * Validates that the buffer behaves correctly if no checkpoint barriers come, for an input with
     * multiple input channels.
     */
    @Test
    public void testMultiChannelNoBarriers() throws Exception {
        BufferOrEvent[] sequence = {
            createBuffer(2),
            createBuffer(2),
            createBuffer(0),
            createBuffer(1),
            createBuffer(0),
            createEndOfPartition(0),
            createBuffer(3),
            createBuffer(1),
            createEndOfPartition(3),
            createBuffer(1),
            createEndOfPartition(1),
            createBuffer(2),
            createEndOfPartition(2)
        };
        inputGate = createBarrierBuffer(4, sequence);

        for (BufferOrEvent boe : sequence) {
            assertEquals(boe, inputGate.pollNext().get());
        }

        assertEquals(0L, inputGate.getAlignmentDurationNanos());
    }

    /**
     * Validates that the buffer preserved the order of elements for a input with a single input
     * channel, and checkpoint events.
     */
    @Test
    public void testSingleChannelWithBarriers() throws Exception {
        BufferOrEvent[] sequence = {
            createBuffer(0),
            createBuffer(0),
            createBuffer(0),
            createBarrier(1, 0),
            createBuffer(0),
            createBuffer(0),
            createBuffer(0),
            createBuffer(0),
            createBarrier(2, 0),
            createBarrier(3, 0),
            createBuffer(0),
            createBuffer(0),
            createBarrier(4, 0),
            createBarrier(5, 0),
            createBarrier(6, 0),
            createBuffer(0),
            createEndOfPartition(0)
        };
        ValidatingCheckpointHandler handler = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(1, sequence, handler);

        handler.setNextExpectedCheckpointId(1L);

        for (BufferOrEvent boe : sequence) {
            assertEquals(boe, inputGate.pollNext().get());
        }
    }

    /**
     * Validates that the buffer correctly aligns the streams for inputs with multiple input
     * channels.
     */
    @Test
    public void testMultiChannelWithBarriers() throws Exception {
        BufferOrEvent[] sequence = {
            // checkpoint with data from multi channels
            createBuffer(0),
            createBuffer(2),
            createBuffer(0),
            createBarrier(1, 1),
            createBarrier(1, 2),
            createBuffer(0),
            createBarrier(1, 0),

            // another checkpoint
            createBuffer(0),
            createBuffer(0),
            createBuffer(1),
            createBuffer(1),
            createBuffer(2),
            createBarrier(2, 0),
            createBarrier(2, 1),
            createBarrier(2, 2),

            // checkpoint with data only from one channel
            createBuffer(2),
            createBuffer(2),
            createBarrier(3, 2),
            createBuffer(0),
            createBuffer(0),
            createBarrier(3, 0),
            createBarrier(3, 1),

            // empty checkpoint
            createBarrier(4, 1),
            createBarrier(4, 2),
            createBarrier(4, 0),

            // some trailing data
            createBuffer(0),
            createEndOfPartition(0),
            createEndOfPartition(1),
            createEndOfPartition(2)
        };
        ValidatingCheckpointHandler handler = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(3, sequence, handler);

        handler.setNextExpectedCheckpointId(1L);

        // pre checkpoint 1
        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(1L, handler.getNextExpectedCheckpointId());

        long startTs = System.nanoTime();

        // checkpoint 1 done
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(2L, handler.getNextExpectedCheckpointId());
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels1 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // pre checkpoint 2
        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(2L, handler.getNextExpectedCheckpointId());

        // checkpoint 2 barriers come together
        startTs = System.nanoTime();
        check(sequence[12], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[13], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[14], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(3L, handler.getNextExpectedCheckpointId());
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels2 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[15], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[16], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 3
        check(sequence[17], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[18], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[19], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[20], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[21], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(4L, handler.getNextExpectedCheckpointId());
        Integer[] expectedUnblockedChannels3 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels3,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // checkpoint 4
        check(sequence[22], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[23], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[24], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(5L, handler.getNextExpectedCheckpointId());
        Integer[] expectedUnblockedChannels4 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels4,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // remaining data
        check(sequence[25], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[26], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[27], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[28], inputGate.pollNext().get(), PAGE_SIZE);
    }

    /**
     * Validates that the buffer skips over the current checkpoint if it receives a barrier from a
     * later checkpoint on a non-blocked input.
     */
    @Test
    public void testMultiChannelJumpingOverCheckpoint() throws Exception {
        BufferOrEvent[] sequence = {
            // checkpoint 1
            createBuffer(0),
            createBuffer(2),
            createBuffer(0),
            createBarrier(1, 1),
            createBarrier(1, 2),
            createBuffer(0),
            createBarrier(1, 0),
            createBuffer(1),
            createBuffer(0),

            // checkpoint 2 will not complete: pre-mature barrier from checkpoint 3
            createBarrier(2, 1),
            createBuffer(2),
            createBarrier(2, 0),
            createBuffer(2),
            createBarrier(3, 2),
            createBuffer(1),
            createBuffer(0),
            createBarrier(3, 0),
            createBarrier(4, 1),
            createBuffer(2),
            createBuffer(0),
            createEndOfPartition(0),
            createBuffer(2),
            createEndOfPartition(2),
            createBuffer(1),
            createEndOfPartition(1)
        };
        ValidatingCheckpointHandler handler = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(3, sequence, handler);

        handler.setNextExpectedCheckpointId(1L);

        // pre checkpoint 1
        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 1
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(1L, inputGate.getLatestCheckpointId());
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels1 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);

        // alignment of checkpoint 2
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(2L, inputGate.getLatestCheckpointId());
        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[12], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 2 aborted, checkpoint 3 started
        check(sequence[13], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(3L, inputGate.getLatestCheckpointId());
        Integer[] expectedUnblockedChannels2 = new Integer[] {0, 1};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[14], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[15], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[16], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 3 aborted, checkpoint 4 started
        check(sequence[17], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(4L, inputGate.getLatestCheckpointId());
        Integer[] expectedUnblockedChannels3 = new Integer[] {0, 2};
        assertArrayEquals(
                expectedUnblockedChannels3,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[18], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[19], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 4 aborted (due to end of partition)
        check(sequence[20], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels4 = new Integer[] {1};
        assertArrayEquals(
                expectedUnblockedChannels4,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[21], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[22], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[23], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[24], inputGate.pollNext().get(), PAGE_SIZE);

        assertEquals(1, handler.getTriggeredCheckpointCounter());
        assertEquals(3, handler.getAbortedCheckpointCounter());
    }

    @Test
    public void testMissingCancellationBarriers() throws Exception {
        BufferOrEvent[] sequence = {
            createBarrier(1L, 0),
            createCancellationBarrier(3L, 1),
            createCancellationBarrier(2L, 0),
            createCancellationBarrier(3L, 0),
            createBuffer(0)
        };
        AbstractInvokable validator = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(2, sequence, validator);

        for (BufferOrEvent boe : sequence) {
            if (boe.isBuffer() || boe.getEvent().getClass() != CancelCheckpointMarker.class) {
                assertEquals(boe, inputGate.pollNext().get());
            }
        }

        Integer[] expectedUnblockedChannels = new Integer[] {0};
        assertArrayEquals(
                expectedUnblockedChannels,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
    }

    @Test
    public void testStartAlignmentWithClosedChannels() throws Exception {
        BufferOrEvent[] sequence = {
            // close some channels immediately
            createEndOfPartition(2),
            createEndOfPartition(1),

            // checkpoint without blocked data
            createBuffer(0),
            createBuffer(0),
            createBuffer(3),
            createBarrier(2, 3),
            createBarrier(2, 0),

            // empty checkpoint
            createBarrier(3, 0),
            createBarrier(3, 3),

            // some data, one channel closes
            createBuffer(0),
            createBuffer(0),
            createBuffer(3),
            createEndOfPartition(0),

            // checkpoint on last remaining channel
            createBuffer(3),
            createBarrier(4, 3),
            createBuffer(3),
            createEndOfPartition(3)
        };
        inputGate = createBarrierBuffer(4, sequence);

        // pre checkpoint 2
        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 2 alignment
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(2L, inputGate.getLatestCheckpointId());
        Integer[] expectedUnblockedChannels1 = new Integer[] {0, 3};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // checkpoint 3 alignment
        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(3L, inputGate.getLatestCheckpointId());
        Integer[] expectedUnblockedChannels2 = new Integer[] {0, 3};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // after checkpoint 3
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[12], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[13], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 4 alignment
        check(sequence[14], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(4L, inputGate.getLatestCheckpointId());
        Integer[] expectedUnblockedChannels3 = new Integer[] {3};
        assertArrayEquals(
                expectedUnblockedChannels3,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[15], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[16], inputGate.pollNext().get(), PAGE_SIZE);
    }

    @Test
    public void testEndOfStreamWhileCheckpoint() throws Exception {
        BufferOrEvent[] sequence = {
            // one checkpoint
            createBarrier(1, 0),
            createBarrier(1, 1),
            createBarrier(1, 2),

            // some buffers
            createBuffer(0),
            createBuffer(0),
            createBuffer(2),

            // start the checkpoint that will be incomplete
            createBarrier(2, 2),
            createBarrier(2, 0),
            createBuffer(1),

            // close one after the barrier one before the barrier
            createEndOfPartition(1),
            createEndOfPartition(2),
            createBuffer(0),

            // final end of stream
            createEndOfPartition(0)
        };
        inputGate = createBarrierBuffer(3, sequence);

        // data after first checkpoint
        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels1 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(1L, inputGate.getLatestCheckpointId());

        // alignment of second checkpoint
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(2L, inputGate.getLatestCheckpointId());
        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);

        // first end-of-partition encountered: checkpoint will not be completed
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels2 = new Integer[] {0, 2};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[12], inputGate.pollNext().get(), PAGE_SIZE);
    }

    @Test
    public void testSingleChannelAbortCheckpoint() throws Exception {
        BufferOrEvent[] sequence = {
            createBuffer(0),
            createBarrier(1, 0),
            createBuffer(0),
            createBarrier(2, 0),
            createBuffer(0),
            createCancellationBarrier(4, 0),
            createBarrier(5, 0),
            createBuffer(0),
            createCancellationBarrier(6, 0),
            createBuffer(0)
        };
        ValidatingCheckpointHandler toNotify = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(1, sequence, toNotify);

        toNotify.setNextExpectedCheckpointId(1);
        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(0L, inputGate.getAlignmentDurationNanos());
        Integer[] expectedUnblockedChannels1 = new Integer[] {0};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        toNotify.setNextExpectedCheckpointId(2);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels2 = new Integer[] {0};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        toNotify.setNextExpectedCheckpointId(5);
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(5L, inputGate.getLatestCheckpointId());
        assertEquals(4, toNotify.getLastCanceledCheckpointId());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER,
                toNotify.getCheckpointFailureReason());
        assertEquals(0L, inputGate.getAlignmentDurationNanos());
        Integer[] expectedUnblockedChannels3 = new Integer[] {0};
        assertArrayEquals(
                expectedUnblockedChannels3,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(6L, inputGate.getLatestCheckpointId());
        assertEquals(6, toNotify.getLastCanceledCheckpointId());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER,
                toNotify.getCheckpointFailureReason());
        assertEquals(0L, inputGate.getAlignmentDurationNanos());

        assertEquals(3, toNotify.getTriggeredCheckpointCounter());
        assertEquals(2, toNotify.getAbortedCheckpointCounter());
    }

    @Test
    public void testMultiChannelAbortCheckpoint() throws Exception {
        BufferOrEvent[] sequence = {
            // some buffers and a successful checkpoint
            /* 0 */ createBuffer(0),
            createBuffer(2),
            createBuffer(0),
            /* 3 */ createBarrier(1, 1),
            createBarrier(1, 2),
            /* 5 */ createBuffer(0),
            /* 6 */ createBarrier(1, 0),
            /* 7 */ createBuffer(0),
            createBuffer(2),

            // aborted on last barrier
            /* 9 */ createBarrier(2, 0),
            createBarrier(2, 2),
            /* 11 */ createBuffer(1),
            /* 12 */ createCancellationBarrier(2, 1),

            // successful checkpoint
            /* 13 */ createBuffer(2),
            createBuffer(1),
            /* 15 */ createBarrier(3, 1),
            createBarrier(3, 2),
            createBarrier(3, 0),

            // abort on first barrier
            /* 18 */ createBuffer(0),
            createBuffer(1),
            /* 20 */ createCancellationBarrier(4, 1),
            createBarrier(4, 2),
            /* 22 */ createBuffer(2),
            /* 23 */ createBarrier(4, 0),

            // another successful checkpoint
            /* 24 */ createBuffer(0),
            createBuffer(1),
            createBuffer(2),
            /* 27 */ createBarrier(5, 2),
            createBarrier(5, 1),
            createBarrier(5, 0),
            /* 30 */ createBuffer(0),
            createBuffer(1),

            // abort multiple cancellations and a barrier after the cancellations
            /* 32 */ createCancellationBarrier(6, 1),
            createCancellationBarrier(6, 2),
            /* 34 */ createBarrier(6, 0),

            /* 35 */ createBuffer(0)
        };
        ValidatingCheckpointHandler toNotify = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(3, sequence, toNotify);

        long startTs;

        // pre checkpoint
        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);

        // first successful checkpoint
        startTs = System.nanoTime();
        toNotify.setNextExpectedCheckpointId(1);
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels1 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);

        // alignment of second checkpoint
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);

        // canceled checkpoint on last barrier
        check(sequence[13], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(2, toNotify.getLastCanceledCheckpointId());
        Integer[] expectedUnblockedChannels2 = new Integer[] {0, 2};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER,
                toNotify.getCheckpointFailureReason());
        check(sequence[14], inputGate.pollNext().get(), PAGE_SIZE);

        // one more successful checkpoint
        startTs = System.nanoTime();
        toNotify.setNextExpectedCheckpointId(3);
        check(sequence[15], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[16], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[17], inputGate.pollNext().get(), PAGE_SIZE);
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels3 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels3,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[18], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[19], inputGate.pollNext().get(), PAGE_SIZE);

        // this checkpoint gets immediately canceled
        check(sequence[21], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(4, toNotify.getLastCanceledCheckpointId());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER,
                toNotify.getCheckpointFailureReason());
        assertEquals(0L, inputGate.getAlignmentDurationNanos());
        Integer[] expectedUnblockedChannels4 = new Integer[] {2};
        assertArrayEquals(
                expectedUnblockedChannels4,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[22], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[23], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels5 = new Integer[] {0};
        assertArrayEquals(
                expectedUnblockedChannels5,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // some buffers
        check(sequence[24], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[25], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[26], inputGate.pollNext().get(), PAGE_SIZE);

        // a simple successful checkpoint
        startTs = System.nanoTime();
        toNotify.setNextExpectedCheckpointId(5);
        check(sequence[27], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[28], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[29], inputGate.pollNext().get(), PAGE_SIZE);
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels6 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels6,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[30], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[31], inputGate.pollNext().get(), PAGE_SIZE);

        // this checkpoint gets immediately canceled
        check(sequence[34], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(6, toNotify.getLastCanceledCheckpointId());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER,
                toNotify.getCheckpointFailureReason());
        assertEquals(0L, inputGate.getAlignmentDurationNanos());
        Integer[] expectedUnblockedChannels7 = new Integer[] {0};
        assertArrayEquals(
                expectedUnblockedChannels7,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[35], inputGate.pollNext().get(), PAGE_SIZE);

        assertEquals(3, toNotify.getTriggeredCheckpointCounter());
        assertEquals(3, toNotify.getAbortedCheckpointCounter());
    }

    /**
     * This tests where a checkpoint barriers meets a canceled checkpoint.
     *
     * <p>The newer checkpoint barrier must not try to cancel the already canceled checkpoint.
     */
    @Test
    public void testAbortOnCanceledBarriers() throws Exception {
        BufferOrEvent[] sequence = {
            // starting a checkpoint
            /*  0 */ createBuffer(1),
            /*  1 */ createBarrier(1, 1),
            /*  2 */ createBuffer(2),
            createBuffer(0),

            // cancel the initial checkpoint
            /*  4 */ createCancellationBarrier(1, 0),

            // receiving a buffer
            /*  5 */ createBuffer(1),

            // starting a new checkpoint
            /*  6 */ createBarrier(2, 1),

            // some more buffers
            /*  7 */ createBuffer(2),
            createBuffer(0),

            // ignored barrier - already canceled and moved to next checkpoint
            /* 9 */ createBarrier(1, 2),

            // some more buffers
            /* 10 */ createBuffer(0),
            createBuffer(2),

            // complete next checkpoint regularly
            /* 12 */ createBarrier(2, 0),
            createBarrier(2, 2),

            // some more buffers
            /* 14 */ createBuffer(0),
            createBuffer(1),
            createBuffer(2)
        };
        ValidatingCheckpointHandler toNotify = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(3, sequence, toNotify);

        long startTs;

        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);

        // starting first checkpoint
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);

        // cancelled by cancellation barrier
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(1, toNotify.getLastCanceledCheckpointId());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER,
                toNotify.getCheckpointFailureReason());
        Integer[] expectedUnblockedChannels1 = new Integer[] {1};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // the next checkpoint alignment
        startTs = System.nanoTime();
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[7], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);

        // ignored barrier and unblock channel directly
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);
        Integer[] expectedUnblockedChannels2 = new Integer[] {2};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint 2 done
        toNotify.setNextExpectedCheckpointId(2);
        check(sequence[12], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[13], inputGate.pollNext().get(), PAGE_SIZE);
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels3 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels3,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // trailing data
        check(sequence[14], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[15], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[16], inputGate.pollNext().get(), PAGE_SIZE);

        assertEquals(1, toNotify.getTriggeredCheckpointCounter());
        assertEquals(1, toNotify.getAbortedCheckpointCounter());
    }

    /**
     * This tests the where a cancellation barrier is received for a checkpoint already canceled due
     * to receiving a newer checkpoint barrier.
     */
    @Test
    public void testIgnoreCancelBarrierIfCheckpointSubsumed() throws Exception {
        BufferOrEvent[] sequence = {
            // starting a checkpoint
            /*  0 */ createBuffer(2),
            /*  1 */ createBarrier(3, 1),
            createBarrier(3, 0),
            /*  3 */ createBuffer(2),

            // newer checkpoint barrier cancels/subsumes pending checkpoint
            /*  4 */ createBarrier(5, 2),

            // some queued buffers
            /*  5 */ createBuffer(1),
            createBuffer(0),

            // cancel barrier the initial checkpoint /it is already canceled)
            /* 7 */ createCancellationBarrier(3, 0),

            // some more buffers
            /* 8 */ createBuffer(0),
            createBuffer(1),

            // complete next checkpoint regularly
            /* 10 */ createBarrier(5, 0),
            createBarrier(5, 1),

            // some more buffers
            /* 12 */ createBuffer(0),
            createBuffer(1),
            createBuffer(2)
        };
        ValidatingCheckpointHandler toNotify = new ValidatingCheckpointHandler();
        inputGate = createBarrierBuffer(3, sequence, toNotify);

        long startTs;

        // validate the sequence

        check(sequence[0], inputGate.pollNext().get(), PAGE_SIZE);

        // beginning of first checkpoint
        check(sequence[1], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[2], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[3], inputGate.pollNext().get(), PAGE_SIZE);

        // future barrier aborts checkpoint
        startTs = System.nanoTime();
        check(sequence[4], inputGate.pollNext().get(), PAGE_SIZE);
        assertEquals(3, toNotify.getLastCanceledCheckpointId());
        assertEquals(
                CheckpointFailureReason.CHECKPOINT_DECLINED_SUBSUMED,
                toNotify.getCheckpointFailureReason());
        Integer[] expectedUnblockedChannels1 = new Integer[] {0, 1};
        assertArrayEquals(
                expectedUnblockedChannels1,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());
        check(sequence[5], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[6], inputGate.pollNext().get(), PAGE_SIZE);

        // alignment of next checkpoint
        check(sequence[8], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[9], inputGate.pollNext().get(), PAGE_SIZE);

        // checkpoint finished
        toNotify.setNextExpectedCheckpointId(5);
        check(sequence[10], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[11], inputGate.pollNext().get(), PAGE_SIZE);
        validateAlignmentTime(startTs, inputGate);
        Integer[] expectedUnblockedChannels2 = new Integer[] {0, 1, 2};
        assertArrayEquals(
                expectedUnblockedChannels2,
                mockInputGate.getAndResetLastUnblockedChannels().toArray());

        // remaining data
        check(sequence[12], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[13], inputGate.pollNext().get(), PAGE_SIZE);
        check(sequence[14], inputGate.pollNext().get(), PAGE_SIZE);

        // check overall notifications
        assertEquals(1, toNotify.getTriggeredCheckpointCounter());
        assertEquals(1, toNotify.getAbortedCheckpointCounter());
    }

    // ------------------------------------------------------------------------
    //  Utils
    // ------------------------------------------------------------------------

    private static BufferOrEvent createBarrier(long checkpointId, int channel) {
        return new BufferOrEvent(
                new CheckpointBarrier(
                        checkpointId,
                        System.currentTimeMillis(),
                        CheckpointOptions.forCheckpointWithDefaultLocation()),
                new InputChannelInfo(0, channel));
    }

    private static BufferOrEvent createCancellationBarrier(long checkpointId, int channel) {
        return new BufferOrEvent(
                new CancelCheckpointMarker(checkpointId), new InputChannelInfo(0, channel));
    }

    private static BufferOrEvent createBuffer(int channel) {
        final int size = sizeCounter++;
        byte[] bytes = new byte[size];
        RND.nextBytes(bytes);

        MemorySegment memory = MemorySegmentFactory.allocateUnpooledSegment(PAGE_SIZE);
        memory.put(0, bytes);

        Buffer buf = new NetworkBuffer(memory, FreeingBufferRecycler.INSTANCE);
        buf.setSize(size);

        // retain an additional time so it does not get disposed after being read by the input gate
        buf.retainBuffer();

        return new BufferOrEvent(buf, new InputChannelInfo(0, channel));
    }

    private static BufferOrEvent createEndOfPartition(int channel) {
        return new BufferOrEvent(EndOfPartitionEvent.INSTANCE, new InputChannelInfo(0, channel));
    }

    private static void check(BufferOrEvent expected, BufferOrEvent present, int pageSize) {
        assertNotNull(expected);
        assertNotNull(present);
        assertEquals(expected.isBuffer(), present.isBuffer());

        if (expected.isBuffer()) {
            assertEquals(
                    expected.getBuffer().getMaxCapacity(), present.getBuffer().getMaxCapacity());
            assertEquals(expected.getBuffer().getSize(), present.getBuffer().getSize());
            MemorySegment expectedMem = expected.getBuffer().getMemorySegment();
            MemorySegment presentMem = present.getBuffer().getMemorySegment();
            assertTrue(
                    "memory contents differs",
                    expectedMem.compare(presentMem, 0, 0, pageSize) == 0);
        } else {
            assertEquals(expected.getEvent(), present.getEvent());
        }
    }

    private static void validateAlignmentTime(
            long alignmentStartTimestamp, CheckpointedInputGate inputGate) {
        long elapsedAlignment = System.nanoTime() - alignmentStartTimestamp;
        long elapsedTotalTime = System.nanoTime() - testStartTimeNanos;
        assertThat(
                inputGate.getAlignmentDurationNanos(),
                Matchers.lessThanOrEqualTo(elapsedAlignment));

        // Barrier lag is calculated with System.currentTimeMillis(), so we need a tolerance of 1ms
        // when comparing to time elapsed via System.nanoTime()
        long tolerance = 1_000_000;
        assertThat(
                inputGate.getCheckpointStartDelayNanos(),
                Matchers.lessThanOrEqualTo(elapsedTotalTime + tolerance));
    }

    // ------------------------------------------------------------------------
    //  Testing Mocks
    // ------------------------------------------------------------------------

    /** The invokable handler used for triggering checkpoint and validation. */
    private static class ValidatingCheckpointHandler extends AbstractInvokable {

        private CheckpointFailureReason failureReason;
        private long lastCanceledCheckpointId = -1L;
        private long nextExpectedCheckpointId = -1L;
        private long triggeredCheckpointCounter = 0;
        private long abortedCheckpointCounter = 0;

        public ValidatingCheckpointHandler() {
            super(new DummyEnvironment("test", 1, 0));
        }

        public void setNextExpectedCheckpointId(long nextExpectedCheckpointId) {
            this.nextExpectedCheckpointId = nextExpectedCheckpointId;
        }

        public CheckpointFailureReason getCheckpointFailureReason() {
            return failureReason;
        }

        public long getLastCanceledCheckpointId() {
            return lastCanceledCheckpointId;
        }

        public long getTriggeredCheckpointCounter() {
            return triggeredCheckpointCounter;
        }

        public long getAbortedCheckpointCounter() {
            return abortedCheckpointCounter;
        }

        public long getNextExpectedCheckpointId() {
            return nextExpectedCheckpointId;
        }

        @Override
        public void invoke() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Boolean> triggerCheckpointAsync(
                CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions) {
            throw new UnsupportedOperationException("should never be called");
        }

        @Override
        public void triggerCheckpointOnBarrier(
                CheckpointMetaData checkpointMetaData,
                CheckpointOptions checkpointOptions,
                CheckpointMetrics checkpointMetrics) {
            assertTrue(
                    "wrong checkpoint id",
                    nextExpectedCheckpointId == -1L
                            || nextExpectedCheckpointId == checkpointMetaData.getCheckpointId());

            assertTrue(checkpointMetaData.getTimestamp() > 0);
            assertTrue(checkpointMetrics.getAlignmentDurationNanos() >= 0);

            nextExpectedCheckpointId++;
            triggeredCheckpointCounter++;
        }

        @Override
        public void abortCheckpointOnBarrier(long checkpointId, Throwable cause) {
            lastCanceledCheckpointId = checkpointId;
            failureReason = ((CheckpointException) cause).getCheckpointFailureReason();
            abortedCheckpointCounter++;
        }

        @Override
        public Future<Void> notifyCheckpointCompleteAsync(long checkpointId) {
            throw new UnsupportedOperationException("should never be called");
        }
    }

    /** A validation matcher for checkpoint exception against failure reason. */
    public static class CheckpointExceptionMatcher extends BaseMatcher<CheckpointException> {

        private final CheckpointFailureReason failureReason;

        public CheckpointExceptionMatcher(CheckpointFailureReason failureReason) {
            this.failureReason = failureReason;
        }

        @Override
        public boolean matches(Object o) {
            return o != null
                    && o.getClass() == CheckpointException.class
                    && ((CheckpointException) o).getCheckpointFailureReason().equals(failureReason);
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("CheckpointException - reason = " + failureReason);
        }
    }
}
