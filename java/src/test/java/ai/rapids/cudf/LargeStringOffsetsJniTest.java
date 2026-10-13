/*
 * SPDX-FileCopyrightText: Copyright (c) 2026, NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.rapids.cudf;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LargeStringOffsetsJniTest {
  @Test void denseCharsBeyondTwoGiBRoundTrip() {
    int rows = 2049;
    long bytesPerRow = 1L << 20;
    long totalBytes = rows * bytesPerRow;
    HostMemoryBuffer data = HostMemoryBuffer.allocate(totalBytes, false);
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate((rows + 1L) * Long.BYTES, false);
    data.setMemory(0, totalBytes, (byte) 'X');
    for (int row = 0; row <= rows; row++) offsets.setLong(row * 8L, row * bytesPerRow);
    try (HostColumnVector input = new HostColumnVector(DType.STRING, rows, Optional.of(0L),
             data, null, offsets, Collections.emptyList(), DType.INT64);
         ColumnVector device = input.copyToDevice();
         BaseDeviceMemoryBuffer deviceData = device.getData();
         HostColumnVector output = device.copyToHost()) {
      assertTrue(totalBytes > Integer.MAX_VALUE);
      assertEquals(DType.INT64, device.getOffsetsType());
      assertEquals(totalBytes, deviceData.getLength());
      assertEquals(totalBytes, output.getData().getLength());
      assertEquals(DType.INT64, output.getOffsetsType());
      byte[] expected = new byte[Math.toIntExact(bytesPerRow)];
      Arrays.fill(expected, (byte) 'X');
      for (int row = 0; row < rows; row++) {
        assertEquals(row * bytesPerRow, output.getStartListOffset(row));
        assertEquals((row + 1L) * bytesPerRow, output.getEndListOffset(row));
        byte[] restored = output.getUTF8(row);
        assertEquals(bytesPerRow, restored.length);
        assertTrue(Arrays.equals(expected, restored), "Data mismatch in row " + row);
      }
    }
  }
}
