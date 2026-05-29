/*
 *
 *  SPDX-FileCopyrightText: Copyright (c) 2019, NVIDIA CORPORATION.
 *  SPDX-License-Identifier: Apache-2.0
 *
 */

package ai.rapids.cudf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Options for reading a parquet file
 */
public class ParquetOptions extends ColumnFilterOptions {

  public static ParquetOptions DEFAULT = new ParquetOptions(new Builder());

  private final DType unit;
  private final boolean[] readBinaryAsString;
  private final int[] rowGroupIndices;

  private ParquetOptions(Builder builder) {
    super(builder);
    unit = builder.unit;
    readBinaryAsString = new boolean[builder.binaryAsStringColumns.size()];
    for (int i = 0 ; i < builder.binaryAsStringColumns.size() ; i++) {
      readBinaryAsString[i] = builder.binaryAsStringColumns.get(i);
    }
    rowGroupIndices = builder.rowGroupIndices;
  }

  DType timeUnit() {
    return unit;
  }

  boolean[] getReadBinaryAsString() {
    return readBinaryAsString;
  }

  /**
   * Indices of row groups to read for the (single-source) parquet input.
   * Returns null if no row group filter is set; in that case the reader
   * reads every row group in the file.
   *
   * Maps to cudf::io::parquet_reader_options::set_row_groups() for the
   * first (and only) source. Lets callers pre-filter row groups on host
   * (e.g. via the Spark file split byte range) instead of letting cuDF
   * read all row groups.
   */
  public int[] getRowGroupIndices() {
    return rowGroupIndices;
  }

  public static ParquetOptions.Builder builder() {
    return new Builder();
  }

  public static class Builder extends ColumnFilterOptions.Builder<Builder> {
    private DType unit = DType.EMPTY;
    final List<Boolean> binaryAsStringColumns = new ArrayList<>();
    int[] rowGroupIndices = null;

    /**
     * Specify the time unit to use when returning timestamps.
     * @param unit default unit of time specified by the user
     * @return builder for chaining
     */
    public Builder withTimeUnit(DType unit) {
      assert unit.isTimestampType();
      this.unit = unit;
      return this;
    }

    /**
     * Restrict the read to the given row-group indices (for the single
     * parquet source). Passing null (the default) reads every row group.
     *
     * Maps to cudf::io::parquet_reader_options::set_row_groups() with a
     * single-source vector. Indices are 0-based and must be in ascending
     * order within the file's row-group list; cuDF will throw if any
     * index is out of range.
     *
     * @param indices row-group indices to read, or null for no filter.
     * @return builder for chaining
     */
    public Builder withRowGroups(int[] indices) {
      this.rowGroupIndices = indices;
      return this;
    }

    /**
     * Include one or more specific columns.  Any column not included will not be read.
     * @param names the name of the column, or more than one if you want.
     */
    @Override
    public Builder includeColumn(String... names) {
      super.includeColumn(names);
      for (int i = 0 ; i < names.length ; i++) {
        binaryAsStringColumns.add(true);
      }
      return this;
    }

    /**
     * Include this column.
     * @param name the name of the column
     * @param isBinary whether this column is to be read in as binary
     */
    public Builder includeColumn(String name, boolean isBinary) {
      includeColumnNames.add(name);
      binaryAsStringColumns.add(!isBinary);
      return this;
    }

    /**
     * Include one or more specific columns.  Any column not included will not be read.
     * @param names the name of the column, or more than one if you want.
     */
    @Override
    public Builder includeColumn(Collection<String> names) {
      super.includeColumn(names);
      for (int i = 0 ; i < names.size() ; i++) {
        binaryAsStringColumns.add(true);
      }
      return this;
    }

    public ParquetOptions build() {
      return new ParquetOptions(this);
    }
  }
}
