/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.local.builders;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.Repeat;
import com.google.common.collect.Lists;
import org.apache.streams.core.StreamBuilder;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsPersistWriter;
import org.apache.streams.core.StreamsProcessor;
import org.apache.streams.local.queues.ThroughputQueue;
import org.apache.streams.local.test.processors.PassthroughDatumCounterProcessor;
import org.apache.streams.local.test.processors.SlowProcessor;
import org.apache.streams.local.test.providers.EmptyResultSetProvider;
import org.apache.streams.local.test.providers.NumericMessageProvider;
import org.apache.streams.local.test.writer.DatumCounterWriter;
import org.apache.streams.local.test.writer.SystemOutWriter;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.management.*;

/**
 * Basic Tests for the LocalStreamBuilder.
 *
 * Test are performed by redirecting system out and counting the number of lines that the SystemOutWriter prints
 * to System.out.  The SystemOutWriter also prints one line when cleanUp() is called, so this is why it tests for
 * the numDatums +1.
 *
 *
 */
public class LocalStreamBuilderTest extends RandomizedTest {


    public void removeRegisteredMBeans(String... ids) {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        for(String id : ids) {
            try {
                mbs.unregisterMBean(new ObjectName(String.format(ThroughputQueue.NAME_TEMPLATE, id)));
            } catch (MalformedObjectNameException|InstanceNotFoundException|MBeanRegistrationException e) {
                //No-op
            }
        }
    }

    @Test
    public void testStreamIdValidations() {
        StreamBuilder builder = new LocalStreamBuilder();
        builder.newReadCurrentStream("id", new NumericMessageProvider(1));
        Exception exp = null;
        try {
            builder.newReadCurrentStream("id", new NumericMessageProvider(1));
        } catch (RuntimeException e) {
            exp = e;
        }
        assertNotNull(exp);
        exp = null;
        builder.addStreamsProcessor("1", new PassthroughDatumCounterProcessor("1"), 1, "id");
        try {
            builder.addStreamsProcessor("2", new PassthroughDatumCounterProcessor("2"), 1, "id", "id2");
        } catch (RuntimeException e) {
            exp = e;
        }
        assertNotNull(exp);
        removeRegisteredMBeans("1", "2");
    }

    @Test
    public void testBasicLinearStream1()  {
        linearStreamNonParallel(1, 1);
    }

    @Test
    @Repeat(iterations = 3)
    public void testBasicLinearStream2()  {
        int numDatums = randomIntBetween(1, 100000);
        int numProcessors = randomIntBetween(1, 10);
        linearStreamNonParallel(numDatums, numProcessors);
    }

    /**
     * Tests that all datums pass through each processor and that all datums reach the writer
     * @param numDatums
     * @param numProcessors
     */
    private void linearStreamNonParallel(int numDatums, int numProcessors) {
        String processorId = "proc";
        try {
            StreamBuilder builder = new LocalStreamBuilder(10);
            builder.newPerpetualStream("numeric_provider", new NumericMessageProvider(numDatums));
            String connectTo = null;
            for(int i=0; i < numProcessors; ++i) {
                if(i == 0) {
                    connectTo = "numeric_provider";
                } else {
                    connectTo = processorId+(i-1);
                }
                builder.addStreamsProcessor(processorId+i, new PassthroughDatumCounterProcessor(processorId+i), 1, connectTo);
            }
            Set output = Collections.newSetFromMap(new ConcurrentHashMap());
            builder.addStreamsPersistWriter("writer", new DatumCounterWriter("writer"), 1, processorId+(numProcessors-1));
            builder.start();
            for(int i=0; i < numProcessors; ++i) {
                assertEquals("Processor "+i+" did not receive all of the datums", numDatums, PassthroughDatumCounterProcessor.COUNTS.get(processorId+i).get());
            }
            for(int i=0; i < numDatums; ++i) {
                assertTrue("Expected writer to have received : "+i, DatumCounterWriter.RECEIVED.get("writer").contains(i));
            }
        } finally {
            for(int i=0; i < numProcessors; ++i) {
                removeRegisteredMBeans(processorId+i);
            }
            removeRegisteredMBeans("writer");
        }
    }

    @Test
    public void testParallelLinearStream1() {
        String processorId = "proc";
        int numProcessors = randomIntBetween(1, 100);
        int numDatums = randomIntBetween(1, 300000);
        try {
            StreamBuilder builder = new LocalStreamBuilder();
            builder.newPerpetualStream("numeric_provider", new NumericMessageProvider(numDatums));
            AtomicInteger[] processorCounters = new AtomicInteger[numProcessors];
            String connectTo = null;
            for(int i=0; i < numProcessors; ++i) {
                processorCounters[i] = new AtomicInteger(0);
                if(i == 0) {
                    connectTo = "numeric_provider";
                } else {
                    connectTo = processorId+(i-1);
                }
                int parallelHint = randomIntBetween(1,5);
                builder.addStreamsProcessor(processorId+i, createPassThroughProcessor(processorCounters[i]), parallelHint, connectTo);
            }
            Set output = Collections.newSetFromMap(new ConcurrentHashMap());

            builder.addStreamsPersistWriter("writer", createSetCollectingWriter(output), 1, processorId+(numProcessors-1));
            builder.start();
            // can't test processors since they are serialized and deserialized when parallelized
            for(int i=0; i < numDatums; ++i) {
                assertTrue("Expected writer to have received : "+i, output.contains(i));
            }
        } finally {
            for(int i=0; i < numProcessors; ++i) {
                removeRegisteredMBeans(processorId+i);
            }
            removeRegisteredMBeans("writer");
        }
    }

    @Test
    public void testBasicMergeStream() {
        try {
            int numDatums1 = randomIntBetween(1, 300000);
            int numDatums2 = randomIntBetween(1, 300000);;
            AtomicInteger counter1 = new AtomicInteger(0);
            AtomicInteger counter2 = new AtomicInteger(0);
            StreamsProcessor processor1 = createPassThroughProcessor(counter1);
            StreamsProcessor processor2 = createPassThroughProcessor(counter2);
            StreamBuilder builder = new LocalStreamBuilder();
            Set output = Collections.newSetFromMap(new ConcurrentHashMap());
            AtomicInteger outPutcounter = new AtomicInteger(0);
            builder.newReadCurrentStream("sp1", new NumericMessageProvider(numDatums1))
                    .newReadCurrentStream("sp2", new NumericMessageProvider(numDatums2))
                    .addStreamsProcessor("proc1", processor1, 1, "sp1")
                    .addStreamsProcessor("proc2", processor2, 1, "sp2")
                    .addStreamsPersistWriter("writer1", createSetCollectingWriter(output, outPutcounter), 1, "proc1", "proc2");
            builder.start();
            assertEquals(numDatums1, counter1.get());
            assertEquals(numDatums2, counter2.get());
            assertEquals(numDatums1+numDatums2, outPutcounter.get());
        } finally {
            removeRegisteredMBeans("proc1", "proc2", "writer1");
        }
    }

    @Test
    public void testBasicBranch() {
        try {
            int numDatums = randomIntBetween(1, 300000);
            StreamBuilder builder = new LocalStreamBuilder();
            AtomicInteger counter1 = new AtomicInteger(0);
            AtomicInteger counter2 = new AtomicInteger(0);
            Set output = Collections.newSetFromMap(new ConcurrentHashMap());
            AtomicInteger outputCounter = new AtomicInteger(0);
            builder.newReadCurrentStream("prov1", new NumericMessageProvider(numDatums))
                    .addStreamsProcessor("proc1", createPassThroughProcessor(counter1), 1, "prov1")
                    .addStreamsProcessor("proc2", createPassThroughProcessor(counter2), 1, "prov1")
                    .addStreamsPersistWriter("w1", createSetCollectingWriter(output, outputCounter), 1, "proc1", "proc2");
            builder.start();
            assertEquals(numDatums, counter1.get());
            assertEquals(numDatums, counter2.get());
            assertEquals(numDatums*2, outputCounter.get());
        } finally {
            removeRegisteredMBeans("prov1", "proc1", "proc2", "w1");
        }
    }

    @Test
    public void testSlowProcessorBranch() {
        try {
            int numDatums = 30;
            int timeout = 2000;
            Map<String, Object> config = Maps.newHashMap();
            config.put(LocalStreamBuilder.TIMEOUT_KEY, timeout);
            StreamBuilder builder = new LocalStreamBuilder(config);
            AtomicInteger counter = new AtomicInteger(0);
            Set output = Collections.newSetFromMap(new ConcurrentHashMap());
            builder.newReadCurrentStream("prov1", new NumericMessageProvider(numDatums))
                    .addStreamsProcessor("proc1", new SlowProcessor(), 1, "prov1")
                    .addStreamsPersistWriter("w1", createSetCollectingWriter(output, counter), 1, "proc1");
            builder.start();
            assertEquals(numDatums, counter.get());
        } finally {
            removeRegisteredMBeans("prov1", "proc1", "w1");
        }
    }

    @Test
    public void testConfiguredProviderTimeout() {
        try {
            Map<String, Object> config = Maps.newHashMap();
            int timeout = 10000;
            config.put(LocalStreamBuilder.TIMEOUT_KEY, timeout);
            long start = System.currentTimeMillis();
            StreamBuilder builder = new LocalStreamBuilder(-1, config);
            builder.newPerpetualStream("prov1", new EmptyResultSetProvider())
                    .addStreamsProcessor("proc1", new PassthroughDatumCounterProcessor("proc1"), 1, "prov1")
                    .addStreamsProcessor("proc2", new PassthroughDatumCounterProcessor("proc2"), 1, "proc1")
                    .addStreamsPersistWriter("w1", new SystemOutWriter(), 1, "proc1");
            builder.start();
            long end = System.currentTimeMillis();
            //We care mostly that it doesn't terminate too early.  With thread shutdowns, etc, the actual time is indeterminate.  Just make sure there is an upper bound
            assertThat((int) (end - start), is(allOf(greaterThanOrEqualTo(timeout), lessThanOrEqualTo(4 * timeout))));
        } finally {
            removeRegisteredMBeans("prov1", "proc1", "proc2", "w1");
        }
    }

    @Test
    public void ensureShutdownWithBlockedQueue() throws InterruptedException {
        try {
            ExecutorService service = Executors.newSingleThreadExecutor();
            int before = Thread.activeCount();
            final StreamBuilder builder = new LocalStreamBuilder(1);
            builder.newPerpetualStream("prov1", new NumericMessageProvider(30))
                    .addStreamsProcessor("proc1", new SlowProcessor(), 1, "prov1")
                    .addStreamsPersistWriter("w1", new SystemOutWriter(), 1, "proc1");
            service.submit(new Runnable() {
                @Override
                public void run() {
                    builder.start();
                }
            });
            //Let streams spin up threads and start to process
            Thread.sleep(500);
            builder.stop();
            service.shutdownNow();
            service.awaitTermination(1000, TimeUnit.MILLISECONDS);
            assertThat(Thread.activeCount(), is(equalTo(before)));
        } finally {
            removeRegisteredMBeans("prov1", "proc1", "w1");
        }
    }


    /**
     * Creates {@link org.apache.streams.core.StreamsProcessor} that passes any StreamsDatum it gets as an
     * input and counts the number of items it processes.
     * @param counter
     * @return
     */
    private StreamsProcessor createPassThroughProcessor(final AtomicInteger counter) {
        StreamsProcessor processor = mock(StreamsProcessor.class);
        when(processor.process(any(StreamsDatum.class))).thenAnswer(new Answer<List<StreamsDatum>>() {
            @Override
            public List<StreamsDatum> answer(InvocationOnMock invocationOnMock) throws Throwable {
                List<StreamsDatum> datum = Lists.newLinkedList();
                if(counter != null) {
                    counter.incrementAndGet();
                }
                datum.add((StreamsDatum) invocationOnMock.getArguments()[0] );
                return datum;
            }
        });
        return processor;
    }

    private StreamsPersistWriter createSetCollectingWriter(final Set collector) {
        return createSetCollectingWriter(collector, null);
    }

    /**
     * Creates a StreamsPersistWriter that adds every datums document to a set
     * @param collector
     * @return
     */
    private StreamsPersistWriter createSetCollectingWriter(final Set collector, final AtomicInteger counter) {
        StreamsPersistWriter writer = mock(StreamsPersistWriter.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if(counter != null) {
                    counter.incrementAndGet();
                }
                collector.add(((StreamsDatum)invocationOnMock.getArguments()[0]).getDocument());
                return null;
            }
        }).when(writer).write(any(StreamsDatum.class));
        return writer;
    }
}
