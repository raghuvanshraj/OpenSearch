package com.parquet.parquetdataformat.engine;

import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.index.engine.exec.DataFormat;
import org.opensearch.index.engine.exec.IndexingExecutionEngine;
import org.opensearch.index.engine.exec.RefreshInput;
import org.opensearch.index.engine.exec.RefreshResult;
import org.opensearch.index.engine.exec.Writer;
import com.parquet.parquetdataformat.writer.ParquetDocumentInput;
import com.parquet.parquetdataformat.writer.ParquetWriter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.parquet.parquetdataformat.engine.ParquetDataFormat.PARQUET_DATA_FORMAT;

/**
 * Main execution engine for Parquet-based indexing operations in OpenSearch.
 *
 * <p>This engine implements OpenSearch's IndexingExecutionEngine interface to provide
 * Parquet file generation capabilities within the indexing pipeline. It manages the
 * lifecycle of Parquet writers and coordinates the overall document processing workflow.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Writer creation with unique file naming and Arrow schema integration</li>
 *   <li>Schema-based field type support and validation</li>
 *   <li>Refresh operations for completing indexing cycles</li>
 *   <li>Integration with the broader Parquet data format ecosystem</li>
 * </ul>
 *
 * <p>The engine uses an atomic counter to ensure unique Parquet file names across
 * concurrent operations, following the naming pattern "parquet_file_generation_N.parquet"
 * where N is an incrementing sequence number.
 *
 * <p>Each writer instance created by this engine is configured with:
 * <ul>
 *   <li>A unique file name for output isolation</li>
 *   <li>The Arrow schema provided during engine construction</li>
 *   <li>Full access to the Parquet processing pipeline via {@link ParquetWriter}</li>
 * </ul>
 *
 * <p>The engine is designed to work with {@link ParquetDocumentInput} for document
 * processing and integrates seamlessly with OpenSearch's execution framework.
 */
public class ParquetExecutionEngine implements IndexingExecutionEngine<ParquetDataFormat> {

    public static final String FILE_NAME_PREFIX = "parquet_file_generation";
    AtomicInteger counter;
    Supplier<Schema> schema;

    public ParquetExecutionEngine(Supplier<Schema> schema) {
        counter = new AtomicInteger(0);
        this.schema = schema;
    }

    @Override
    public List<String> supportedFieldTypes() {
        return List.of();
    }

    @Override
    public Writer<ParquetDocumentInput> createWriter() throws IOException {
        String fileName = FILE_NAME_PREFIX + "_" + counter.getAndIncrement() + ".parquet";
        return new ParquetWriter(fileName, schema.get());
    }

    @Override
    public RefreshResult refresh(RefreshInput refreshInput) throws IOException {
        RefreshResult refreshResult = new RefreshResult();
        refreshResult.add(PARQUET_DATA_FORMAT, refreshInput.getFiles());
        return refreshResult;
    }

    @Override
    public DataFormat getDataFormat() {
        return new ParquetDataFormat();
    }
}
