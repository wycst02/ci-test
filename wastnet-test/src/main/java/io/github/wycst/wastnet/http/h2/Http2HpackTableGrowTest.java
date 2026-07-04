package io.github.wycst.wastnet.http.h2;

import java.lang.reflect.Field;

/**
 * Test to verify Http2HpackTable grow() method correctness.
 */
public class Http2HpackTableGrowTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Testing Http2HpackTable grow() correctness...\n");

        // Create table with small max size to trigger grow quickly
        Http2HpackTable table = new Http2HpackTable(10000);

        // Use reflection to access private fields
        Field tableField = Http2HpackTable.class.getDeclaredField("table");
        Field newestIdxField = Http2HpackTable.class.getDeclaredField("newestIdx");
        Field countField = Http2HpackTable.class.getDeclaredField("count");
        Field maskField = Http2HpackTable.class.getDeclaredField("mask");

        tableField.setAccessible(true);
        newestIdxField.setAccessible(true);
        countField.setAccessible(true);
        maskField.setAccessible(true);

        // Add headers until we trigger grow (initial capacity is 64)
        System.out.println("Adding headers to trigger grow...");
        for (int i = 0; i < 70; i++) {
            String name = "header-" + i;
            String value = "value-" + i;
            table.addHeader(new Http2Header(name, value));

            if (i == 63) {
                // Just before grow
                int capacity = ((Http2Header[]) tableField.get(table)).length;
                int newestIdx = newestIdxField.getInt(table);
                int count = countField.getInt(table);
                int mask = maskField.getInt(table);

                System.out.println("\nBefore grow (after adding 64 headers):");
                System.out.println("  Capacity: " + capacity);
                System.out.println("  Count: " + count);
                System.out.println("  NewestIdx: " + newestIdx);
                System.out.println("  Mask: " + mask);
            }

            if (i == 64) {
                // Just after grow
                int capacity = ((Http2Header[]) tableField.get(table)).length;
                int newestIdx = newestIdxField.getInt(table);
                int count = countField.getInt(table);
                int mask = maskField.getInt(table);

                System.out.println("\nAfter grow (after adding 65th header):");
                System.out.println("  Capacity: " + capacity);
                System.out.println("  Count: " + count);
                System.out.println("  NewestIdx: " + newestIdx);
                System.out.println("  Mask: " + mask);

                // Verify newestIdx is correct
                if (capacity != 128) {
                    System.err.println("ERROR: Expected capacity 128, got " + capacity);
                    System.exit(1);
                }

                // After grow, newestIdx should be 127 (because next insert will be there)
                // Let's verify by checking what getHeader returns
                Http2Header latest = table.getHeader(62);  // Dynamic index 62 = newest
                if (!latest.name.equals("header-64")) {
                    System.err.println("ERROR: Expected latest header 'header-64', got '" + latest.name + "'");
                    System.exit(1);
                }
                System.out.println("  ✓ Latest header is correct: " + latest.name);

                // Check second latest
                Http2Header secondLatest = table.getHeader(63);
                if (!secondLatest.name.equals("header-63")) {
                    System.err.println("ERROR: Expected second latest 'header-63', got '" + secondLatest.name + "'");
                    System.exit(1);
                }
                System.out.println("  ✓ Second latest header is correct: " + secondLatest.name);

                // Check oldest in table (should be header-1, because header-0 was evicted)
                // With maxHeaderSize=10000 and avg entry size ~30, we can fit ~333 entries
                // So no eviction happened yet
                Http2Header oldest = table.getHeader(62 + count - 1);
                System.out.println("  ✓ Oldest header in table: " + oldest.name);
            }
        }

        System.out.println("\n✅ All tests passed! grow() is working correctly.");

        // Additional test: verify circular buffer behavior after grow
        System.out.println("\nTesting circular buffer wrap-around after grow...");
        Http2HpackTable table2 = new Http2HpackTable(100000);

        // Fill up to trigger grow
        for (int i = 0; i < 100; i++) {
            table2.addHeader(new Http2Header("key-" + i, "val-" + i));
        }

        // Verify we can retrieve all headers in correct order
        boolean allCorrect = true;
        for (int i = 0; i < 100; i++) {
            Http2Header h = table2.getHeader(62 + i);
            String expectedName = "key-" + (99 - i);  // Reverse order: newest first
            if (!h.name.equals(expectedName)) {
                System.err.println("ERROR at index " + i + ": expected '" + expectedName + "', got '" + h.name + "'");
                allCorrect = false;
            }
        }

        if (allCorrect) {
            System.out.println("✅ Circular buffer ordering is correct after grow!");
        } else {
            System.err.println("❌ Circular buffer ordering is broken!");
            System.exit(1);
        }
    }
}
