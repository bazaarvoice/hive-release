/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io.orc;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.AcidOutputFormat;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.RecordIdentifier;
import org.apache.hadoop.hive.ql.io.RecordUpdater;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import java.util.ArrayList;
import java.util.List;

/**
 * A RecordUpdater where the files are stored as ORC.
 */
public class OrcRecordUpdater implements RecordUpdater {
  final static String ACID_KEY_INDEX_NAME = "hive.acid.key.index";

  final static int INSERT_OPERATION = 0;
  final static int UPDATE_OPERATION = 1;
  final static int DELETE_OPERATION = 2;

  final static int OPERATION = 0;
  final static int ORIGINAL_TRANSACTION = 1;
  final static int BUCKET = 2;
  final static int ROW_ID = 3;
  final static int CURRENT_TRANSACTION = 4;
  final static int ROW = 5;
  final static int FIELDS = 6;

  final static int DELTA_BUFFER_SIZE = 16 * 1024;
  final static long DELTA_STRIPE_SIZE = 16 * 1024 * 1024;

  private final AcidOutputFormat.Options options;
  private final Path path;
  private final FileSystem fs;
  private Writer writer;
  private FSDataOutputStream flushLengths = null;
  private final OrcStruct item;
  private final IntWritable operation = new IntWritable();
  private final LongWritable currentTransaction = new LongWritable(-1);
  private final LongWritable originalTransaction = new LongWritable(-1);
  private final IntWritable bucket = new IntWritable();
  private final LongWritable rowId = new LongWritable();
  private long insertedRows = 0;
  private final KeyIndexBuilder indexBuilder = new KeyIndexBuilder();

  static Path getSideFile(Path main) {
    return new Path(main + "_flush_length");
  }

  static int getOperation(OrcStruct struct) {
    return ((IntWritable) struct.getFieldValue(OPERATION)).get();
  }

  static long getCurrentTransaction(OrcStruct struct) {
    return ((LongWritable) struct.getFieldValue(CURRENT_TRANSACTION)).get();
  }

  static long getOriginalTransaction(OrcStruct struct) {
    return ((LongWritable) struct.getFieldValue(ORIGINAL_TRANSACTION)).get();
  }

  static int getBucket(OrcStruct struct) {
    return ((IntWritable) struct.getFieldValue(BUCKET)).get();
  }

  static long getRowId(OrcStruct struct) {
    return ((LongWritable) struct.getFieldValue(ROW_ID)).get();
  }

  static OrcStruct getRow(OrcStruct struct) {
    if (struct == null) {
      return null;
    } else {
      return (OrcStruct) struct.getFieldValue(ROW);
    }
  }

  /**
   * An extension to AcidOutputFormat that allows users to add additional
   * options.
   */
  public static class OrcOptions extends AcidOutputFormat.Options {
    OrcFile.WriterOptions orcOptions = null;

    public OrcOptions(Configuration conf) {
      super(conf);
    }

    public OrcOptions orcOptions(OrcFile.WriterOptions opts) {
      this.orcOptions = opts;
      return this;
    }

    public OrcFile.WriterOptions getOrcOptions() {
      return orcOptions;
    }
  }

  /**
   * Create an object inspector for the ACID event based on the object inspector
   * for the underlying row.
   * @param rowInspector the row's object inspector
   * @return an object inspector for the event stream
   */
  static ObjectInspector createEventSchema(ObjectInspector rowInspector) {
    List<StructField> fields = new ArrayList<StructField>();
    fields.add(new OrcStruct.Field("operation",
        PrimitiveObjectInspectorFactory.writableIntObjectInspector, OPERATION));
    fields.add(new OrcStruct.Field("originalTransaction",
        PrimitiveObjectInspectorFactory.writableLongObjectInspector,
        ORIGINAL_TRANSACTION));
    fields.add(new OrcStruct.Field("bucket",
        PrimitiveObjectInspectorFactory.writableIntObjectInspector, BUCKET));
    fields.add(new OrcStruct.Field("rowId",
        PrimitiveObjectInspectorFactory.writableLongObjectInspector, ROW_ID));
    fields.add(new OrcStruct.Field("currentTransaction",
        PrimitiveObjectInspectorFactory.writableLongObjectInspector,
        CURRENT_TRANSACTION));
    fields.add(new OrcStruct.Field("row", rowInspector, ROW));
    return new OrcStruct.OrcStructInspector(fields);
  }

  OrcRecordUpdater(Path path,
                   AcidOutputFormat.Options options) throws IOException {
    this.options = options;
    this.bucket.set(options.getBucket());
    this.path = AcidUtils.createFilename(path, options);
    FileSystem fs = options.getFilesystem();
    if (fs == null) {
      fs = path.getFileSystem(options.getConfiguration());
    }
    this.fs = fs;
    OrcFile.WriterOptions writerOptions = null;
    if (options instanceof OrcOptions) {
      writerOptions = ((OrcOptions) options).getOrcOptions();
    }
    if (writerOptions == null) {
      writerOptions = OrcFile.writerOptions(options.getConfiguration());
    }
    writerOptions.fileSystem(fs).callback(indexBuilder);
    if (!options.isWritingBase()) {
      writerOptions.blockPadding(false);
      writerOptions.bufferSize(DELTA_BUFFER_SIZE);
      writerOptions.stripeSize(DELTA_STRIPE_SIZE);
    }
    writerOptions.inspector(createEventSchema(options.getInspector()));
    this.writer = OrcFile.createWriter(this.path, writerOptions);
    item = new OrcStruct(FIELDS);
    item.setFieldValue(OPERATION, operation);
    item.setFieldValue(CURRENT_TRANSACTION, currentTransaction);
    item.setFieldValue(ORIGINAL_TRANSACTION, originalTransaction);
    item.setFieldValue(BUCKET, bucket);
    item.setFieldValue(ROW_ID, rowId);
  }

  @Override
  public void insert(long currentTransaction, Object row) throws IOException {
    operation.set(INSERT_OPERATION);
    if (this.currentTransaction.get() != currentTransaction) {
      this.currentTransaction.set(currentTransaction);
      this.originalTransaction.set(currentTransaction);
      insertedRows = 0;
    }
    indexBuilder.addKey(currentTransaction, bucket.get(), insertedRows);
    this.rowId.set(insertedRows++);
    item.setFieldValue(OrcRecordUpdater.ROW, row);
    writer.addRow(item);
  }

  @Override
  public void update(long currentTransaction, long originalTransaction,
                     long rowId, Object row) throws IOException {
    operation.set(UPDATE_OPERATION);
    this.currentTransaction.set(currentTransaction);
    this.originalTransaction.set(originalTransaction);
    this.rowId.set(rowId);
    item.setFieldValue(OrcRecordUpdater.ROW, row);
    indexBuilder.addKey(originalTransaction, bucket.get(), rowId);
    writer.addRow(item);
  }

  @Override
  public void delete(long currentTransaction, long originalTransaction,
                     long rowId) throws IOException {
    operation.set(DELETE_OPERATION);
    this.currentTransaction.set(currentTransaction);
    this.originalTransaction.set(originalTransaction);
    this.rowId.set(rowId);
    item.setFieldValue(OrcRecordUpdater.ROW, null);
    indexBuilder.addKey(originalTransaction, bucket.get(), rowId);
    writer.addRow(item);
  }

  @Override
  public void flush() throws IOException {
    long len = writer.writeIntermediateFooter();
    if (flushLengths == null) {
      flushLengths = fs.create(getSideFile(path), true, 8,
          options.getReporter());
    }
    flushLengths.writeLong(len);
    flushLengths.flush();
  }

  @Override
  public void close(boolean abort) throws IOException {
    if (abort) {
      if (flushLengths == null) {
        fs.delete(path, false);
      }
    } else {
      writer.close();
    }
    if (flushLengths != null) {
      flushLengths.close();
      fs.delete(getSideFile(path), false);
    }
    writer = null;
  }

  @Override
  public SerDeStats getStats() {
    return null;
  }

  private static final Charset utf8 = Charset.forName("UTF-8");
  private static final CharsetDecoder utf8Decoder = utf8.newDecoder();

  static RecordIdentifier[] parseKeyIndex(Reader reader) {
    String[] stripes;
    try {
      ByteBuffer val =
          reader.getMetadataValue(OrcRecordUpdater.ACID_KEY_INDEX_NAME)
              .duplicate();
      stripes = utf8Decoder.decode(val).toString().split(";");
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Bad string encoding for " +
          OrcRecordUpdater.ACID_KEY_INDEX_NAME, e);
    }
    RecordIdentifier[] result = new RecordIdentifier[stripes.length];
    for(int i=0; i < stripes.length; ++i) {
      if (stripes[i].length() != 0) {
        String[] parts = stripes[i].split(",");
        result[i] = new RecordIdentifier();
        result[i].setValues(Long.parseLong(parts[0]),
            Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
      }
    }
    return result;
  }

  static class KeyIndexBuilder implements OrcFile.WriterCallback {
    StringBuilder lastKey = new StringBuilder();
    long lastTransaction;
    int lastBucket;
    long lastRowId;

    @Override
    public void preStripeWrite(OrcFile.WriterContext context
    ) throws IOException {
      lastKey.append(lastTransaction);
      lastKey.append(',');
      lastKey.append(lastBucket);
      lastKey.append(',');
      lastKey.append(lastRowId);
      lastKey.append(';');
    }

    @Override
    public void preFooterWrite(OrcFile.WriterContext context
                               ) throws IOException {
      context.getWriter().addUserMetadata(OrcRecordUpdater.ACID_KEY_INDEX_NAME,
          ByteBuffer.wrap(lastKey.toString().getBytes(utf8)));
    }

    void addKey(long transaction, int bucket, long rowId) {
      lastTransaction = transaction;
      lastBucket = bucket;
      lastRowId = rowId;
    }
  }
}
