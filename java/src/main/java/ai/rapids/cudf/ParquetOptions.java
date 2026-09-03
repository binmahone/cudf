/*
 *
 *  SPDX-FileCopyrightText: Copyright (c) 2019-2026, NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 *  SPDX-License-Identifier: Apache-2.0
 *
 */

package ai.rapids.cudf;

import ai.rapids.cudf.ast.CompiledExpression;

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
  private final CompiledExpression filter;

  private ParquetOptions(Builder builder) {
    super(builder);
    unit = builder.unit;
    readBinaryAsString = new boolean[builder.binaryAsStringColumns.size()];
    for (int i = 0 ; i < builder.binaryAsStringColumns.size() ; i++) {
      readBinaryAsString[i] = builder.binaryAsStringColumns.get(i);
    }
    rowGroupIndices = builder.rowGroupIndices;
    filter = builder.filter;
  }

  DType timeUnit() {
    return unit;
  }

  boolean[] getReadBinaryAsString() {
    return readBinaryAsString;
  }

  /**
   * Return the selected Parquet row-group indices, or null when all row groups are selected.
   */
  public int[] getRowGroupIndices() {
    return rowGroupIndices;
  }

  CompiledExpression getFilter() {
    return filter;
  }

  public static ParquetOptions.Builder builder() {
    return new Builder();
  }

  public static class Builder extends ColumnFilterOptions.Builder<Builder> {
    private DType unit = DType.EMPTY;
    final List<Boolean> binaryAsStringColumns = new ArrayList<>();
    int[] rowGroupIndices = null;
    CompiledExpression filter = null;

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
     * Restrict a single-source Parquet read to the given zero-based row-group indices.
     *
     * @param indices row-group indices in ascending order, or null to read all row groups
     * @return builder for chaining
     */
    public Builder withRowGroups(int[] indices) {
      this.rowGroupIndices = indices;
      return this;
    }

    /**
     * Set an AST filter for predicate pushdown and row filtering during the Parquet read.
     * The caller owns the expression and must keep it open until the reader is closed.
     *
     * @param filter compiled filter expression, or null for no filter
     * @return builder for chaining
     */
    public Builder withFilter(CompiledExpression filter) {
      this.filter = filter;
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
