/*
 * SPDX-FileCopyrightText: Copyright (c) 2026, NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.rapids.cudf;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JCudfStringOffsetsGpuTest {
  private static void assertStrings(HostColumnVector host, String... values) {
    assertEquals(values.length, host.getRowCount());
    for (int row = 0; row < values.length; row++) {
      if (values[row] == null) assertTrue(host.isNull(row));
      else assertEquals(values[row], host.getJavaString(row));
    }
  }
  @Test void wideSerializationRestoresOnGpu() throws Exception {
    try (HostColumnVector input = HostStringOffsetsTest.strings(true, "abc", null, "", "\u00e9");
         JCudfSerialization.HostConcatResult packed = HostStringOffsetsTest.serialized(0, 4, input);
         ContiguousTable table = packed.toContiguousTable();
         HostColumnVector restored = table.getTable().getColumn(0).copyToHost()) {
      assertEquals(DType.INT64, table.getTable().getColumn(0).getOffsetsType());
      assertEquals(DType.INT64, restored.getOffsetsType());
      assertStrings(restored, "abc", null, "", "\u00e9");
    }
  }
  @Test void mixedWidthsConcatenateAndRestoreOnGpu() throws Exception {
    try (HostColumnVector a = HostStringOffsetsTest.strings(false, "abc", null);
         HostColumnVector b = HostStringOffsetsTest.strings(true, "DE", "");
         JCudfSerialization.HostConcatResult first = HostStringOffsetsTest.serialized(0, 2, a);
         JCudfSerialization.HostConcatResult second = HostStringOffsetsTest.serialized(0, 2, b);
         JCudfSerialization.HostConcatResult merged = JCudfSerialization.concatToHostBuffer(
             new JCudfSerialization.SerializedTableHeader[]{first.getTableHeader(), second.getTableHeader()},
             new HostMemoryBuffer[]{first.getHostBuffer(), second.getHostBuffer()});
         ContiguousTable table = merged.toContiguousTable();
         HostColumnVector restored = table.getTable().getColumn(0).copyToHost()) {
      assertEquals(DType.INT64, restored.getOffsetsType());
      assertStrings(restored, "abc", null, "DE", "");
    }
  }
  @Test void ordinarySerializationKeepsInt32ThroughGpuRestore() throws Exception {
    try (HostColumnVector input = HostColumnVector.fromStrings("abc", null, "DE");
         JCudfSerialization.HostConcatResult packed = HostStringOffsetsTest.serialized(0, 3, input);
         ContiguousTable table = packed.toContiguousTable();
         HostColumnVector restored = table.getTable().getColumn(0).copyToHost()) {
      assertEquals(DType.INT32, restored.getOffsetsType());
      assertStrings(restored, "abc", null, "DE");
    }
  }
  @Test void listWideChildRestoresOnGpu() throws Exception {
    try (HostColumnVector input = HostStringOffsetsTest.list(HostStringOffsetsTest.strings(true, "abc", null, "DE"));
         JCudfSerialization.HostConcatResult packed = HostStringOffsetsTest.serialized(0, 1, input);
         ContiguousTable table = packed.toContiguousTable();
         HostColumnVector restored = table.getTable().getColumn(0).copyToHost()) {
      assertEquals(DType.INT32, restored.getOffsetsType());
      assertEquals(DType.INT64, restored.getChildColumnView(0).getOffsetsType());
      assertEquals(Arrays.asList("abc", null, "DE"), restored.getList(0));
    }
  }
  @Test void unpackedEmptyWideChildCanBeUploadedToGpu() throws Exception {
    try (HostColumnVector input = HostStringOffsetsTest.list(HostStringOffsetsTest.strings(true));
         JCudfSerialization.HostConcatResult packed = HostStringOffsetsTest.serialized(0, 1, input)) {
      HostColumnVector[] columns = JCudfSerialization.unpackHostColumnVectors(packed.getTableHeader(), packed.getHostBuffer());
      try (HostColumnVector host = columns[0];
           ColumnVector device = host.copyToDevice();
           HostColumnVector restored = device.copyToHost()) {
        assertEquals(Collections.emptyList(), restored.getList(0));
        assertEquals(DType.INT32, restored.getChildColumnView(0).getOffsetsType());
      }
    }
  }
  @Test void zeroRowsRestoreAsCanonicalEmptyGpuColumn() throws Exception {
    try (HostColumnVector input = HostStringOffsetsTest.strings(true);
         JCudfSerialization.HostConcatResult packed = HostStringOffsetsTest.serialized(0, 0, input);
         ContiguousTable table = packed.toContiguousTable();
         HostColumnVector restored = table.getTable().getColumn(0).copyToHost()) {
      assertEquals(0, restored.getRowCount());
      assertEquals(DType.INT32, restored.getOffsetsType());
    }
  }
}
