/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.util;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.lucene.tests.util.CrateLuceneTestCase;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.Accountables;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;

import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.carrotsearch.randomizedtesting.SeedUtils;

import io.crate.common.collections.Sets;

public class MockBigArrays extends BigArrays {

    /**
     * Tracking allocations is useful when debugging a leak but shouldn't be enabled by default as this would also be very costly
     * since it creates a new Exception every time a new array is created.
     */
    private static final boolean TRACK_ALLOCATIONS = false;

    private static final ConcurrentMap<Object, Object> ACQUIRED_ARRAYS = new ConcurrentHashMap<>();

    public static void ensureAllArraysAreReleased() throws Exception {
        final Map<Object, Object> masterCopy = new HashMap<>(ACQUIRED_ARRAYS);
        if (!masterCopy.isEmpty()) {
            // not empty, we might be executing on a shared cluster that keeps on obtaining
            // and releasing arrays, lets make sure that after a reasonable timeout, all master
            // copy (snapshot) have been released
            boolean success = ESTestCase.awaitBusy(() -> Sets.haveEmptyIntersection(masterCopy.keySet(), ACQUIRED_ARRAYS.keySet()));
            if (!success) {
                masterCopy.keySet().retainAll(ACQUIRED_ARRAYS.keySet());
                ACQUIRED_ARRAYS.keySet().removeAll(masterCopy.keySet()); // remove all existing master copy we will report on
                if (!masterCopy.isEmpty()) {
                    Iterator<Object> causes = masterCopy.values().iterator();
                    Object firstCause = causes.next();
                    RuntimeException exception = new RuntimeException(masterCopy.size() + " arrays have not been released",
                            firstCause instanceof Throwable ? (Throwable) firstCause : null);
                    while (causes.hasNext()) {
                        Object cause = causes.next();
                        if (cause instanceof Throwable) {
                            exception.addSuppressed((Throwable) cause);
                        }
                    }
                    throw exception;
                }
            }
        }
    }

    private final Random random;
    private final PageCacheRecycler recycler;
    private final CircuitBreakerService breakerService;

    public MockBigArrays(PageCacheRecycler recycler, CircuitBreakerService breakerService) {
        this(recycler, breakerService, false);
    }

    private MockBigArrays(PageCacheRecycler recycler, CircuitBreakerService breakerService, boolean checkBreaker) {
        super(recycler, breakerService, CircuitBreaker.REQUEST, checkBreaker);
        this.recycler = recycler;
        this.breakerService = breakerService;
        long seed;
        try {
            seed = SeedUtils.parseSeed(RandomizedContext.current().getRunnerSeedAsString());
        } catch (IllegalStateException e) { // rest tests don't run randomized and have no context
            seed = 0;
        }
        random = new Random(seed);
    }

    @Override
    public ByteArray newByteArray(long size, boolean clearOnResize) {
        final ByteArrayWrapper array = new ByteArrayWrapper(super.newByteArray(size, clearOnResize), clearOnResize);
        if (!clearOnResize) {
            array.randomizeContent(0, size);
        }
        return array;
    }

    @Override
    public ByteArray resize(ByteArray array, long size) {
        ByteArrayWrapper arr = (ByteArrayWrapper) array;
        final long originalSize = arr.size();
        array = super.resize(arr.in, size);
        ACQUIRED_ARRAYS.remove(arr);
        if (array instanceof ByteArrayWrapper) {
            arr = (ByteArrayWrapper) array;
        } else {
            arr = new ByteArrayWrapper(array, arr.clearOnResize);
        }
        if (!arr.clearOnResize) {
            arr.randomizeContent(originalSize, size);
        }
        return arr;
    }

    @Override
    public IntArray newIntArray(long size, boolean clearOnResize) {
        final IntArrayWrapper array = new IntArrayWrapper(super.newIntArray(size, clearOnResize), clearOnResize);
        if (!clearOnResize) {
            array.randomizeContent(0, size);
        }
        return array;
    }

    @Override
    public IntArray resize(IntArray array, long size) {
        IntArrayWrapper arr = (IntArrayWrapper) array;
        final long originalSize = arr.size();
        array = super.resize(arr.in, size);
        ACQUIRED_ARRAYS.remove(arr);
        if (array instanceof IntArrayWrapper) {
            arr = (IntArrayWrapper) array;
        } else {
            arr = new IntArrayWrapper(array, arr.clearOnResize);
        }
        if (!arr.clearOnResize) {
            arr.randomizeContent(originalSize, size);
        }
        return arr;
    }

    @Override
    public <T> ObjectArray<T> newObjectArray(long size) {
        return new ObjectArrayWrapper<>(super.<T>newObjectArray(size));
    }

    @Override
    public <T> ObjectArray<T> resize(ObjectArray<T> array, long size) {
        ObjectArrayWrapper<T> arr = (ObjectArrayWrapper<T>) array;
        array = super.resize(arr.in, size);
        ACQUIRED_ARRAYS.remove(arr);
        if (array instanceof ObjectArrayWrapper) {
            arr = (ObjectArrayWrapper<T>) array;
        } else {
            arr = new ObjectArrayWrapper<>(array);
        }
        return arr;
    }

    private abstract static class AbstractArrayWrapper {

        final boolean clearOnResize;
        private final AtomicReference<AssertionError> originalRelease;

        AbstractArrayWrapper(boolean clearOnResize) {
            this.clearOnResize = clearOnResize;
            this.originalRelease = new AtomicReference<>();
            ACQUIRED_ARRAYS.put(this,
                    TRACK_ALLOCATIONS ? new RuntimeException("Unreleased array from test: " + CrateLuceneTestCase.getTestClass().getName())
                            : Boolean.TRUE);
        }

        protected abstract BigArray getDelegate();

        protected abstract void randomizeContent(long from, long to);

        public long size() {
            return getDelegate().size();
        }

        public long ramBytesUsed() {
            return getDelegate().ramBytesUsed();
        }

        public void close() {
            if (originalRelease.compareAndSet(null, new AssertionError()) == false) {
                throw new IllegalStateException("Double release. Original release attached as cause", originalRelease.get());
            }
            ACQUIRED_ARRAYS.remove(this);
            randomizeContent(0, size());
            getDelegate().close();
        }

    }

    private class ByteArrayWrapper extends AbstractArrayWrapper implements ByteArray {

        private final ByteArray in;

        ByteArrayWrapper(ByteArray in, boolean clearOnResize) {
            super(clearOnResize);
            this.in = in;
        }

        @Override
        protected BigArray getDelegate() {
            return in;
        }

        @Override
        protected void randomizeContent(long from, long to) {
            fill(from, to, (byte) random.nextInt(1 << 8));
        }

        @Override
        public byte get(long index) {
            return in.get(index);
        }

        @Override
        public byte set(long index, byte value) {
            return in.set(index, value);
        }

        @Override
        public boolean get(long index, int len, BytesRef ref) {
            return in.get(index, len, ref);
        }

        @Override
        public void set(long index, byte[] buf, int offset, int len) {
            in.set(index, buf, offset, len);
        }

        @Override
        public void fill(long fromIndex, long toIndex, byte value) {
            in.fill(fromIndex, toIndex, value);
        }

        @Override
        public boolean hasArray() {
            return in.hasArray();
        }

        @Override
        public byte[] array() {
            return in.array();
        }

        @Override
        public Collection<Accountable> getChildResources() {
            return Collections.singleton(Accountables.namedAccountable("delegate", in));
        }
    }

    private class IntArrayWrapper extends AbstractArrayWrapper implements IntArray {

        private final IntArray in;

        IntArrayWrapper(IntArray in, boolean clearOnResize) {
            super(clearOnResize);
            this.in = in;
        }

        @Override
        protected BigArray getDelegate() {
            return in;
        }

        @Override
        protected void randomizeContent(long from, long to) {
            fill(from, to, random.nextInt());
        }

        @Override
        public int get(long index) {
            return in.get(index);
        }

        @Override
        public int set(long index, int value) {
            return in.set(index, value);
        }

        @Override
        public int increment(long index, int inc) {
            return in.increment(index, inc);
        }

        @Override
        public void fill(long fromIndex, long toIndex, int value) {
            in.fill(fromIndex, toIndex, value);
        }

        @Override
        public Collection<Accountable> getChildResources() {
            return Collections.singleton(Accountables.namedAccountable("delegate", in));
        }
    }

    private class ObjectArrayWrapper<T> extends AbstractArrayWrapper implements ObjectArray<T> {

        private final ObjectArray<T> in;

        ObjectArrayWrapper(ObjectArray<T> in) {
            super(false);
            this.in = in;
        }

        @Override
        protected BigArray getDelegate() {
            return in;
        }

        @Override
        public T get(long index) {
            return in.get(index);
        }

        @Override
        public T set(long index, T value) {
            return in.set(index, value);
        }

        @Override
        protected void randomizeContent(long from, long to) {
            // will be cleared anyway
        }

        @Override
        public Collection<Accountable> getChildResources() {
            return Collections.singleton(Accountables.namedAccountable("delegate", in));
        }
    }

}
