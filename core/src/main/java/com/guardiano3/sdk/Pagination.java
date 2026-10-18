package com.guardiano3.sdk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Cursor pagination as an {@link Iterable}.
 *
 * <p>The API returns an opaque {@code nextCursor}; a caller should never loop
 * on it by hand and must never construct one. Lazy: a page is fetched only when
 * the previous one has been consumed, so taking the first item costs one
 * request rather than walking the whole history.
 */
public final class Pagination {

    private Pagination() {}

    /** One page of results. */
    public static final class Page<T> {
        private final List<T> items;
        private final String nextCursor;

        public Page(List<T> items, String nextCursor) {
            this.items = items == null ? List.of() : items;
            this.nextCursor = nextCursor;
        }

        public List<T> getItems() { return items; }
        public String getNextCursor() { return nextCursor; }
    }

    /** Every item across pages. {@code fetch} takes a cursor (null for the first). */
    public static <T> Iterable<T> iterate(Function<String, Page<T>> fetch) {
        return () -> new Iterator<>() {
            private Iterator<T> current = List.<T>of().iterator();
            private String cursor = null;
            private boolean exhausted = false;

            private void advance() {
                while (!current.hasNext() && !exhausted) {
                    Page<T> page = fetch.apply(cursor);
                    current = page.getItems().iterator();
                    cursor = page.getNextCursor();
                    // `hasMore` alone is not enough: a page can report more
                    // while giving no cursor to reach it, and looping on that
                    // spins forever.
                    if (cursor == null || cursor.isEmpty()) {
                        exhausted = true;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return current.hasNext();
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
    }

    /** Collect up to {@code max} items (0 or less = all). */
    public static <T> List<T> collect(Function<String, Page<T>> fetch, int max) {
        List<T> out = new ArrayList<>();
        for (T item : iterate(fetch)) {
            out.add(item);
            if (max > 0 && out.size() >= max) {
                break;
            }
        }
        return out;
    }
}
