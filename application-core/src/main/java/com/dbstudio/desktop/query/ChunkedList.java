package com.dbstudio.desktop.query;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;

/** Immutable list whose append operation shares all complete chunks. */
public final class ChunkedList<E> extends AbstractList<E> implements RandomAccess {
    public static final int CHUNK_SIZE = 1024;

    private final List<List<E>> chunks;
    private final int size;

    private ChunkedList(List<List<E>> chunks, int size) {
        this.chunks = Collections.unmodifiableList(chunks);
        this.size = size;
    }

    public static <E> ChunkedList<E> empty() {
        return new ChunkedList<E>(Collections.<List<E>>emptyList(), 0);
    }

    public static <E> ChunkedList<E> copy(List<? extends E> source) {
        if (source == null || source.isEmpty()) return empty();
        Builder<E> builder = new Builder<E>();
        for (E value : source) builder.add(value);
        return builder.build();
    }

    public static <E> ChunkedList<E> append(List<? extends E> source, List<? extends E> values) {
        ChunkedList<E> base = source instanceof ChunkedList
                ? cast(source) : copy(source);
        return base.append(values);
    }

    @SuppressWarnings("unchecked")
    private static <E> ChunkedList<E> cast(List<? extends E> source) {
        return (ChunkedList<E>) source;
    }

    public ChunkedList<E> append(List<? extends E> values) {
        if (values == null || values.isEmpty()) return this;
        List<List<E>> next = new ArrayList<List<E>>(chunks);
        int valueIndex = 0;
        if (!next.isEmpty() && next.get(next.size() - 1).size() < CHUNK_SIZE) {
            List<E> tail = new ArrayList<E>(next.get(next.size() - 1));
            int available = CHUNK_SIZE - tail.size();
            for (int index = 0; index < available && valueIndex < values.size(); index++) {
                tail.add(values.get(valueIndex++));
            }
            next.set(next.size() - 1, immutableChunk(tail));
        }
        while (valueIndex < values.size()) {
            int end = Math.min(values.size(), valueIndex + CHUNK_SIZE);
            List<E> chunk = new ArrayList<E>(end - valueIndex);
            for (int index = valueIndex; index < end; index++) chunk.add(values.get(index));
            next.add(immutableChunk(chunk));
            valueIndex = end;
        }
        return new ChunkedList<E>(next, size + values.size());
    }

    @Override public E get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("index=" + index);
        return chunks.get(index / CHUNK_SIZE).get(index % CHUNK_SIZE);
    }

    @Override public int size() { return size; }

    private static <E> List<E> immutableChunk(List<E> values) {
        return Collections.unmodifiableList(values);
    }

    public static final class Builder<E> {
        private final List<List<E>> chunks = new ArrayList<List<E>>();
        private List<E> current = new ArrayList<E>(CHUNK_SIZE);
        private int size;

        public Builder<E> add(E value) {
            current.add(value);
            size++;
            if (current.size() == CHUNK_SIZE) {
                chunks.add(immutableChunk(current));
                current = new ArrayList<E>(CHUNK_SIZE);
            }
            return this;
        }

        public Builder<E> addAll(Iterable<? extends E> values) {
            if (values != null) for (E value : values) add(value);
            return this;
        }

        public ChunkedList<E> build() {
            List<List<E>> result = new ArrayList<List<E>>(chunks);
            if (!current.isEmpty()) result.add(immutableChunk(new ArrayList<E>(current)));
            return new ChunkedList<E>(result, size);
        }
    }
}
