package com.dbstudio.desktop.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkedListTest {
    @Test
    void appendsAcrossChunkBoundariesWithoutChangingTheOldSnapshot() {
        List<Integer> source = new ArrayList<Integer>();
        for (int index = 0; index < ChunkedList.CHUNK_SIZE + 3; index++) source.add(index);
        ChunkedList<Integer> first = ChunkedList.copy(source);
        ChunkedList<Integer> second = first.append(Arrays.asList(10_000, 10_001));

        assertEquals(ChunkedList.CHUNK_SIZE + 3, first.size());
        assertEquals(ChunkedList.CHUNK_SIZE + 5, second.size());
        assertEquals(Integer.valueOf(ChunkedList.CHUNK_SIZE + 2), first.get(first.size() - 1));
        assertEquals(Integer.valueOf(10_001), second.get(second.size() - 1));
        assertThrows(UnsupportedOperationException.class, () -> second.add(4));
    }
}
