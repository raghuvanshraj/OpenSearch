/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package com.parquet.parquetdataformat.fields;

import com.parquet.parquetdataformat.fields.number.ByteParquetField;
import com.parquet.parquetdataformat.fields.number.DoubleParquetField;
import com.parquet.parquetdataformat.fields.number.FloatParquetField;
import com.parquet.parquetdataformat.fields.number.HalfFloatParquetField;
import com.parquet.parquetdataformat.fields.number.IntegerParquetField;
import com.parquet.parquetdataformat.fields.number.LongParquetField;
import com.parquet.parquetdataformat.fields.number.ShortParquetField;
import com.parquet.parquetdataformat.fields.number.UnsignedLongParquetField;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.index.mapper.BooleanFieldMapper;
import org.opensearch.index.mapper.DateFieldMapper;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.NumberFieldMapper;
import org.opensearch.index.mapper.TextFieldMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ParquetFieldUtil {
    private final Map<String, ParquetField> parquetFieldMap;

    public static Schema getSchema() {
        // Create the most minimal schema possible - just one string field
        return new Schema(Arrays.asList(
            //Number fields
            new Field(NumberFieldMapper.NumberType.HALF_FLOAT.typeName(),
                FieldType.notNullable(new ArrowType.FloatingPoint(FloatingPointPrecision.HALF)), null),
            new Field(NumberFieldMapper.NumberType.FLOAT.typeName(),
                FieldType.notNullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            new Field(NumberFieldMapper.NumberType.DOUBLE.typeName(),
                FieldType.notNullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            new Field(NumberFieldMapper.NumberType.BYTE.typeName(),
                FieldType.notNullable(new ArrowType.Int(8, true)), null),
            new Field(NumberFieldMapper.NumberType.SHORT.typeName(),
                FieldType.notNullable(new ArrowType.Int(16, true)), null),
            new Field(NumberFieldMapper.NumberType.INTEGER.typeName(),
                FieldType.notNullable(new ArrowType.Int(32, true)), null),
            new Field(NumberFieldMapper.NumberType.LONG.typeName(),
                FieldType.notNullable(new ArrowType.Int(64, true)), null),
            new Field(NumberFieldMapper.NumberType.UNSIGNED_LONG.typeName(),
                FieldType.notNullable(new ArrowType.Int(64, false)), null),

            //Date field
            new Field(DateFieldMapper.CONTENT_TYPE,
                FieldType.notNullable(new ArrowType.Timestamp(TimeUnit.MILLISECOND, null)), null),

            //Boolean field
            new Field(BooleanFieldMapper.CONTENT_TYPE,
                FieldType.nullable(new ArrowType.Bool()), null),

            //Keyword field
            new Field(KeywordFieldMapper.CONTENT_TYPE,
                FieldType.nullable(new ArrowType.Utf8()), null),

            //Text field
            new Field(TextFieldMapper.CONTENT_TYPE,
                FieldType.notNullable(new ArrowType.Utf8()), null),

            //Keyword field
            new Field(KeywordFieldMapper.CONTENT_TYPE,
                FieldType.notNullable(new ArrowType.Utf8()), null)
        ));
    }

    public ParquetFieldUtil() {
        parquetFieldMap = new HashMap<>();

        //Number fields
        ParquetField halfFloatParquetField = new HalfFloatParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.HALF_FLOAT.typeName(), halfFloatParquetField);
        ParquetField floatParquetField = new FloatParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.FLOAT.typeName(), floatParquetField);
        ParquetField doubleParquetField = new DoubleParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.DOUBLE.typeName(), doubleParquetField);
        ParquetField byteParquetField = new ByteParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.BYTE.typeName(), byteParquetField);
        ParquetField shortParquetField = new ShortParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.SHORT.typeName(), shortParquetField);
        ParquetField intParquetField = new IntegerParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.INTEGER.typeName(), intParquetField);
        ParquetField longParquetField = new LongParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.LONG.typeName(), longParquetField);
        ParquetField unsignedLongParquetField = new UnsignedLongParquetField();
        parquetFieldMap.put(NumberFieldMapper.NumberType.UNSIGNED_LONG.typeName(), unsignedLongParquetField);

        //Date field
        ParquetField dateParquetField = new DateParquetField();
        parquetFieldMap.put(DateFieldMapper.CONTENT_TYPE, dateParquetField);

        //Boolean field
        ParquetField booleanParquetField = new BooleanParquetField();
        parquetFieldMap.put(BooleanFieldMapper.CONTENT_TYPE, booleanParquetField);

        //Text field
        ParquetField textParquetField = new TextParquetField();
        parquetFieldMap.put(TextFieldMapper.CONTENT_TYPE, textParquetField);

        //Keyword field
        ParquetField keywordParquetField = new KeywordParquetField();
        parquetFieldMap.put(KeywordFieldMapper.CONTENT_TYPE, keywordParquetField);
    }

    public ParquetField getParquetField(String type) {
        return parquetFieldMap.get(type);
    }
}
