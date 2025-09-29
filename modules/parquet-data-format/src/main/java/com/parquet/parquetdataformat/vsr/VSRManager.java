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
import org.opensearch.index.engine.exec.WriteResult;

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

    public WriteResult addToManagedVSR(ParquetDocumentInput document) throws IOException {
        // Ensure we have an active VSR (handle case where getActiveVSR() returns null)
        if (managedVSR == null) {
            managedVSR = vsrPool.getActiveVSR();
            if (managedVSR == null) {
                throw new IOException("No active VSR available");
            }
            reinitializeFieldVectorMap();
        }

        // Ensure VSR is in ACTIVE state for modifications
        if (managedVSR.getState() != VSRState.ACTIVE) {
            throw new IOException("Cannot add document - VSR is not active: " + managedVSR.getState());
        }

        System.out.println("[JAVA] addToManagedVSR called, current row count: " + managedVSR.getRowCount());

        try {
            // Since ParquetDocumentInput now works directly with ManagedVSR,
            // fields should already be populated in vectors via addField() calls.
            // We just need to finalize the document by calling addToWriter()
            // which will increment the row count.
            WriteResult result = document.addToWriter();

            System.out.println("[JAVA] After adding document, row count: " + managedVSR.getRowCount());

            // Check for VSR rotation AFTER successful document processing
            handleVSRRotationAfterAddToManagedVSR();

            return result;
        } catch (Exception e) {
            System.out.println("[JAVA] ERROR in addToManagedVSR: " + e.getMessage());
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
     * Handles VSR rotation after successful document addition.
     * Checks if rotation is needed and immediately processes any frozen VSR.
     */
    private void handleVSRRotationAfterAddToManagedVSR() throws IOException {
        try {
            // Check if rotation is needed and perform it if safe
            boolean rotated = vsrPool.maybeRotateActiveVSR();

            if (rotated) {
                System.out.println("[JAVA] VSR rotation occurred after document addition");

                // Get the frozen VSR that was just created by rotation
                ManagedVSR frozenVSR = vsrPool.getFrozenVSR();
                if (frozenVSR != null) {
                    System.out.println("[JAVA] Processing frozen VSR: " + frozenVSR.getId() +
                                     " with " + frozenVSR.getRowCount() + " rows");

                    // Write the frozen VSR data immediately
                    frozenVSR.setState(VSRState.FLUSHING);
                    NativeCallWrapper.NativeCallResult result = nativeWrapper.writeData(fileName, frozenVSR);
                    if (!result.isSuccess()) {
                        throw new IOException("Failed to write rotated VSR data: " + result.getMessage());
                    }

                    System.out.println("[JAVA] Successfully wrote frozen VSR data");

                    // Complete the VSR processing
                    vsrPool.completeVSR(frozenVSR);
                } else {
                    System.err.println("[JAVA] WARNING: Rotation occurred but no frozen VSR found");
                }

                // Update to new active VSR
                managedVSR = vsrPool.getActiveVSR();
                if (managedVSR == null) {
                    throw new IOException("No active VSR available after rotation");
                }

                // Reinitialize field vector map with new VSR
                reinitializeFieldVectorMap();

                System.out.println("[JAVA] VSR rotation completed, new active VSR: " + managedVSR.getId() +
                                 ", row count: " + managedVSR.getRowCount());
            }
        } catch (IOException e) {
            System.err.println("[JAVA] Error during VSR rotation: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Checks if VSR rotation is needed based on row count and memory pressure.
     * If rotation occurs, updates the managed VSR reference and reinitializes field vectors.
     *
     * @deprecated Use handleVSRRotationAfterAddToManagedVSR() instead for safer rotation after document processing
     */
    @Deprecated
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

    /**
     * Gets the current active ManagedVSR for document input creation.
     *
     * @return The current managed VSR instance
     */
    public ManagedVSR getActiveManagedVSR() {
        return managedVSR;
    }
}
