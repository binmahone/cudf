/*
 * SPDX-FileCopyrightText: Copyright (c) 2026, NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.rapids.cudf;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LargeStringOffsetsJniTest {
  @AfterAll static void verifyJarNativeLibraries() throws IOException {
    StringOffsetsJniTest.verifyJarNativeLibraries();
  }

  private static HostColumnVector buildDenseStrings(int rows, int bytesPerRow) {
    byte[] value = new byte[bytesPerRow];
    Arrays.fill(value, (byte) 'X');
    try (HostColumnVector.Builder builder = HostColumnVector.builder(rows,
        rows * (long) bytesPerRow)) {
      for (int row = 0; row < rows; row++) builder.appendUTF8String(value);
      return builder.build();
    }
  }

  @Test void denseCharsBeyondTwoGiBRoundTrip() {
    int rows = 2049;
    long bytesPerRow = 1L << 20;
    long totalBytes = rows * bytesPerRow;
    try (HostColumnVector input = buildDenseStrings(rows, Math.toIntExact(bytesPerRow));
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
