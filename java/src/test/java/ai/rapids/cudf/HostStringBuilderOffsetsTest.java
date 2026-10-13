/*
 * SPDX-FileCopyrightText: Copyright (c) 2026, NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.rapids.cudf;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostStringBuilderOffsetsTest {
  private static final int CHUNK = 1 << 20;

  @Test void fixedBuilderPromotesOnlyWhenUsedBytesExceedInt32() {
    byte[] value = new byte[CHUNK];
    Arrays.fill(value, (byte) 'X');
    try (HostColumnVector.Builder builder = HostColumnVector.builder(2051, 1L << 31)) {
      for (int row = 0; row < 2047; row++) builder.appendUTF8String(value);
      builder.appendUTF8String(value, 0, CHUNK - 1);
      try (HostColumnVector atLimit = builder.build()) {
        assertEquals(DType.INT32, atLimit.getOffsetsType());
        assertEquals(Integer.MAX_VALUE, atLimit.getEndListOffset(2047));
      }
    }
    try (HostColumnVector.Builder builder = HostColumnVector.builder(2051, 1L << 31)) {
      for (int row = 0; row < 2047; row++) builder.appendUTF8String(value);
      builder.appendUTF8String(value, 0, CHUNK - 1);
      builder.appendUTF8String(value, 0, 1);
      builder.appendNull();
      builder.appendUTF8String(value, 1, 2);
      try (HostColumnVector host = builder.build()) {
        assertEquals(DType.INT64, host.getOffsetsType());
        assertEquals(Integer.MAX_VALUE, host.getEndListOffset(2047));
        assertEquals(1L << 31, host.getEndListOffset(2048));
        assertTrue(host.isNull(2049));
        assertEquals(1L << 31, host.getEndListOffset(2049));
        assertArrayEquals(new byte[]{'X', 'X'}, host.getUTF8(2050));
        assertArrayEquals(value, host.getUTF8(0));
      }
    }
  }

  @Test void nestedBuilderGrowsWideOffsetsAndPreservesNulls() {
    byte[] value = new byte[CHUNK];
    Arrays.fill(value, (byte) 'Y');
    HostColumnVector.DataType type = new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING));
    try (HostColumnVector.ColumnBuilder builder = new HostColumnVector.ColumnBuilder(type, 1)) {
      for (int row = 0; row < 2049; row++) {
        builder.appendLists(Arrays.asList(value));
      }
      builder.appendLists(Arrays.asList(null, value));
      try (HostColumnVector host = builder.build()) {
        HostColumnVectorCore strings = host.getChildColumnView(0);
        assertEquals(DType.INT32, host.getOffsetsType());
        assertEquals(DType.INT64, strings.getOffsetsType());
        assertEquals(2051, strings.getRowCount());
        assertEquals(2049L * CHUNK, strings.getEndListOffset(2048));
        assertTrue(strings.isNull(2049));
        assertArrayEquals(value, strings.getUTF8(2050));
        assertArrayEquals(value, strings.getUTF8(0));
      }
    }
  }

  @Test void nestedSmallBuilderKeepsInt32() {
    HostColumnVector.DataType type = new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING));
    try (HostColumnVector.ColumnBuilder builder = new HostColumnVector.ColumnBuilder(type, 1)) {
      builder.appendLists(Arrays.asList("ab", null, "cd"));
      try (HostColumnVector host = builder.build()) {
        assertEquals(DType.INT32, host.getOffsetsType());
        assertEquals(DType.INT32, host.getChildColumnView(0).getOffsetsType());
        assertEquals("cd", host.getChildColumnView(0).getJavaString(2));
      }
    }
  }
}
