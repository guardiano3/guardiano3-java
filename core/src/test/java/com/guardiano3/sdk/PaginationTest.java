package com.guardiano3.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaginationTest {

    @Test
    @DisplayName("every page is walked and the cursor never reaches the caller")
    void walksEveryPage() {
        List<String> seen = new ArrayList<>();
        for (String item : Pagination.iterate(cursor ->
                cursor == null
                        ? new Pagination.Page<>(List.of("a", "b"), "p2")
                        : new Pagination.Page<>(List.of("c"), null))) {
            seen.add(item);
        }
        assertEquals(List.of("a", "b", "c"), seen);
    }

    @Test
    @DisplayName("pagination is lazy")
    void isLazy() {
        // Taking the first item must cost one request, not the whole history.
        AtomicInteger calls = new AtomicInteger();
        var iterator = Pagination.iterate(cursor -> {
            calls.incrementAndGet();
            return new Pagination.Page<>(List.of(1, 2), "more");
        }).iterator();
        iterator.next();
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("a page claiming more with no cursor terminates")
    void terminatesWithoutACursor() {
        // Looping on a "has more" flag alone spins forever; the cursor is the
        // only thing that can be trusted to advance.
        AtomicInteger calls = new AtomicInteger();
        List<Integer> seen = new ArrayList<>();
        for (int item : Pagination.iterate(cursor -> {
            assertTrue(calls.incrementAndGet() < 5, "iterator did not terminate");
            return new Pagination.Page<>(List.of(1), null);
        })) {
            seen.add(item);
        }
        assertEquals(List.of(1), seen);
    }

    @Test
    @DisplayName("collect respects its limit")
    void collectRespectsLimit() {
        List<Integer> got = Pagination.collect(
                cursor -> new Pagination.Page<>(List.of(1, 2, 3), "next"), 2);
        assertEquals(List.of(1, 2), got);
    }

    @Test
    @DisplayName("an empty first page terminates rather than spinning")
    void emptyPageTerminates() {
        List<Integer> got = Pagination.collect(
                cursor -> new Pagination.Page<>(List.of(), null), 0);
        assertTrue(got.isEmpty());
    }
}
