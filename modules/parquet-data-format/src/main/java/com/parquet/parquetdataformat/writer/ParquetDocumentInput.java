package com.parquet.parquetdataformat.writer;

import org.opensearch.index.engine.exec.DocumentInput;
import org.opensearch.index.engine.exec.WriteResult;
import org.opensearch.index.mapper.MappedFieldType;
import com.parquet.parquetdataformat.vsr.ManagedVSR;
import com.parquet.parquetdataformat.converter.ParquetFieldType;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.parquet.parquetdataformat.converter.FieldTypeConverter.convertToParquetFieldType;

/**
 * Document input wrapper for Parquet-based document processing.
 *
 * <p>This class serves as an adapter between OpenSearch's DocumentInput interface
 * and the Arrow-based vector representation. It works directly with a {@link ManagedVSR}
 * to populate field vectors and manage document lifecycle.
 *
 * <p>The implementation follows the builder pattern, allowing incremental construction
 * of documents through field addition before finalizing the document for writing.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Direct field vector population using OpenSearch's {@link MappedFieldType}</li>
 *   <li>Document lifecycle management via ManagedVSR</li>
 *   <li>Integration with the Arrow-based Parquet writer pipeline</li>
 * </ul>
 *
 * <p>This implementation works directly with Arrow field vectors, eliminating the
 * intermediate ParquetDocument representation for improved performance and memory efficiency.
 */
public class ParquetDocumentInput implements DocumentInput<ManagedVSR> {
    private final ManagedVSR managedVSR;

    public ParquetDocumentInput(ManagedVSR managedVSR) {
        this.managedVSR = managedVSR;
    }

    @Override
    public void addField(MappedFieldType fieldType, Object value) {
        // Convert OpenSearch field type to Parquet field type
        ParquetFieldType parquetFieldType = convertToParquetFieldType(fieldType);

        // Get the appropriate field vector from the ManagedVSR
        FieldVector fieldVector = managedVSR.getVector(parquetFieldType.getName());

        if (fieldVector != null) {
            // Get current row position and populate the vector
            int currentRow = managedVSR.getRowCount();
            ArrowType type = parquetFieldType.getType();

            // Populate the field vector based on Arrow type
            switch (type) {
                case ArrowType.Int intType -> {
                    if (intType.getBitWidth() == 32) {
                        IntVector intVector = (IntVector) fieldVector;
                        if (value == null) {
                            intVector.setNull(currentRow);
                        } else {
                            intVector.setSafe(currentRow, (Integer) value);
                        }
                    } else if (intType.getBitWidth() == 64) {
                        BigIntVector bigIntVector = (BigIntVector) fieldVector;
                        if (value == null) {
                            bigIntVector.setNull(currentRow);
                        } else {
                            bigIntVector.setSafe(currentRow, (Long) value);
                        }
                    }
                }
                case ArrowType.FloatingPoint floatType -> {
                    switch (floatType.getPrecision()) {
                        case SINGLE -> {
                            Float4Vector floatVector = (Float4Vector) fieldVector;
                            if (value == null) {
                                floatVector.setNull(currentRow);
                            } else {
                                floatVector.setSafe(currentRow, (Float) value);
                            }
                        }
                        case DOUBLE -> {
                            Float8Vector doubleVector = (Float8Vector) fieldVector;
                            if (value == null) {
                                doubleVector.setNull(currentRow);
                            } else {
                                doubleVector.setSafe(currentRow, (Double) value);
                            }
                        }
                    }
                }
                case ArrowType.Utf8 utf8 -> {
                    VarCharVector strVector = (VarCharVector) fieldVector;
                    if (value == null) {
                        strVector.setNull(currentRow);
                    } else {
                        byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                        strVector.setSafe(currentRow, bytes);
                    }
                }
                case ArrowType.Bool bool -> {
                    BitVector bitVector = (BitVector) fieldVector;
                    if (value == null) {
                        bitVector.setNull(currentRow);
                    } else {
                        bitVector.setSafe(currentRow, ((Boolean) value) ? 1 : 0);
                    }
                }
                case null, default -> throw new UnsupportedOperationException("Unsupported ArrowType: " + type);
            }
        }
    }

    @Override
    public ManagedVSR getFinalInput() {
        return managedVSR;
    }

    @Override
    public WriteResult addToWriter() throws IOException {
        // Complete the current document by incrementing row count
        // This will internally call setValueCount on all field vectors
        int currentRowCount = managedVSR.getRowCount();
        managedVSR.setRowCount(currentRowCount + 1);

        // TODO: Return appropriate WriteResult based on operation success
        return new WriteResult(true, null, 1, 1, 1);
    }

    @Override
    public void close() throws Exception {
        // NOTE: ParquetDocumentInput does NOT own the ManagedVSR lifecycle
        // The ManagedVSR is owned and managed by VSRManager/VSRPool
        // VSRManager.close() -> vsrPool.completeVSR(managedVSR) handles cleanup
        // ParquetDocumentInput only holds a reference for field population
        
        // No cleanup needed here - VSRManager handles the ManagedVSR lifecycle
    }
}
