package com.parquet.parquetdataformat.bridge;

import com.parquet.parquetdataformat.vsr.ManagedVSR;
import org.opensearch.common.settings.Settings;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced wrapper around RustBridge calls with proper error handling,
 * validation, and metrics collection. Provides safer JNI interactions
 * as specified in the design requirements.
 */
public class NativeCallWrapper {

    public enum CallResult {
        SUCCESS,
        VALIDATION_ERROR,
        NATIVE_ERROR,
        TIMEOUT_ERROR,
        MEMORY_ERROR
    }

    private final Settings settings;
    private final AtomicLong successCount;
    private final AtomicLong errorCount;
    private final AtomicLong validationErrorCount;
    private final ConcurrentHashMap<String, WriterState> writerStates;

    // Configuration
    private final boolean enableValidation;
    private final long callTimeoutMs;
    private final int maxRetries;

    public NativeCallWrapper(Settings settings) {
        this.settings = settings;
        this.successCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        this.validationErrorCount = new AtomicLong(0);
        this.writerStates = new ConcurrentHashMap<>();

        // Configuration
        this.enableValidation = settings.getAsBoolean("parquet.native.enable_validation", true);
        this.callTimeoutMs = settings.getAsLong("parquet.native.call_timeout_ms", 30000L); // 30s default
        this.maxRetries = settings.getAsInt("parquet.native.max_retries", 2);
    }

    /**
     * Creates a writer with validation and error handling.
     *
     * @param fileName Name of the parquet file
     * @param schemaAddress Memory address of the Arrow schema
     * @return NativeCallResult with success/failure information
     */
    public NativeCallResult createWriter(String fileName, long schemaAddress) {
        String operation = "createWriter";

        // Pre-call validation
        ValidationResult validation = validateCreateWriter(fileName, schemaAddress);
        if (!validation.isValid()) {
            validationErrorCount.incrementAndGet();
            return new NativeCallResult(CallResult.VALIDATION_ERROR, validation.getErrorMessage());
        }

        // Execute native call with retry logic
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                long startTime = System.nanoTime();
                int result = RustBridge.createWriter(fileName, schemaAddress);
                long duration = System.nanoTime() - startTime;

                if (result == 0) {
                    // Success - track writer state
                    writerStates.put(fileName, new WriterState(fileName, System.currentTimeMillis()));
                    successCount.incrementAndGet();
                    return new NativeCallResult(CallResult.SUCCESS, "Writer created successfully", duration);
                } else {
                    String errorMsg = mapNativeErrorCode(result, operation);
                    if (attempt == maxRetries) {
                        errorCount.incrementAndGet();
                        return new NativeCallResult(CallResult.NATIVE_ERROR, errorMsg);
                    }
                    // Retry on certain error codes
                    Thread.sleep(100 * (attempt + 1)); // Exponential backoff
                }
            } catch (OutOfMemoryError e) {
                errorCount.incrementAndGet();
                return new NativeCallResult(CallResult.MEMORY_ERROR, "Out of memory: " + e.getMessage());
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    errorCount.incrementAndGet();
                    return new NativeCallResult(CallResult.NATIVE_ERROR, "Unexpected error: " + e.getMessage());
                }
                try {
                    Thread.sleep(100 * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        errorCount.incrementAndGet();
        return new NativeCallResult(CallResult.NATIVE_ERROR, "Max retries exceeded");
    }

    /**
     * Writes data with VSR export validation and error handling.
     *
     * @param fileName Name of the parquet file
     * @param managedVSR Managed VSR to export and write
     * @return NativeCallResult with success/failure information
     */
    public NativeCallResult writeData(String fileName, ManagedVSR managedVSR) {
        String operation = "writeData";

        // Pre-call validation
        ValidationResult validation = validateWriteData(fileName, managedVSR);
        if (!validation.isValid()) {
            validationErrorCount.incrementAndGet();
            return new NativeCallResult(CallResult.VALIDATION_ERROR, validation.getErrorMessage());
        }

        // Export VSR to Arrow C Data Interface
        try (ArrowExport export = managedVSR.exportToArrow()) {
            long arrayAddress = export.getArrayAddress();
            long schemaAddress = export.getSchemaAddress();

            // Validate memory addresses
            if (arrayAddress == 0 || schemaAddress == 0) {
                validationErrorCount.incrementAndGet();
                return new NativeCallResult(CallResult.VALIDATION_ERROR, "Invalid memory addresses");
            }

            // Execute native call with retry logic
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    long startTime = System.nanoTime();
                    int result = RustBridge.write(fileName, arrayAddress, schemaAddress);
                    long duration = System.nanoTime() - startTime;

                    if (result == 0) {
                        // Success - update writer state
                        WriterState state = writerStates.get(fileName);
                        if (state != null) {
                            state.recordWrite(managedVSR.getRowCount());
                        }
                        successCount.incrementAndGet();
                        return new NativeCallResult(CallResult.SUCCESS, "Data written successfully", duration);
                    } else {
                        String errorMsg = mapNativeErrorCode(result, operation);
                        if (attempt == maxRetries) {
                            errorCount.incrementAndGet();
                            return new NativeCallResult(CallResult.NATIVE_ERROR, errorMsg);
                        }
                        Thread.sleep(100 * (attempt + 1));
                    }
                } catch (OutOfMemoryError e) {
                    errorCount.incrementAndGet();
                    return new NativeCallResult(CallResult.MEMORY_ERROR, "Out of memory: " + e.getMessage());
                } catch (Exception e) {
                    if (attempt == maxRetries) {
                        errorCount.incrementAndGet();
                        return new NativeCallResult(CallResult.NATIVE_ERROR, "Unexpected error: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(100 * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
            return new NativeCallResult(CallResult.NATIVE_ERROR, "Failed to export VSR: " + e.getMessage());
        }

        errorCount.incrementAndGet();
        return new NativeCallResult(CallResult.NATIVE_ERROR, "Max retries exceeded");
    }

    /**
     * Closes writer with validation and error handling.
     *
     * @param fileName Name of the parquet file
     * @return NativeCallResult with success/failure information
     */
    public NativeCallResult closeWriter(String fileName) {
        String operation = "closeWriter";

        // Pre-call validation
        ValidationResult validation = validateCloseWriter(fileName);
        if (!validation.isValid()) {
            validationErrorCount.incrementAndGet();
            return new NativeCallResult(CallResult.VALIDATION_ERROR, validation.getErrorMessage());
        }

        try {
            long startTime = System.nanoTime();
            int result = RustBridge.closeWriter(fileName);
            long duration = System.nanoTime() - startTime;

            if (result == 0) {
                // Success - clean up writer state
                writerStates.remove(fileName);
                successCount.incrementAndGet();
                return new NativeCallResult(CallResult.SUCCESS, "Writer closed successfully", duration);
            } else {
                String errorMsg = mapNativeErrorCode(result, operation);
                errorCount.incrementAndGet();
                return new NativeCallResult(CallResult.NATIVE_ERROR, errorMsg);
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
            return new NativeCallResult(CallResult.NATIVE_ERROR, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Flushes to disk with validation and error handling.
     *
     * @param fileName Name of the parquet file
     * @return NativeCallResult with success/failure information
     */
    public NativeCallResult flushToDisk(String fileName) {
        String operation = "flushToDisk";

        try {
            long startTime = System.nanoTime();
            int result = RustBridge.flushToDisk(fileName);
            long duration = System.nanoTime() - startTime;

            if (result == 0) {
                successCount.incrementAndGet();
                return new NativeCallResult(CallResult.SUCCESS, "Flushed to disk successfully", duration);
            } else {
                String errorMsg = mapNativeErrorCode(result, operation);
                errorCount.incrementAndGet();
                return new NativeCallResult(CallResult.NATIVE_ERROR, errorMsg);
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
            return new NativeCallResult(CallResult.NATIVE_ERROR, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Gets metrics about native call performance.
     *
     * @return NativeCallMetrics with current statistics
     */
    public NativeCallMetrics getMetrics() {
        return new NativeCallMetrics(
            successCount.get(),
            errorCount.get(),
            validationErrorCount.get(),
            writerStates.size()
        );
    }

    private ValidationResult validateCreateWriter(String fileName, long schemaAddress) {
        if (!enableValidation) {
            return ValidationResult.valid();
        }

        if (fileName == null || fileName.trim().isEmpty()) {
            return ValidationResult.invalid("File name cannot be null or empty");
        }

        if (schemaAddress == 0) {
            return ValidationResult.invalid("Schema address cannot be zero");
        }

        if (writerStates.containsKey(fileName)) {
            return ValidationResult.invalid("Writer already exists for file: " + fileName);
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateWriteData(String fileName, ManagedVSR managedVSR) {
        if (!enableValidation) {
            return ValidationResult.valid();
        }

        if (fileName == null || fileName.trim().isEmpty()) {
            return ValidationResult.invalid("File name cannot be null or empty");
        }

        if (managedVSR == null) {
            return ValidationResult.invalid("ManagedVSR cannot be null");
        }

        if (!writerStates.containsKey(fileName)) {
            return ValidationResult.invalid("No writer exists for file: " + fileName);
        }

        if (managedVSR.getRowCount() == 0) {
            return ValidationResult.invalid("VSR has no data to write");
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateCloseWriter(String fileName) {
        if (!enableValidation) {
            return ValidationResult.valid();
        }

        if (fileName == null || fileName.trim().isEmpty()) {
            return ValidationResult.invalid("File name cannot be null or empty");
        }

        if (!writerStates.containsKey(fileName)) {
            return ValidationResult.invalid("No writer exists for file: " + fileName);
        }

        return ValidationResult.valid();
    }

    private String mapNativeErrorCode(int errorCode, String operation) {
        return switch (errorCode) {
            case -1 -> "General error in " + operation;
            case -2 -> "Invalid parameters in " + operation;
            case -3 -> "Memory allocation failed in " + operation;
            case -4 -> "IO error in " + operation;
            case -5 -> "Schema validation failed in " + operation;
            default -> "Unknown error code " + errorCode + " in " + operation;
        };
    }

    /**
     * Tracks state for individual writers.
     */
    private static class WriterState {
        private final String fileName;
        private final long createdTime;
        private final AtomicLong writeCount;
        private final AtomicLong totalRows;
        private volatile long lastWriteTime;

        public WriterState(String fileName, long createdTime) {
            this.fileName = fileName;
            this.createdTime = createdTime;
            this.writeCount = new AtomicLong(0);
            this.totalRows = new AtomicLong(0);
            this.lastWriteTime = createdTime;
        }

        public void recordWrite(int rowCount) {
            writeCount.incrementAndGet();
            totalRows.addAndGet(rowCount);
            lastWriteTime = System.currentTimeMillis();
        }
    }

    /**
     * Result of a validation check.
     */
    private static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * Result of a native call.
     */
    public static class NativeCallResult {
        private final CallResult result;
        private final String message;
        private final long durationNanos;

        public NativeCallResult(CallResult result, String message) {
            this(result, message, 0);
        }

        public NativeCallResult(CallResult result, String message, long durationNanos) {
            this.result = result;
            this.message = message;
            this.durationNanos = durationNanos;
        }

        public CallResult getResult() { return result; }
        public String getMessage() { return message; }
        public long getDurationNanos() { return durationNanos; }
        public boolean isSuccess() { return result == CallResult.SUCCESS; }

        @Override
        public String toString() {
            return String.format("NativeCallResult{result=%s, message='%s', duration=%dns}",
                result, message, durationNanos);
        }
    }

    /**
     * Metrics for native call performance.
     */
    public static class NativeCallMetrics {
        private final long successCount;
        private final long errorCount;
        private final long validationErrorCount;
        private final int activeWriters;

        public NativeCallMetrics(long successCount, long errorCount,
                               long validationErrorCount, int activeWriters) {
            this.successCount = successCount;
            this.errorCount = errorCount;
            this.validationErrorCount = validationErrorCount;
            this.activeWriters = activeWriters;
        }

        public long getSuccessCount() { return successCount; }
        public long getErrorCount() { return errorCount; }
        public long getValidationErrorCount() { return validationErrorCount; }
        public int getActiveWriters() { return activeWriters; }
        public long getTotalCalls() { return successCount + errorCount + validationErrorCount; }
        public double getSuccessRate() {
            long total = getTotalCalls();
            return total > 0 ? (double) successCount / total : 0.0;
        }
    }
}
