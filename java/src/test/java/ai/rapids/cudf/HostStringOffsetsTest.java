/*
 * SPDX-FileCopyrightText: Copyright (c) 2026, NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.rapids.cudf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostStringOffsetsTest {
  static HostColumnVector strings(boolean wide, String... values) {
    int bytes = 0;
    long nulls = 0;
    for (String value : values) {
      if (value == null) nulls++;
      else bytes += value.getBytes(StandardCharsets.UTF_8).length;
    }
    HostMemoryBuffer data = HostMemoryBuffer.allocate(Math.max(bytes, 1), false);
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate((values.length + 1L) * (wide ? 8 : 4), false);
    HostMemoryBuffer valid = nulls == 0 ? null : HostMemoryBuffer.allocate(64, false);
    if (valid != null) valid.setMemory(0, 64, (byte) 0);
    int end = 0;
    for (int i = 0; i <= values.length; i++) {
      if (wide) offsets.setLong(i * 8L, end);
      else offsets.setInt(i * 4L, end);
      if (i < values.length && values[i] != null) {
        byte[] value = values[i].getBytes(StandardCharsets.UTF_8);
        data.setBytes(end, value, 0, value.length);
        end += value.length;
        if (valid != null) {
          long index = i / 8;
          valid.setByte(index, (byte) (valid.getByte(index) | (1 << (i % 8))));
        }
      }
    }
    return new HostColumnVector(DType.STRING, values.length, Optional.of(nulls), data,
        valid, offsets, Collections.emptyList(), wide ? DType.INT64 : DType.INT32);
  }

  static HostColumnVector list(HostColumnVector child) {
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate(8, false);
    offsets.setInt(0, 0);
    offsets.setInt(4, Math.toIntExact(child.getRowCount()));
    return new HostColumnVector(DType.LIST, 1, Optional.of(0L), null, null,
        offsets, Collections.singletonList(child));
  }

  @Test void defaultBuilderKeepsInt32AndNulls() {
    try (HostColumnVector host = HostColumnVector.fromStrings("abc", null, "", "DE")) {
      assertEquals(DType.INT32, host.getOffsetsType());
      assertEquals(20, host.getOffsets().getLength());
      assertEquals("abc", host.getJavaString(0));
      assertTrue(host.isNull(1));
      assertEquals("", host.getJavaString(2));
      assertEquals("DE", host.getJavaString(3));
      assertDoesNotThrow(() -> host.require32BitOffsets("legacy"));
    }
  }

  @Test void explicitInt64ReadsEveryRow() {
    try (HostColumnVector host = strings(true, "abc", "DE", "fghi")) {
      assertEquals(DType.INT64, host.getOffsetsType());
      assertEquals(32, host.getOffsets().getLength());
      assertEquals("abc", host.getJavaString(0));
      assertEquals("DE", host.getJavaString(1));
      assertEquals("fghi", host.getJavaString(2));
      assertEquals(5, host.getStartListOffset(2));
      assertEquals(9, host.getEndListOffset(2));
    }
  }

  @Test void paddedOffsetsBufferDoesNotChangeDefaultInt32Type() {
    HostMemoryBuffer data = HostMemoryBuffer.allocate(9, false);
    data.setBytes(0, "abcDEfghi".getBytes(StandardCharsets.UTF_8), 0, 9);
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate(64, false);
    int[] ends = {0, 3, 5, 9};
    for (int i = 0; i < ends.length; i++) offsets.setInt(i * 4L, ends[i]);
    try (HostColumnVector host = new HostColumnVector(DType.STRING, 3, Optional.of(0L),
        data, null, offsets)) {
      assertEquals(DType.INT32, host.getOffsetsType());
      assertEquals("abc", host.getJavaString(0));
      assertEquals("DE", host.getJavaString(1));
      assertEquals("fghi", host.getJavaString(2));
    }
  }

  @Test void wideUtf8NullAndEmptyStrings() {
    try (HostColumnVector host = strings(true, "\u4f60\u597d", "", null, "\u00e9")) {
      assertArrayEquals("\u4f60\u597d".getBytes(StandardCharsets.UTF_8), host.getUTF8(0));
      assertEquals(6, host.getEndListOffset(0));
      assertEquals("", host.getJavaString(1));
      assertTrue(host.isNull(2));
      assertEquals("\u00e9", host.getJavaString(3));
    }
  }

  @Test void allNullAndEmptyWideColumns() {
    try (HostColumnVector allNull = strings(true, null, null);
         HostColumnVector empty = strings(true)) {
      assertTrue(allNull.isNull(0));
      assertTrue(allNull.isNull(1));
      assertEquals(0, allNull.getEndListOffset(1));
      assertEquals(0, empty.getRowCount());
      assertEquals(DType.INT64, empty.getOffsetsType());
    }
  }

  @Test void sparseHostAddressBeyondInt32ReadsWithoutTruncation() {
    // Reserve virtual address space; touch only the offsets and three data bytes.
    long start = (1L << 31) + 17;
    HostMemoryBuffer data = HostMemoryBuffer.allocate(start + 3, false);
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate(16, false);
    byte[] expected = "XYZ".getBytes(StandardCharsets.UTF_8);
    data.setBytes(start, expected, 0, expected.length);
    offsets.setLong(0, start);
    offsets.setLong(8, start + 3);
    try (HostColumnVector host = new HostColumnVector(DType.STRING, 1, Optional.of(0L),
        data, null, offsets, Collections.emptyList(), DType.INT64)) {
      assertEquals(start, host.getStartListOffset(0));
      assertArrayEquals(expected, host.getUTF8(0));
      assertEquals("XYZ", host.getJavaString(0));
    }
  }

  @Test void listOffsetsStayInt32WhileStringChildIsInt64() {
    try (HostColumnVector parent = list(strings(true, "abc", null, "DE"))) {
      assertEquals(DType.INT32, parent.getOffsetsType());
      assertEquals(DType.INT64, parent.getChildColumnView(0).getOffsetsType());
      assertEquals(Arrays.asList("abc", null, "DE"), parent.getList(0));
    }
  }

  @Test void wideListOffsetsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new HostColumnVectorCore(
        DType.LIST, 0, Optional.of(0L), null, null, null, Collections.emptyList(), DType.INT64));
  }

  @Test void invalidWidthAndShortBufferAreRejectedWithoutTakingOwnership() {
    assertThrows(IllegalArgumentException.class, () -> new HostColumnVectorCore(
        DType.STRING, 0, Optional.of(0L), null, null, null, Collections.emptyList(), DType.INT16));
    try (HostMemoryBuffer offsets = HostMemoryBuffer.allocate(8, false)) {
      assertThrows(IllegalArgumentException.class, () -> new HostColumnVectorCore(
          DType.STRING, 1, Optional.of(0L), null, null, offsets, Collections.emptyList(), DType.INT64));
      offsets.setLong(0, 19);
      assertEquals(19, offsets.getLong(0));
    }
  }

  @Test void invalidOffsetOrderIsRejected() {
    try (HostColumnVector host = strings(true, "abc")) {
      host.getOffsets().setLong(0, 3);
      host.getOffsets().setLong(8, 2);
      assertThrows(IllegalStateException.class, () -> host.getUTF8(0));
    }
  }

  @Test void individualStringTooLargeForJavaArrayIsRejectedBeforeAllocation() {
    try (HostColumnVector host = strings(true, "abc")) {
      host.getOffsets().setLong(8, (1L << 31));
      assertThrows(ArithmeticException.class, () -> host.getUTF8(0));
    }
  }

  @Test void legacyInt32SerializationRoundTripPreservesValues() throws Exception {
    try (HostColumnVector input = HostColumnVector.fromStrings("abc", null, "", "DE")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(new HostColumnVector[]{input}, output, 0, 4);
      DataInputStream stream = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
      JCudfSerialization.SerializedTableHeader header = new JCudfSerialization.SerializedTableHeader(stream);
      assertEquals(4, header.getNumRows());
      try (HostMemoryBuffer buffer = HostMemoryBuffer.allocate(header.getDataLen(), false)) {
        JCudfSerialization.readTableIntoBuffer(stream, header, buffer);
        HostColumnVector[] unpacked = JCudfSerialization.unpackHostColumnVectors(header, buffer);
        try (HostColumnVector restored = unpacked[0]) {
          assertEquals(DType.INT32, restored.getOffsetsType());
          assertEquals("abc", restored.getJavaString(0));
          assertTrue(restored.isNull(1));
          assertEquals("", restored.getJavaString(2));
          assertEquals("DE", restored.getJavaString(3));
        }
      }
    }
  }

  private static HostColumnVector[] serializeAndUnpack(long start, long rows,
                                                       HostColumnVector... columns) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JCudfSerialization.writeToStream(columns, out, start, rows);
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    JCudfSerialization.SerializedTableHeader header = new JCudfSerialization.SerializedTableHeader(input);
    try (HostMemoryBuffer buffer = HostMemoryBuffer.allocate(header.getDataLen(), false)) {
      JCudfSerialization.readTableIntoBuffer(input, header, buffer);
      return JCudfSerialization.unpackHostColumnVectors(header, buffer);
    }
  }

  @Test void mixedColumnsSerializeTheirDeclaredWidthsWithoutClosingInputs() throws Exception {
    try (HostColumnVector narrow = strings(false, "first");
         HostColumnVector wide = strings(true, "second")) {
      HostColumnVector[] restored = serializeAndUnpack(0, 1, narrow, wide);
      try (HostColumnVector first = restored[0]; HostColumnVector second = restored[1]) {
        assertEquals(DType.INT32, first.getOffsetsType());
        assertEquals(DType.INT64, second.getOffsetsType());
        assertEquals("first", first.getJavaString(0));
        assertEquals("second", second.getJavaString(0));
      }
      assertEquals(1, narrow.getRefCount());
      assertEquals(1, wide.getRefCount());
    }
  }

  @Test void nestedWideSerializationPreservesParentAndChildWidths() throws Exception {
    try (HostColumnVector parent = list(strings(true, "abc", null, "DE"))) {
      HostColumnVector[] restored = serializeAndUnpack(0, 1, parent);
      try (HostColumnVector result = restored[0]) {
        assertEquals(DType.INT32, result.getOffsetsType());
        assertEquals(DType.INT64, result.getChildColumnView(0).getOffsetsType());
        assertEquals(Arrays.asList("abc", null, "DE"), result.getList(0));
      }
    }
  }

  @Test void wideSerializationSliceRebasesOffsetsAndNulls() throws Exception {
    try (HostColumnVector input = strings(true, "prefix", "abc", null, "", "\u00e9", "suffix")) {
      HostColumnVector[] restored = serializeAndUnpack(1, 4, input);
      try (HostColumnVector result = restored[0]) {
        assertEquals(DType.INT64, result.getOffsetsType());
        assertEquals(0, result.getStartListOffset(0));
        assertEquals("abc", result.getJavaString(0));
        assertTrue(result.isNull(1));
        assertEquals("", result.getJavaString(2));
        assertEquals("\u00e9", result.getJavaString(3));
      }
    }
  }

  @Test void serializedSourceBytePositionBeyondInt32IsRebased() throws Exception {
    long start = (1L << 31) + 17;
    HostMemoryBuffer data = HostMemoryBuffer.allocate(start + 3, false);
    data.setBytes(start, "XYZ".getBytes(StandardCharsets.UTF_8), 0, 3);
    HostMemoryBuffer offsets = HostMemoryBuffer.allocate(16, false);
    offsets.setLong(0, start);
    offsets.setLong(8, start + 3);
    try (HostColumnVector input = new HostColumnVector(DType.STRING, 1, Optional.of(0L),
             data, null, offsets, Collections.emptyList(), DType.INT64)) {
      HostColumnVector[] restored = serializeAndUnpack(0, 1, input);
      try (HostColumnVector result = restored[0]) {
        assertEquals(DType.INT64, result.getOffsetsType());
        assertEquals(0, result.getStartListOffset(0));
        assertEquals("XYZ", result.getJavaString(0));
      }
    }
  }

  @Test void wideZeroRowsAndNestedEmptyChildCanBeUnpacked() throws Exception {
    try (HostColumnVector empty = strings(true)) {
      HostColumnVector[] restored = serializeAndUnpack(0, 0, empty);
      try (HostColumnVector result = restored[0]) {
        assertEquals(0, result.getRowCount());
        assertEquals(DType.INT64, result.getOffsetsType());
      }
    }
    try (HostColumnVector parent = list(strings(true))) {
      HostColumnVector[] restored = serializeAndUnpack(0, 1, parent);
      try (HostColumnVector result = restored[0]) {
        assertEquals(Collections.emptyList(), result.getList(0));
        assertEquals(DType.INT64, result.getChildColumnView(0).getOffsetsType());
      }
    }
  }

  @Test void versionsDistinguishOrdinaryAndWideStringTables() throws Exception {
    for (boolean wide : new boolean[]{false, true}) {
      try (HostColumnVector column = strings(wide, "abc")) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JCudfSerialization.writeToStream(new HostColumnVector[]{column}, out, 0, 1);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(0x43554446, in.readInt());
        assertEquals(wide ? 1 : 0, in.readShort());
      }
    }
  }

  static JCudfSerialization.HostConcatResult serialized(long start, long rows,
                                                        HostColumnVector... columns) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JCudfSerialization.writeToStream(columns, out, start, rows);
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    JCudfSerialization.SerializedTableHeader header = new JCudfSerialization.SerializedTableHeader(input);
    HostMemoryBuffer buffer = HostMemoryBuffer.allocate(header.getDataLen(), false);
    try {
      JCudfSerialization.readTableIntoBuffer(input, header, buffer);
      return new JCudfSerialization.HostConcatResult(header, buffer);
    } catch (Throwable failure) {
      buffer.close();
      throw failure;
    }
  }

  @Test void mixedWidthsConcatenateAndRestoreAsWideStrings() throws Exception {
    try (HostColumnVector narrow = strings(false, "first", null);
         HostColumnVector wide = strings(true, "second", "");
         JCudfSerialization.HostConcatResult a = serialized(0, 2, narrow);
         JCudfSerialization.HostConcatResult b = serialized(0, 2, wide);
         JCudfSerialization.HostConcatResult merged = JCudfSerialization.concatToHostBuffer(
             new JCudfSerialization.SerializedTableHeader[]{a.getTableHeader(), b.getTableHeader()},
             new HostMemoryBuffer[]{a.getHostBuffer(), b.getHostBuffer()})) {
      HostColumnVector[] columns = JCudfSerialization.unpackHostColumnVectors(
          merged.getTableHeader(), merged.getHostBuffer());
      try (HostColumnVector result = columns[0]) {
        assertEquals(DType.INT64, result.getOffsetsType());
        assertEquals("first", result.getJavaString(0));
        assertTrue(result.isNull(1));
        assertEquals("second", result.getJavaString(2));
        assertEquals("", result.getJavaString(3));
        assertEquals(11, result.getEndListOffset(3));
      }
    }
  }

  @Test void narrowConcatenationKeepsNarrowOffsets() throws Exception {
    try (HostColumnVector input = strings(false, "abc", null);
         JCudfSerialization.HostConcatResult a = serialized(0, 2, input);
         JCudfSerialization.HostConcatResult merged = JCudfSerialization.concatToHostBuffer(
             new JCudfSerialization.SerializedTableHeader[]{a.getTableHeader(), a.getTableHeader()},
             new HostMemoryBuffer[]{a.getHostBuffer(), a.getHostBuffer()})) {
      HostColumnVector[] columns = JCudfSerialization.unpackHostColumnVectors(
          merged.getTableHeader(), merged.getHostBuffer());
      try (HostColumnVector result = columns[0]) {
        assertEquals(DType.INT32, result.getOffsetsType());
        assertEquals("abc", result.getJavaString(0));
        assertTrue(result.isNull(1));
        assertEquals("abc", result.getJavaString(2));
        assertTrue(result.isNull(3));
      }
    }
  }

  @Test void structWithFixedAndWideChildrenPreservesValues() throws Exception {
    try (HostColumnVector input = new HostColumnVector(DType.STRUCT, 2, Optional.of(0L),
             null, null, null, Arrays.asList(HostColumnVector.fromInts(17, 23),
                 strings(true, "abc", null)))) {
      HostColumnVector[] columns = serializeAndUnpack(0, 2, input);
      try (HostColumnVector result = columns[0]) {
        assertEquals(17, result.getChildColumnView(0).getInt(0));
        assertEquals(23, result.getChildColumnView(0).getInt(1));
        assertEquals(DType.INT64, result.getChildColumnView(1).getOffsetsType());
        assertEquals("abc", result.getChildColumnView(1).getJavaString(0));
        assertTrue(result.getChildColumnView(1).isNull(1));
      }
    }
  }
}
