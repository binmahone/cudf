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

class StringOffsetsJniTest {
  private static void assertStrings(HostColumnVectorCore host, String... expected) {
    assertEquals(expected.length, host.getRowCount());
    for (int i = 0; i < expected.length; i++) {
      if (expected[i] == null) assertTrue(host.isNull(i));
      else assertEquals(expected[i], host.getJavaString(i));
    }
  }

  private static void roundTrip(boolean wide, String... values) {
    try (HostColumnVector input = HostStringOffsetsTest.strings(wide, values);
         ColumnVector device = input.copyToDevice();
         HostColumnVector output = device.copyToHost()) {
      DType expected = wide && values.length != 0 ? DType.INT64 : DType.INT32;
      assertEquals(expected, device.getOffsetsType());
      assertEquals(expected, output.getOffsetsType());
      if (values.length != 0) {
        try (BaseDeviceMemoryBuffer offsets = device.getOffsets()) {
          assertEquals((values.length + 1L) * expected.getSizeInBytes(), offsets.getLength());
        }
        assertEquals((values.length + 1L) * expected.getSizeInBytes(), output.getOffsets().getLength());
      }
      assertStrings(output, values);
    }
  }

  @Test void legacyInt32RoundTrip() { roundTrip(false, "abc", null, "", "DE"); }
  @Test void explicitInt64RoundTrip() { roundTrip(true, "abc", null, "", "DE"); }
  @Test void explicitInt64Utf8RoundTrip() { roundTrip(true, "\u4f60\u597d", "\u00e9", ""); }
  @Test void explicitInt64AllNullRoundTrip() { roundTrip(true, null, null); }
  @Test void explicitInt64AllEmptyRoundTrip() { roundTrip(true, "", ""); }
  @Test void emptyColumnHasCanonicalInt32Offsets() { roundTrip(true); }

  @Test void listPreservesWideStringChild() {
    try (HostColumnVector input = HostStringOffsetsTest.list(
             HostStringOffsetsTest.strings(true, "abc", null, "DE"));
         ColumnVector device = input.copyToDevice();
         ColumnView child = device.getChildColumnView(0);
         HostColumnVector output = device.copyToHost()) {
      assertEquals(DType.INT32, device.getOffsetsType());
      assertEquals(DType.INT64, child.getOffsetsType());
      assertEquals(DType.INT64, output.getChildColumnView(0).getOffsetsType());
      assertEquals(Arrays.asList("abc", null, "DE"), output.getList(0));
    }
  }

  @Test void structPreservesMixedStringWidths() {
    try (HostColumnVector input = new HostColumnVector(DType.STRUCT, 2, Optional.of(0L),
             null, null, null, Arrays.asList(
                 HostStringOffsetsTest.strings(false, "a", "b"),
                 HostStringOffsetsTest.strings(true, "cd", null)));
         ColumnVector device = input.copyToDevice();
         ColumnView narrow = device.getChildColumnView(0);
         ColumnView wide = device.getChildColumnView(1);
         HostColumnVector output = device.copyToHost()) {
      assertEquals(DType.INT32, narrow.getOffsetsType());
      assertEquals(DType.INT64, wide.getOffsetsType());
      assertEquals(DType.INT32, output.getChildColumnView(0).getOffsetsType());
      assertEquals(DType.INT64, output.getChildColumnView(1).getOffsetsType());
      assertStrings(output.getChildColumnView(0), "a", "b");
      assertStrings(output.getChildColumnView(1), "cd", null);
    }
  }

  @Test void structOfListPreservesWideGrandchild() {
    try (HostColumnVector input = new HostColumnVector(DType.STRUCT, 1, Optional.of(0L),
             null, null, null, Collections.singletonList(HostStringOffsetsTest.list(
                 HostStringOffsetsTest.strings(true, "abc", null, "DE"))));
         ColumnVector device = input.copyToDevice();
         ColumnView list = device.getChildColumnView(0);
         ColumnView strings = list.getChildColumnView(0);
         HostColumnVector output = device.copyToHost()) {
      assertEquals(DType.INT32, list.getOffsetsType());
      assertEquals(DType.INT64, strings.getOffsetsType());
      HostColumnVectorCore restoredList = output.getChildColumnView(0);
      assertEquals(DType.INT64, restoredList.getChildColumnView(0).getOffsetsType());
      assertEquals(Arrays.asList("abc", null, "DE"), restoredList.getList(0));
    }
  }

  @Test void nestedEmptyStringChildRoundTrip() {
    try (HostColumnVector input = HostStringOffsetsTest.list(HostStringOffsetsTest.strings(true));
         ColumnVector device = input.copyToDevice();
         HostColumnVector output = device.copyToHost()) {
      assertEquals(Collections.emptyList(), output.getList(0));
      assertEquals(0, output.getChildColumnView(0).getRowCount());
    }
  }

  @Test void asyncHostCopyPreservesWidthAfterSynchronization() {
    try (HostColumnVector input = HostStringOffsetsTest.strings(true, "abc", "DE");
         ColumnVector device = input.copyToDevice();
         HostColumnVector output = device.copyToHostAsync(Cuda.DEFAULT_STREAM)) {
      Cuda.DEFAULT_STREAM.sync();
      assertEquals(DType.INT64, output.getOffsetsType());
      assertStrings(output, "abc", "DE");
    }
  }

  @Test void nonOwningViewUsesExplicitStringOffsetsType() {
    try (HostColumnVector host = HostStringOffsetsTest.strings(true, "abc", "DE");
         ColumnVector device = host.copyToDevice();
         BaseDeviceMemoryBuffer data = device.getData();
         BaseDeviceMemoryBuffer offsets = device.getOffsets();
         ColumnView view = new ColumnView(DType.STRING, 2, Optional.of(0L), data, null,
             offsets, DType.INT64);
         HostColumnVector restored = view.copyToHost()) {
      assertEquals(DType.INT64, view.getOffsetsType());
      assertStrings(restored, "abc", "DE");
    }
  }

  @Test void shortWideDeviceOffsetsAreRejectedBeforeNativeViewCreation() {
    try (DeviceMemoryBuffer offsets = DeviceMemoryBuffer.allocate(8)) {
      assertThrows(IllegalArgumentException.class, () -> new ColumnView(DType.STRING, 1,
          Optional.of(0L), null, null, offsets, DType.INT64));
      assertEquals(8, offsets.getLength());
    }
  }

  @Test void packedSplitPreservesWideOffsetsAndRebasesData() {
    try (HostColumnVector host = HostStringOffsetsTest.strings(true, "abc", null, "", "DE");
         ColumnVector device = host.copyToDevice();
         Table table = new Table(device)) {
      ContiguousTable[] parts = table.contiguousSplit(2);
      try {
        assertEquals(2, parts.length);
        for (int i = 0; i < parts.length; i++) {
          ColumnVector column = parts[i].getTable().getColumn(0);
          assertEquals(DType.INT64, column.getOffsetsType());
          try (HostColumnVector restored = column.copyToHost()) {
            assertEquals(DType.INT64, restored.getOffsetsType());
            if (i == 0) assertStrings(restored, "abc", null);
            else assertStrings(restored, "", "DE");
          }
        }
      } finally {
        for (ContiguousTable part : parts) part.close();
      }
    }
  }

  @Test void nativeConcatenateSelectsWidthFromTotalBytes() {
    String value = "abcdefghijklmnopqrstuvwx";
    try (HostColumnVector host = HostStringOffsetsTest.strings(false, value);
         ColumnVector input = host.copyToDevice();
         ColumnVector output = ColumnVector.concatenate(input, input);
         HostColumnVector restored = output.copyToHost()) {
      boolean enabled = "1".equals(System.getenv("LIBCUDF_LARGE_STRINGS_ENABLED"));
      String thresholdEnv = System.getenv("LIBCUDF_LARGE_STRINGS_THRESHOLD");
      long threshold = thresholdEnv == null ? Integer.MAX_VALUE : Long.parseLong(thresholdEnv);
      DType expected = enabled && value.length() * 2L > threshold ? DType.INT64 : DType.INT32;
      assertEquals(DType.INT32, input.getOffsetsType());
      assertEquals(expected, output.getOffsetsType());
      assertEquals(expected, restored.getOffsetsType());
      assertStrings(restored, value, value);
    }
  }
}
