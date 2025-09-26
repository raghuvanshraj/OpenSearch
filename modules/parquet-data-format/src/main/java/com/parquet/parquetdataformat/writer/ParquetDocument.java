package com.parquet.parquetdataformat.writer;

import org.opensearch.index.mapper.MappedFieldType;
import com.parquet.parquetdataformat.converter.ParquetFieldType;

import java.util.HashMap;
import java.util.Map;

import static com.parquet.parquetdataformat.converter.FieldTypeConverter.convertToParquetFieldType;

/**
 * Represents a document to be written in Parquet format.
 *
 * <p>This class encapsulates the field data for a single document, providing
 * a mapping between Parquet field types and their corresponding values.
 * It serves as the intermediate representation between OpenSearch's field
 * mapping system and the Parquet-specific data structures.
 *
 * <p>The document uses {@link ParquetFieldType} as keys, which are converted
 * from OpenSearch's {@link MappedFieldType} through the.
 * This abstraction allows for proper type mapping and ensures compatibility
 * between OpenSearch field types and Parquet data types.
 *
 * <p>Key features:
 * <ul>
 *   <li>Automatic field type conversion from OpenSearch to Parquet types</li>
 *   <li>Simple key-value storage for document fields</li>
 *   <li>Integration with the Parquet writing pipeline</li>
 * </ul>
 */
public class ParquetDocument {
    private final Map<ParquetFieldType, Object> fieldMap;

    public ParquetDocument() {
        fieldMap = new HashMap<>();
    }

    public void addField(MappedFieldType fieldType, Object value) {
        fieldMap.put(convertToParquetFieldType(fieldType),value);
    }

    public Map<ParquetFieldType, Object> getFields() {
        return fieldMap;
    }
}
