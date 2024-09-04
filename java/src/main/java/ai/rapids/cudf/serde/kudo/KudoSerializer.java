package ai.rapids.cudf.serde.kudo;

import ai.rapids.cudf.*;
import ai.rapids.cudf.schema.Visitors;
import ai.rapids.cudf.serde.TableSerializer;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class KudoSerializer implements TableSerializer {


    private static final byte[] PADDING = new byte[64];

    static {
        Arrays.fill(PADDING, (byte) 0);
    }

    @Override
    public String version() {
        return "MultiTableSerializer-v2";
    }

    @Override
    public long writeToStream(HostColumnVector[] columns, OutputStream out, long rowOffset, long numRows) {
        if (numRows < 0) {
            throw new IllegalArgumentException("numRows must be >= 0");
        }

        if (numRows == 0 || columns.length == 0) {
            return 0;
        }

        try {
            return writeSliced(columns, writerFrom(out), rowOffset, numRows);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long writeRowsToStream(OutputStream out, long numRows) {
        if (numRows <= 0) {
            throw new IllegalArgumentException("Number of rows must be > 0, but was " + numRows);
        }
        try {
            DataWriter writer = writerFrom(out);
            SerializedTableHeader header = new SerializedTableHeader(0, (int) numRows, 0, 0, 0, new byte[0]);
            header.writeTo(writer);
            writer.flush();
            return header.getSerializedSize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object readOneTableBuffer(InputStream in) {
        Objects.requireNonNull(in, "Input stream must not be null");

        try {
            DataInputStream din = readerFrom(in);
            SerializedTableHeader header = new SerializedTableHeader(din);
            if (!header.wasInitialized()) {
                return null;
            }

            if (header.getNumRows() <= 0) {
                throw new IllegalArgumentException("Number of rows must be > 0, but was " + header.getNumRows());
            }

            // Header only
            if (header.getNumColumns() == 0) {
                return new SerializedTable(header, null);
            }

            HostMemoryBuffer buffer = HostMemoryBuffer.allocate(header.getTotalDataLen());
            buffer.copyFromStream(0, din, header.getTotalDataLen());
            return new SerializedTable(header, buffer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<HostColumnVector> mergeToHost(List<Object> buffers, Schema schema) {
        List<SerializedTable> serializedTables = buffers
                .stream()
                .map(o -> (SerializedTable) o)
                .collect(Collectors.toList());

        try (MultiTableDeserializer deserializer = new MultiTableDeserializer(serializedTables)) {
            return Visitors.visitSchema(schema, deserializer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Table mergeTable(List<Object> buffers, Schema schema) {
        List<HostColumnVector> children = mergeToHost(buffers, schema);
        return HostColumnVector.toTable(children);
    }

    private static long writeSliced(HostColumnVector[] columns, DataWriter out, long rowOffset, long numRows) throws Exception {
        List<HostColumnVector> columnList = Arrays.stream(columns).collect(Collectors.toList());

        Schema.Builder schemaBuilder = Schema.builder();
        for (int i = 0; i < columns.length; i++) {
            columns[i].toSchema("col_" + i + "_", schemaBuilder);
        }
        Schema schema = schemaBuilder.build();


        SerializedTableHeaderCalc headerCalc = new SerializedTableHeaderCalc(rowOffset, numRows);
        SerializedTableHeader header = Visitors.visitSchemaWithColumns(schema, columnList, headerCalc);
        header.writeTo(out);

        long bytesWritten = 0;
        for (BufferType bufferType : Arrays.asList(BufferType.VALIDITY, BufferType.OFFSET, BufferType.DATA)) {
            bytesWritten += Visitors.visitSchemaWithColumns(schema, columnList, new SlicedBufferSerializer(rowOffset, numRows, bufferType, out));
        }

        if (bytesWritten != header.getTotalDataLen()) {
            throw new IllegalStateException("Header total data length: " + header.getTotalDataLen() + " does not match actual written data length: " + bytesWritten);
        }

        out.flush();

        return header.getSerializedSize() + bytesWritten;
    }

    private static DataInputStream readerFrom(InputStream in) {
        if (!(in instanceof DataInputStream)) {
            in = new DataInputStream(in);
        }
        return new DataInputStream(in);
    }

    private static DataWriter writerFrom(OutputStream out) {
        if (!(out instanceof DataOutputStream)) {
            out = new DataOutputStream(new BufferedOutputStream(out));
        }
        return new DataOutputStreamWriter((DataOutputStream) out);
    }


    /////////////////////////////////////////////
// METHODS
/////////////////////////////////////////////


    /////////////////////////////////////////////
// PADDING FOR ALIGNMENT
/////////////////////////////////////////////
    static long padFor64byteAlignment(long orig) {
        return ((orig + 63) / 64) * 64;
    }

    static long padFor64byteAlignment(DataWriter out, long bytes) throws IOException {
        final long paddedBytes = padFor64byteAlignment(bytes);
        if (paddedBytes > bytes) {
            out.write(PADDING, 0, (int) (paddedBytes - bytes));
        }
        return paddedBytes;
    }

}