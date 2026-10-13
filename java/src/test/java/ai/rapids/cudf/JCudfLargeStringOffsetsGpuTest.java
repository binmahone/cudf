/*
 * SPDX-FileCopyrightText: Copyright (c) 2026, NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.rapids.cudf;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JCudfLargeStringOffsetsGpuTest {
  private static final int BYTES_PER_ROW = 1 << 20;
  private static HostColumnVector dense(boolean wide, int rows) {
    HostMemoryBuffer data = HostMemoryBuffer.allocate((long) rows * BYTES_PER_ROW, false);
    data.setMemory(0, data.getLength(), (byte) 'X');
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate((rows + 1L) * (wide ? 8 : 4), false);
    for (int row = 0; row <= rows; row++) {
      long at = (long) row * BYTES_PER_ROW;
      if (wide) offsets.setLong(row * 8L, at);
      else offsets.setInt(row * 4L, Math.toIntExact(at));
    }
    return new HostColumnVector(DType.STRING, rows, Optional.of(0L), data, null, offsets,
        Collections.emptyList(), wide ? DType.INT64 : DType.INT32);
  }
  private static void assertDense(ContiguousTable table, int rows) {
    assertEquals(DType.INT64, table.getTable().getColumn(0).getOffsetsType());
    byte[] expected = new byte[BYTES_PER_ROW];
    Arrays.fill(expected, (byte) 'X');
    try (HostColumnVector host = table.getTable().getColumn(0).copyToHost()) {
      assertEquals(rows, host.getRowCount());
      assertEquals(DType.INT64, host.getOffsetsType());
      assertEquals((long) rows * BYTES_PER_ROW, host.getData().getLength());
      for (int row = 0; row < rows; row++) {
        assertEquals((long) row * BYTES_PER_ROW, host.getStartListOffset(row));
        assertEquals((row + 1L) * BYTES_PER_ROW, host.getEndListOffset(row));
        assertTrue(Arrays.equals(expected, host.getUTF8(row)), "Data mismatch in row " + row);
      }
    }
  }
  private static JCudfSerialization.HostConcatResult serialize(Path path, HostColumnVector input) throws Exception {
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path), BYTES_PER_ROW)) {
      JCudfSerialization.writeToStream(new HostColumnVector[]{input}, out, 0, input.getRowCount());
    }
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), BYTES_PER_ROW))) {
      JCudfSerialization.SerializedTableHeader header = new JCudfSerialization.SerializedTableHeader(in);
      HostMemoryBuffer buffer = HostMemoryBuffer.allocate(header.getDataLen(), false);
      try {
        JCudfSerialization.readTableIntoBuffer(in, header, buffer);
        return new JCudfSerialization.HostConcatResult(header, buffer);
      } catch (Throwable failure) {
        buffer.close();
        throw failure;
      }
    }
  }
  @Test void widePayloadOverTwoGiBStreamsAndRestoresOnGpu() throws Exception {
    Path path = Files.createTempFile("jcudf-wide-payload-", ".bin");
    try {
      try (HostColumnVector input = dense(true, 2049);
           JCudfSerialization.HostConcatResult packed = serialize(path, input);
           ContiguousTable table = packed.toContiguousTable()) {
        assertTrue(Files.size(path) > Integer.MAX_VALUE);
        assertTrue(packed.getTableHeader().getDataLen() > Integer.MAX_VALUE);
        assertDense(table, 2049);
      }
    } finally { Files.deleteIfExists(path); }
  }
  @Test void mergingTwoNarrowTablesPromotesOffsetsWhenCharsExceedTwoGiB() throws Exception {
    Path path = Files.createTempFile("jcudf-promote-offsets-", ".bin");
    try {
      try (HostColumnVector input = dense(false, 1025);
           JCudfSerialization.HostConcatResult packed = serialize(path, input);
           JCudfSerialization.HostConcatResult merged = JCudfSerialization.concatToHostBuffer(
               new JCudfSerialization.SerializedTableHeader[]{packed.getTableHeader(), packed.getTableHeader()},
               new HostMemoryBuffer[]{packed.getHostBuffer(), packed.getHostBuffer()});
           ContiguousTable table = merged.toContiguousTable()) {
        assertEquals(DType.INT32, packed.getTableHeader().getColumnHeader(0).getOffsetsType());
        assertEquals(DType.INT64, merged.getTableHeader().getColumnHeader(0).getOffsetsType());
        assertDense(table, 2050);
      }
    } finally { Files.deleteIfExists(path); }
  }
}
