/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package com.parquet.parquetdataformat.vsr;

import com.parquet.parquetdataformat.writer.ParquetDocumentInput;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import com.parquet.parquetdataformat.bridge.NativeCallWrapper;
import com.parquet.parquetdataformat.converter.ParquetFieldType;
import com.parquet.parquetdataformat.bridge.ArrowExport;
import com.parquet.parquetdataformat.memory.ArrowBufferPool;
import com.parquet.parquetdataformat.memory.MemoryPressureMonitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.arrow.vector.*;
import org.apache.arrow.vector.types.pojo.ArrowType;

/**
 * Manages VectorSchemaRoot lifecycle with integrated memory management and native call wrappers.
 * Provides a high-level interface for Parquet document operations using managed VSR abstractions.
 *
 * <p>This class orchestrates the following components:
 * <ul>
 *   <li>{@link ManagedVSR} - Thread-safe VSR with state management</li>
 *   <li>{@link VSRPool} - Resource pooling for VSRs</li>
 *   <li>{@link ArrowBufferPool} - Memory management with pressure monitoring</li>
 *   <li>{@link NativeCallWrapper} - Safe JNI calls to Rust backend</li>
 * </ul>
 */
public class VSRManager {
    private ManagedVSR managedVSR;
    private Map<String, FieldVector> fieldVectorMap;
    private final Schema schema;
    private final BufferAllocator allocator;
    private final String fileName;
    private final VSRPool vsrPool;
    private final NativeCallWrapper nativeWrapper;

    public VSRManager(String fileName, Schema schema) {
        this.fileName = fileName;
        this.schema = schema;

        // Create memory monitor and buffer pool
        MemoryPressureMonitor memoryMonitor = new MemoryPressureMonitor(org.opensearch.common.settings.Settings.EMPTY);
        ArrowBufferPool bufferPool = new ArrowBufferPool(org.opensearch.common.settings.Settings.EMPTY, memoryMonitor);
        this.allocator = bufferPool.createAllocator("vsrmanager-" + fileName);

        // Create VSR pool
        this.vsrPool = new VSRPool("pool-" + fileName, schema, bufferPool, memoryMonitor);

        // Create native call wrapper
        this.nativeWrapper = new NativeCallWrapper(org.opensearch.common.settings.Settings.EMPTY);

        // Get active VSR from pool
        this.managedVSR = vsrPool.getActiveVSR();
        initializeFieldVectorMap();
        // Initialize writer lazily to avoid crashes
        initializeWriter();
    }

    private void initializeWriter() {
        try {
            // Export schema through managed VSR
            try (ArrowExport export = managedVSR.exportToArrow()) {
                long schemaAddress = export.getSchemaAddress();

                // Use safer native call wrapper
                NativeCallWrapper.NativeCallResult result = nativeWrapper.createWriter(fileName, schemaAddress);
                if (!result.isSuccess()) {
                    throw new RuntimeException("Failed to create writer: " + result.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Parquet writer: " + e.getMessage(), e);
        }
    }

    public Object addDoc(ParquetDocumentInput document) throws IOException {
        // Check for VSR rotation before processing document
        checkAndHandleVSRRotation();
        
        // Ensure VSR is in ACTIVE state for modifications
        if (managedVSR.getState() != VSRState.ACTIVE) {
            throw new IOException("Cannot add document - VSR is not active: " + managedVSR.getState());
        }

        System.out.println("[JAVA] addDoc called, current row count: " + managedVSR.getRowCount());
        try {
            Map<ParquetFieldType, Object> fields = document.getFinalInput().getFields();
            System.out.println("[JAVA] Document has " + fields.size() + " fields");

            int rowIndex = managedVSR.getRowCount();

            for (Map.Entry<ParquetFieldType, Object> entry : fields.entrySet()) {
                System.out.println("[JAVA] Processing field: " + entry.getKey().getName() + " = " + entry.getValue());
                FieldVector vector = fieldVectorMap.get(entry.getKey().getName());
                if (vector == null) {
                    throw new IOException("No vector found for field: " + entry.getKey().getName());
                }

                ArrowType type = entry.getKey().getType();
                Object value = entry.getValue();

                // Set vector capacity first, let Arrow manage counts
                vector.setValueCount(rowIndex + 1);

                switch (type) {
                    case ArrowType.Int intType -> {
                        if (intType.getBitWidth() == 32) {
                            IntVector intVector = (IntVector) vector;
                            if (value == null) {
                                intVector.setNull(rowIndex);
                            } else {
                                intVector.setSafe(rowIndex, (Integer) value);
                            }
                        } else if (intType.getBitWidth() == 64) {
                            BigIntVector bigIntVector = (BigIntVector) vector;
                            if (value == null) {
                                bigIntVector.setNull(rowIndex);
                            } else {
                                bigIntVector.setSafe(rowIndex, (Long) value);
                            }
                        }
                    }
                    case ArrowType.FloatingPoint floatType -> {
                        switch (floatType.getPrecision()) {
                            case SINGLE -> {
                                Float4Vector floatVector = (Float4Vector) vector;
                                if (value == null) {
                                    floatVector.setNull(rowIndex);
                                } else {
                                    floatVector.setSafe(rowIndex, (Float) value);
                                }
                            }
                            case DOUBLE -> {
                                Float8Vector doubleVector = (Float8Vector) vector;
                                if (value == null) {
                                    doubleVector.setNull(rowIndex);
                                } else {
                                    doubleVector.setSafe(rowIndex, (Double) value);
                                }
                            }
                        }
                    }
                    case ArrowType.Utf8 utf8 -> {
                        VarCharVector strVector = (VarCharVector) vector;
                        if (value == null) {
                            strVector.setNull(rowIndex);
                        } else {
                            byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                            strVector.setSafe(rowIndex, bytes);
                        }
                    }
                    case ArrowType.Bool bool -> {
                        BitVector bitVector = (BitVector) vector;
                        if (value == null) {
                            bitVector.setNull(rowIndex);
                        } else {
                            bitVector.setSafe(rowIndex, ((Boolean) value) ? 1 : 0);
                        }
                    }
                    case null, default -> throw new UnsupportedOperationException("Unsupported ArrowType: " + type);
                }
            }

            // Use managed VSR's thread-safe setRowCount instead of manual increment
            managedVSR.setRowCount(rowIndex + 1);
            System.out.println("[JAVA] After adding document, row count: " + managedVSR.getRowCount());
            return null;
        } catch (Exception e) {
            System.out.println("[JAVA] ERROR in addDoc: " + e.getMessage());
            throw new IOException("Failed to add document: " + e.getMessage(), e);
        }
    }

    public Object flush(Object flushIn) throws IOException {
        System.out.println("[JAVA] flush called, row count: " + managedVSR.getRowCount());
        try {
            // Only flush if we have data
            if (managedVSR.getRowCount() == 0) {
                System.out.println("[JAVA] No data to flush, returning null");
                return null;
            }

            // Transition VSR to FROZEN state before flushing
            managedVSR.setState(VSRState.FROZEN);
            System.out.println("[JAVA] Flushing " + managedVSR.getRowCount() + " rows");

            // Transition to FLUSHING state
            managedVSR.setState(VSRState.FLUSHING);

            // Use managed VSR export through native call wrapper
            NativeCallWrapper.NativeCallResult result = nativeWrapper.writeData(fileName, managedVSR);
            if (!result.isSuccess()) {
                throw new IOException("Failed to write data: " + result.getMessage());
            }
            System.out.println("[JAVA] Successfully flushed data");

            return null;
        } catch (Exception e) {
            System.out.println("[JAVA] ERROR in flush: " + e.getMessage());
            throw new IOException("Failed to flush data: " + e.getMessage(), e);
        }
    }

    public void close() {
        try {
            // Use safer native call wrapper
            NativeCallWrapper.NativeCallResult closeResult = nativeWrapper.closeWriter(fileName);
            if (!closeResult.isSuccess()) {
                System.err.println("Warning: Failed to close writer: " + closeResult.getMessage());
            }

            NativeCallWrapper.NativeCallResult flushResult = nativeWrapper.flushToDisk(fileName);
            if (!flushResult.isSuccess()) {
                System.err.println("Warning: Failed to flush to disk: " + flushResult.getMessage());
            }

            // Complete VSR processing and cleanup
            vsrPool.completeVSR(managedVSR);
            managedVSR = null;

            // Close the allocator
            allocator.close();
        } catch (Exception e) {
            System.err.println("Error during close: " + e.getMessage());
        }
    }

    private boolean checkFlushConditions() {
        // TODO: Implement memory pressure-based flush conditions
        return false;
    }

    /**
     * Checks if VSR rotation is needed based on row count and memory pressure.
     * If rotation occurs, updates the managed VSR reference and reinitializes field vectors.
     */
    private void checkAndHandleVSRRotation() throws IOException {
        // Get active VSR from pool - this will trigger rotation if needed
        ManagedVSR currentActive = vsrPool.getActiveVSR();
        
        // Check if we got a different VSR (rotation occurred)
        if (currentActive != managedVSR) {
            System.out.println("[JAVA] VSR rotation detected, updating references");
            
            // Update the managed VSR reference
            managedVSR = currentActive;
            
            // Reinitialize field vector map with new VSR
            reinitializeFieldVectorMap();
            
            // Note: Writer initialization is not needed per VSR as it's per file
            System.out.println("[JAVA] VSR rotation completed, new row count: " + managedVSR.getRowCount());
        }
    }
    
    /**
     * Reinitializes the field vector map with the current managed VSR.
     * Called after VSR rotation to update vector references.
     */
    private void reinitializeFieldVectorMap() {
        fieldVectorMap.clear();
        initializeFieldVectorMap();
    }

    private void initializeFieldVectorMap() {
        fieldVectorMap = new HashMap<>();
        for (Field field : schema.getFields()) {
            String fieldName = field.getName();
            FieldVector fieldVector = managedVSR.getVector(fieldName);
            // Vector is already properly typed from ManagedVSR.getVector()
            fieldVectorMap.put(fieldName, fieldVector);
        }
    }
}
