package com.parquet.parquetdataformat.vsr;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import com.parquet.parquetdataformat.memory.ArrowBufferPool;
import com.parquet.parquetdataformat.memory.MemoryPressureMonitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages VectorSchemaRoot lifecycle with ACTIVE and FROZEN states as specified
 * in the Project Mustang design. Each ParquetWriter maintains a single ACTIVE VSR
 * for writing and a single FROZEN VSR for Rust handoff.
 */
public class VSRPool {
    
    private final Schema schema;
    private final ArrowBufferPool bufferPool;
    private final MemoryPressureMonitor memoryMonitor;
    private final String poolId;
    
    // VSR lifecycle management
    private final AtomicReference<ManagedVSR> activeVSR;
    private final AtomicReference<ManagedVSR> frozenVSR;
    private final ConcurrentHashMap<String, ManagedVSR> allVSRs;
    private final AtomicInteger vsrCounter;
    
    // Configuration
    private final int maxRowsPerVSR;
    
    public VSRPool(String poolId, Schema schema, ArrowBufferPool bufferPool, 
                   MemoryPressureMonitor memoryMonitor) {
        this.poolId = poolId;
        this.schema = schema;
        this.bufferPool = bufferPool;
        this.memoryMonitor = memoryMonitor;
        
        this.activeVSR = new AtomicReference<>();
        this.frozenVSR = new AtomicReference<>();
        this.allVSRs = new ConcurrentHashMap<>();
        this.vsrCounter = new AtomicInteger(0);
        
        // Configuration - could be made configurable
        this.maxRowsPerVSR = 50000; // Max rows before forcing freeze
        
        // Initialize with first active VSR
        initializeActiveVSR();
    }
    
    /**
     * Gets the current active VSR for writing.
     * Creates a new one if none exists or current is full.
     * Blocks rotation if frozen VSR slot is occupied to prevent data loss.
     * 
     * @return Active ManagedVSR for writing
     */
    public ManagedVSR getActiveVSR() {
        ManagedVSR current = activeVSR.get();
        
        // Check if we need to create or rotate VSR
        if (current == null || shouldRotateVSR(current)) {
            // CRITICAL: Prevent rotation if frozen slot is occupied
            if (frozenVSR.get() != null) {
                // Backpressure: Return current VSR to prevent data loss
                // Log warning about backpressure condition
                System.err.println("[VSRPool] WARNING: Rotation blocked due to unprocessed frozen VSR. " +
                                 "Current VSR rows: " + current.getRowCount() + 
                                 ", applying backpressure to prevent data loss.");
                return current;
            }
            return rotateActiveVSR();
        }
        
        return current;
    }
    
    /**
     * Freezes the current active VSR and creates a new active one.
     * The frozen VSR replaces any existing frozen VSR.
     * 
     * @return Newly created active VSR
     */
    public ManagedVSR rotateActiveVSR() {
        synchronized (this) {
            ManagedVSR current = activeVSR.get();
            
            // Freeze current VSR if it exists and has data
            if (current != null && current.getRowCount() > 0) {
                freezeVSR(current);
            }
            
            // Create new active VSR
            ManagedVSR newActive = createNewVSR();
            activeVSR.set(newActive);
            
            return newActive;
        }
    }
    
    /**
     * Gets the frozen VSR for Rust processing.
     * 
     * @return Frozen VSR, or null if none available
     */
    public ManagedVSR getFrozenVSR() {
        return frozenVSR.get();
    }
    
    /**
     * Takes the frozen VSR for processing and clears the frozen slot.
     * 
     * @return Frozen VSR that was taken, or null if none available
     */
    public ManagedVSR takeFrozenVSR() {
        return frozenVSR.getAndSet(null);
    }
    
    /**
     * Marks a VSR as flushing (being processed by Rust).
     * 
     * @param vsr VSR being processed
     */
    public void markFlushing(ManagedVSR vsr) {
        vsr.setState(VSRState.FLUSHING);
    }
    
    /**
     * Completes VSR processing and cleans up resources.
     * 
     * @param vsr VSR that has been processed
     */
    public void completeVSR(ManagedVSR vsr) {
        vsr.setState(VSRState.CLOSED);
        vsr.close();
        allVSRs.remove(vsr.getId());
    }
    
    /**
     * Forces all VSRs to be frozen for immediate processing.
     * Used during refresh or shutdown.
     */
    public void freezeAll() {
        ManagedVSR current = activeVSR.getAndSet(null);
        if (current != null && current.getRowCount() > 0) {
            freezeVSR(current);
        }
    }
    
    /**
     * Gets statistics about the VSR pool.
     * 
     * @return PoolStats with current state
     */
    public PoolStats getStats() {
        ManagedVSR active = activeVSR.get();
        ManagedVSR frozen = frozenVSR.get();
        int frozenCount = frozen != null ? 1 : 0;
        
        return new PoolStats(
            poolId,
            active != null ? active.getRowCount() : 0,
            frozenCount,
            allVSRs.size(),
            allVSRs.values().stream().mapToLong(ManagedVSR::getRowCount).sum()
        );
    }
    
    /**
     * Checks if backpressure should be applied.
     * 
     * @return true if frozen VSR slot is occupied or memory pressure is critical
     */
    public boolean shouldApplyBackpressure() {
        return frozenVSR.get() != null || 
               memoryMonitor.getCurrentPressureLevel() == MemoryPressureMonitor.PressureLevel.CRITICAL;
    }
    
    /**
     * Closes the pool and cleans up all resources.
     */
    public void close() {
        // Close active VSR
        ManagedVSR active = activeVSR.getAndSet(null);
        if (active != null) {
            active.close();
        }
        
        // Close frozen VSR
        ManagedVSR frozen = frozenVSR.getAndSet(null);
        if (frozen != null) {
            frozen.close();
        }
        
        // Close any remaining VSRs
        allVSRs.values().forEach(ManagedVSR::close);
        allVSRs.clear();
    }
    
    private void initializeActiveVSR() {
        ManagedVSR initial = createNewVSR();
        activeVSR.set(initial);
    }
    
    private ManagedVSR createNewVSR() {
        try {
            String vsrId = poolId + "-vsr-" + vsrCounter.incrementAndGet();
            BufferAllocator allocator = bufferPool.createAllocator(vsrId);
            VectorSchemaRoot vsr = VectorSchemaRoot.create(schema, allocator);
            
            ManagedVSR managedVSR = new ManagedVSR(vsrId, vsr, allocator);
            allVSRs.put(vsrId, managedVSR);
            
            return managedVSR;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create new VSR", e);
        }
    }
    
    private void freezeVSR(ManagedVSR vsr) {
        vsr.setState(VSRState.FROZEN);
        
        // CRITICAL FIX: Check if frozen slot is already occupied
        ManagedVSR previousFrozen = frozenVSR.get();
        if (previousFrozen != null) {
            // NEVER blindly overwrite a frozen VSR - this would cause data loss
            System.err.println("[VSRPool] ERROR: Attempting to freeze VSR when frozen slot is occupied! " +
                             "Previous VSR: " + previousFrozen.getId() + " (" + previousFrozen.getRowCount() + " rows), " +
                             "New VSR: " + vsr.getId() + " (" + vsr.getRowCount() + " rows). " +
                             "This indicates a logic error - frozen VSR should be consumed before replacement.");
            
            // Return VSR to ACTIVE state to prevent state corruption
            vsr.setState(VSRState.ACTIVE);
            throw new IllegalStateException("Cannot freeze VSR: frozen slot is occupied by unprocessed VSR " + 
                                          previousFrozen.getId() + ". This would cause data loss.");
        }
        
        // Safe to set frozen VSR since slot is empty
        boolean success = frozenVSR.compareAndSet(null, vsr);
        if (!success) {
            // Race condition: another thread set frozen VSR between our check and set
            vsr.setState(VSRState.ACTIVE);
            throw new IllegalStateException("Race condition detected: frozen slot was occupied during freeze operation");
        }
    }
    
    private boolean shouldRotateVSR(ManagedVSR vsr) {
        return vsr.getRowCount() >= maxRowsPerVSR ||
               memoryMonitor.shouldTriggerEarlyRefresh();
    }
    
    /**
     * Statistics for the VSR pool.
     */
    public static class PoolStats {
        private final String poolId;
        private final long activeRowCount;
        private final int frozenVSRCount;
        private final int totalVSRCount;
        private final long totalRowCount;
        
        public PoolStats(String poolId, long activeRowCount, int frozenVSRCount, 
                        int totalVSRCount, long totalRowCount) {
            this.poolId = poolId;
            this.activeRowCount = activeRowCount;
            this.frozenVSRCount = frozenVSRCount;
            this.totalVSRCount = totalVSRCount;
            this.totalRowCount = totalRowCount;
        }
        
        public String getPoolId() { return poolId; }
        public long getActiveRowCount() { return activeRowCount; }
        public int getFrozenVSRCount() { return frozenVSRCount; }
        public int getTotalVSRCount() { return totalVSRCount; }
        public long getTotalRowCount() { return totalRowCount; }
    }
}
