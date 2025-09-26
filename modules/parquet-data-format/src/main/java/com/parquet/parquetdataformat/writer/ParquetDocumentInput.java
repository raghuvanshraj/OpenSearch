package com.parquet.parquetdataformat.writer;

import org.opensearch.index.engine.exec.DocumentInput;
import org.opensearch.index.engine.exec.WriteResult;
import org.opensearch.index.mapper.MappedFieldType;

import java.io.IOException;

/**
 * Document input wrapper for Parquet-based document processing.
 * 
 * <p>This class serves as an adapter between OpenSearch's DocumentInput interface
 * and the Parquet-specific document representation. It encapsulates a {@link ParquetDocument}
 * and provides methods to add fields and retrieve the final document for processing.
 * 
 * <p>The implementation follows the builder pattern, allowing incremental construction
 * of documents through field addition before finalizing the document for writing.
 * 
 * <p>Key responsibilities:
 * <ul>
 *   <li>Field aggregation using OpenSearch's {@link MappedFieldType}</li>
 *   <li>Document lifecycle management</li>
 *   <li>Integration with the Parquet writer pipeline</li>
 * </ul>
 * 
 * <p>Note: The current implementation has a TODO for improving the abstraction
 * between writer and document input to reduce tight coupling.
 */
public class ParquetDocumentInput implements DocumentInput<ParquetDocument> {
    private final ParquetDocument parquetDocument;

    public ParquetDocumentInput() {
        this.parquetDocument = new ParquetDocument();
    }

    @Override
    public void addField(MappedFieldType fieldType, Object value) {
        parquetDocument.addField(fieldType,value);
    }

    @Override
    public ParquetDocument getFinalInput() {
        return parquetDocument;
    }

    @Override
    public WriteResult addToWriter() throws IOException {
        // Seems like tight coupling between the writer and the document input
        // We can have a better abstraction here
        // For now, returning null
        return null;
    }

    @Override
    public void close() throws Exception {

    }
}
