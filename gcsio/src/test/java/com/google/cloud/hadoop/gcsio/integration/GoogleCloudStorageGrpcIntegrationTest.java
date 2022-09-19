package com.google.cloud.hadoop.gcsio.integration;

import static com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper.assertByteArrayEquals;
import static com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper.assertObjectContent;
import static com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper.writeObject;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.cloud.hadoop.gcsio.AssertingLogHandler;
import com.google.cloud.hadoop.gcsio.EventLoggingHttpRequestInitializer;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorage;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageGrpcTracingInterceptor;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageImpl;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageItemInfo;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageOptions.MetricsSink;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions.Fadvise;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageTestUtils;
import com.google.cloud.hadoop.gcsio.GrpcRequestTracingInfo;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper.TestBucketHelper;
import com.google.cloud.hadoop.util.AsyncWriteChannelOptions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class GoogleCloudStorageGrpcIntegrationTest {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // Prefix this name with the prefix used in other gcs io integrate tests once it's whitelisted by
  // GCS to access gRPC API.
  private static final String BUCKET_NAME_PREFIX = "gcs-grpc-team";

  private static final TestBucketHelper BUCKET_HELPER = new TestBucketHelper(BUCKET_NAME_PREFIX);

  private static final String BUCKET_NAME = BUCKET_HELPER.getUniqueBucketName("shared");

  private final boolean tdEnabled;

  @Rule public final TestName testName = new TestName();

  private Stopwatch stopwatch = Stopwatch.createStarted();

  @Parameters
  // We want to test this entire class with both gRPC-LB and Traffic Director
  // Some of our internal endpoints only work with TD
  public static Iterable<Boolean> tdEnabled() {
    return Boolean.parseBoolean(System.getenv("GCS_TEST_ONLY_RUN_WITH_TD_ENABLED"))
        ? ImmutableList.of(true)
        : ImmutableList.of(false, true);
  }

  public GoogleCloudStorageGrpcIntegrationTest(boolean tdEnabled) {
    this.tdEnabled = tdEnabled;
  }

  private GoogleCloudStorageOptions.Builder configureOptionsWithTD() {
    logger.atInfo().log("Creating client with tdEnabled %s", this.tdEnabled);
    return GoogleCloudStorageTestUtils.configureDefaultOptions()
        .setTrafficDirectorEnabled(this.tdEnabled);
  }

  private GoogleCloudStorage createGoogleCloudStorage() throws IOException {
    return new GoogleCloudStorageImpl(
        configureOptionsWithTD().build(), GoogleCloudStorageTestHelper.getCredential());
  }

  private GoogleCloudStorage createGoogleCloudStorage(
      AsyncWriteChannelOptions asyncWriteChannelOptions) throws IOException {
    return new GoogleCloudStorageImpl(
        configureOptionsWithTD().setWriteChannelOptions(asyncWriteChannelOptions).build(),
        GoogleCloudStorageTestHelper.getCredential());
  }

  @BeforeClass
  public static void createBuckets() throws IOException {
    GoogleCloudStorage rawStorage =
        new GoogleCloudStorageImpl(
            GoogleCloudStorageTestUtils.configureDefaultOptions().build(),
            GoogleCloudStorageTestHelper.getCredential());
    rawStorage.createBucket(BUCKET_NAME);
  }

  @AfterClass
  public static void cleanupBuckets() throws IOException {
    GoogleCloudStorage rawStorage =
        new GoogleCloudStorageImpl(
            GoogleCloudStorageTestUtils.configureDefaultOptions().build(),
            GoogleCloudStorageTestHelper.getCredential());
    BUCKET_HELPER.cleanup(rawStorage);
  }

  @Before
  public void setup() {
    stopwatch = Stopwatch.createStarted();
  }

  @Test
  public void testCreateObject() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testCreateObject_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 512);

    assertObjectContent(rawStorage, objectToCreate, objectBytes);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testCreateExistingObject() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testCreateExistingObject_Object");
    writeObject(rawStorage, objectToCreate, /* objectSize= */ 128);

    GoogleCloudStorageItemInfo createdItemInfo = rawStorage.getItemInfo(objectToCreate);
    assertThat(createdItemInfo.exists()).isTrue();
    assertThat(createdItemInfo.getSize()).isEqualTo(128);

    byte[] overwriteBytesToWrite = writeObject(rawStorage, objectToCreate, /* objectSize= */ 256);

    GoogleCloudStorageItemInfo overwrittenItemInfo = rawStorage.getItemInfo(objectToCreate);
    assertThat(overwrittenItemInfo.exists()).isTrue();
    assertThat(overwrittenItemInfo.getSize()).isEqualTo(256);
    assertObjectContent(rawStorage, objectToCreate, overwriteBytesToWrite);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testCreateEmptyObject() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testCreateEmptyObject_Object");

    rawStorage.createEmptyObject(objectToCreate);

    GoogleCloudStorageItemInfo itemInfo = rawStorage.getItemInfo(objectToCreate);

    assertThat(itemInfo.exists()).isTrue();
    assertThat(itemInfo.getSize()).isEqualTo(0);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testCreateInvalidObject() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testCreateInvalidObject_InvalidObject\n");

    assertThrows(
        IOException.class, () -> writeObject(rawStorage, objectToCreate, /* objectSize= */ 10));

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpen() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId objectToCreate = new StorageResourceId(BUCKET_NAME, "testOpen_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 100);

    assertObjectContent(rawStorage, objectToCreate, objectBytes);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenWithMetricsEnabled() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage =
        new GoogleCloudStorageImpl(
            GoogleCloudStorageTestHelper.getStandardOptionBuilder()
                .setMetricsSink(MetricsSink.CLOUD_MONITORING)
                .build(),
            GoogleCloudStorageTestHelper.getCredential());
    StorageResourceId objectToCreate = new StorageResourceId(BUCKET_NAME, "testOpen_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 100);
    assertObjectContent(rawStorage, objectToCreate, objectBytes);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenWithTracingLogEnabled() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AssertingLogHandler assertingHandler = new AssertingLogHandler();
    Logger grpcTracingLogger =
        assertingHandler.getLoggerForClass(
            GoogleCloudStorageGrpcTracingInterceptor.class.getName());

    AssertingLogHandler jsonLogHander = new AssertingLogHandler();
    Logger jsonTracingLogger =
        jsonLogHander.getLoggerForClass(EventLoggingHttpRequestInitializer.class.getName());

    try {
      GoogleCloudStorage rawStorage =
          new GoogleCloudStorageImpl(
              GoogleCloudStorageTestUtils.configureDefaultOptions()
                  .setTraceLogEnabled(true)
                  .build(),
              GoogleCloudStorageTestHelper.getCredential());

      StorageResourceId objectToCreate1 = new StorageResourceId(BUCKET_NAME, "testOpen_Object1");
      StorageResourceId objectToCreate2 = new StorageResourceId(BUCKET_NAME, "testOpen_Object2");

      int objectSize = 100000;
      byte[] objectBytes1 = writeObject(rawStorage, objectToCreate1, /* objectSize= */ objectSize);
      assertThat(getFilteredEvents(assertingHandler)).hasSize(6 * 2);

      byte[] objectBytes2 =
          writeObject(rawStorage, objectToCreate2, /* objectSize= */ objectSize * 2);
      assertThat(getFilteredEvents(assertingHandler)).hasSize(6 * 4);

      assertObjectContent(rawStorage, objectToCreate1, objectBytes1);
      assertThat(getFilteredEvents(assertingHandler)).hasSize(6 * 5);

      assertObjectContent(rawStorage, objectToCreate2, objectBytes2);
      assertThat(getFilteredEvents(assertingHandler)).hasSize(6 * 6);

      List<Map<String, Object>> traceEvents = getFilteredEvents(assertingHandler);
      int outboundMessageSentIndex = 2;
      int inboundMessageReadIndex = 4;

      int getUploadId1StartIndex = 0;
      int writeContent1StartIndex = 6;
      int writeContent2StartIndex = 6 * 3;
      int readContent1StartIndex = 6 * 4;
      int readContent2StartIndex = 6 * 5;

      int outboundMessageSentIndexUploadId1 = getUploadId1StartIndex + outboundMessageSentIndex;
      int inboundMessageReadIndexUploadId1 = getUploadId1StartIndex + inboundMessageReadIndex;
      int outboundMessageSentWriteContent1 = writeContent1StartIndex + outboundMessageSentIndex;
      int outboundMessageSentWriteContent2 = writeContent2StartIndex + outboundMessageSentIndex;
      int inboundMessageReadIndexWriteContent1 = writeContent1StartIndex + inboundMessageReadIndex;
      int outboundMessageSentReadContent1 = readContent1StartIndex + outboundMessageSentIndex;
      int inboundMessageReadIndexReadContent1 = readContent1StartIndex + inboundMessageReadIndex;
      int outboundMessageSentReadContent2 = readContent2StartIndex + outboundMessageSentIndex;
      int inboundMessageReadIndexReadContent2 = readContent2StartIndex + inboundMessageReadIndex;

      Map<String, Object> outboundUploadId1Details =
          traceEvents.get(outboundMessageSentIndexUploadId1);
      Map<String, Object> outboundWriteContent1Details =
          traceEvents.get(outboundMessageSentWriteContent1);
      Map<String, Object> outboundWriteContent2Details =
          traceEvents.get(outboundMessageSentWriteContent2);
      Map<String, Object> inboundUploadId1Details =
          traceEvents.get(inboundMessageReadIndexUploadId1);
      Map<String, Object> inboundWriteContent1Details =
          traceEvents.get(inboundMessageReadIndexWriteContent1);
      Map<String, Object> outboundReadContent1Details =
          traceEvents.get(outboundMessageSentReadContent1);
      Map<String, Object> inboundReadContent1Details =
          traceEvents.get(inboundMessageReadIndexReadContent1);
      Map<String, Object> outboundReadContent2Details =
          traceEvents.get(outboundMessageSentReadContent2);
      Map<String, Object> inboundReadContent2Details =
          traceEvents.get(inboundMessageReadIndexReadContent2);

      verifyTrace(outboundUploadId1Details, "write", "testOpen_Object1", "outboundMessageSent()");
      verifyTrace(
          outboundWriteContent1Details, "write", "testOpen_Object1", "outboundMessageSent()");
      verifyTrace(inboundUploadId1Details, "write", "testOpen_Object1", "inboundMessageRead()");
      verifyTrace(inboundWriteContent1Details, "write", "testOpen_Object1", "inboundMessageRead()");
      verifyTrace(outboundReadContent1Details, "read", "testOpen_Object1", "outboundMessageSent()");
      verifyTrace(inboundReadContent1Details, "read", "testOpen_Object1", "inboundMessageRead()");
      verifyTrace(outboundReadContent2Details, "read", "testOpen_Object2", "outboundMessageSent()");
      verifyTrace(inboundReadContent2Details, "read", "testOpen_Object2", "inboundMessageRead()");

      verifyWireSizeDifferenceWithinRange(
          outboundWriteContent1Details, outboundWriteContent2Details, objectSize, 50);

      verifyWireSizeDifferenceWithinRange(
          inboundReadContent1Details, inboundReadContent2Details, objectSize, 50);

      assertingHandler.verifyCommonTraceFields();
      jsonLogHander.verifyJsonLogFields(BUCKET_NAME, "testOpen_Object");
      jsonLogHander.assertLogCount(4);

    } finally {
      grpcTracingLogger.removeHandler(jsonLogHander);
      jsonTracingLogger.removeHandler(jsonLogHander);
    }

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  // The wiresize is usually greater than the content size by a few bytes. Hence, checking that the
  // actual wire
  // size is within the range. Also checking the difference b/w wireSize b/w two calls is within a
  // bound of few bytes of the expected value.
  private void verifyWireSizeDifferenceWithinRange(
      Map<String, Object> event1, Map<String, Object> event2, int sizeDifference, int range) {
    double wireSize1 = (double) event1.get("optionalWireSize");
    double wireSize2 = (double) event2.get("optionalWireSize");
    double diff = wireSize2 - wireSize1;
    assertThat(diff).isGreaterThan(sizeDifference - range);
    assertThat(diff).isLessThan(sizeDifference + range);
  }

  // inboundTrailers() event is missing in some scenarios. Hence, filtering it out to make the
  // test non-flaky.
  private List<Map<String, Object>> getFilteredEvents(AssertingLogHandler assertingHandler) {
    return assertingHandler.getAllLogRecords().stream()
        .filter((a) -> !a.get("details").equals("inboundTrailers()"))
        .collect(Collectors.toList());
  }

  private void verifyTrace(
      Map<String, Object> traceDetails, String requestType, String objectName, String methodName) {
    Gson gson = new Gson();
    assertEquals(methodName, traceDetails.get("details"));
    assertTrue(traceDetails.containsKey("elapsedmillis"));

    GrpcRequestTracingInfo requestTracingInfo =
        gson.fromJson(traceDetails.get("requestinfo").toString(), GrpcRequestTracingInfo.class);
    assertEquals("grpc", requestTracingInfo.getApi());
    assertEquals(requestType, requestTracingInfo.getRequestType());
    assertEquals(objectName, requestTracingInfo.getObjectName());
  }

  @Test
  public void testOpenNonExistentItem() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    Throwable throwable =
        assertThrows(
            IOException.class,
            () ->
                rawStorage.open(
                    new StorageResourceId(BUCKET_NAME, "testOpenNonExistentItem_Object")));
    assertThat(throwable).hasMessageThat().contains("Item not found");

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenEmptyObject() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId resourceId = new StorageResourceId(BUCKET_NAME, "testOpenEmptyObject_Object");
    rawStorage.createEmptyObject(resourceId);

    assertObjectContent(rawStorage, resourceId, new byte[0]);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  @Ignore("Ignore")
  public void testOpenLargeObject() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    StorageResourceId resourceId = new StorageResourceId(BUCKET_NAME, "testOpenLargeObject_Object");

    int partitionsCount = 50;
    byte[] partition =
        writeObject(rawStorage, resourceId, /* partitionSize= */ 10 * 1024 * 1024, partitionsCount);

    assertObjectContent(rawStorage, resourceId, partition, partitionsCount);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenObjectWithChecksum() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testOpenObjectWithChecksum_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 100);

    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    assertObjectContent(rawStorage, objectToCreate, readOptions, objectBytes);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenObjectWithSeek() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testOpenObjectWithSeek_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 100);
    int offset = 10;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, offset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        offset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenObjectWithSeekOverBounds() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testOpenObjectWithSeekOverBounds_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 4 * 1024 * 1024);
    int offset = 3 * 1024 * 1024;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, offset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        offset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testOpenObjectWithSeekLimits() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testOpenObjectWithSeekOverBounds_Object");
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ 1024);
    int offset = 100;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, offset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder()
            .setInplaceSeekLimit(50)
            .setGrpcChecksumsEnabled(true)
            .build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        offset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadFooterDataWithGrpcChecksums() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testOpenObjectWithSeekToFooter_Object");
    int objectSize = 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);
    int minRangeRequestSize = 200;
    int offset = objectSize - minRangeRequestSize / 2;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, offset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder()
            .setInplaceSeekLimit(50)
            .setMinRangeRequestSize(minRangeRequestSize)
            .setFadvise(Fadvise.RANDOM)
            .setGrpcChecksumsEnabled(true)
            .build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        offset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadCachedFooterData() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testReadCachedFooterData_Object");
    int objectSize = 10 * 1024 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);

    int minRangeRequestSize = 200;
    int footerOffset = objectSize - minRangeRequestSize / 2;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, footerOffset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setMinRangeRequestSize(minRangeRequestSize).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        footerOffset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadSeekToFooterData() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testReadSeekToFooterData_Object");
    int objectSize = 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);

    int minRangeRequestSize = 200;
    int offset = objectSize - minRangeRequestSize / 4;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, offset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setMinRangeRequestSize(minRangeRequestSize).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        offset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadObjectCachedAsFooter() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testReadSeekToFooterData_Object");
    int objectSize = 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);

    int minRangeRequestSize = 4 * 1024;
    int offset = 0;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, offset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setMinRangeRequestSize(minRangeRequestSize).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        offset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testPartialReadFooterDataWithSingleChunk() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testPartialReadFooterDataWithSingleChunk_Object");
    int objectSize = 2 * 1024 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);

    int minRangeRequestSize = 1024; // server responds in 2MB chunks by default
    int readOffset = objectSize / 2;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, readOffset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setMinRangeRequestSize(minRangeRequestSize).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        readOffset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testPartialReadFooterDataWithMultipleChunks() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testPartialReadFooterDataWithMultipleChunks_Object");
    int objectSize = 10 * 1024 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);

    int minRangeRequestSize = 4 * 1024; // server responds in 2MB chunks by default
    int readOffset = objectSize / 2;
    byte[] trimmedObjectBytes = Arrays.copyOfRange(objectBytes, readOffset, objectBytes.length);
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setMinRangeRequestSize(minRangeRequestSize).build();
    assertObjectContent(
        rawStorage,
        objectToCreate,
        readOptions,
        trimmedObjectBytes,
        /* expectedBytesCount= */ 1,
        readOffset);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testPartialReadFooterDataWithinSegment() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testPartialReadFooterDataWithinSegment_Object");
    int objectSize = 10 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);

    int minRangeRequestSize = 4 * 1024;
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setMinRangeRequestSize(minRangeRequestSize).build();
    int readOffset = 7 * 1024;
    try (SeekableByteChannel readChannel = rawStorage.open(objectToCreate, readOptions)) {
      byte[] segmentBytes = new byte[100];
      ByteBuffer segmentBuffer = ByteBuffer.wrap(segmentBytes);
      readChannel.position(readOffset);
      readChannel.read(segmentBuffer);
      byte[] expectedSegment =
          Arrays.copyOfRange(
              objectBytes, /* from= */ readOffset, /* to= */ readOffset + segmentBytes.length);
      assertWithMessage("Unexpected segment data read.")
          .that(segmentBytes)
          .isEqualTo(expectedSegment);
    }

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testPartialRead() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    int segmentSize = 553;
    int segmentCount = 5;

    StorageResourceId resourceId =
        new StorageResourceId(BUCKET_NAME, "testReadPartialObjects_Object");
    byte[] data = writeObject(rawStorage, resourceId, /* objectSize= */ segmentCount * segmentSize);

    byte[][] readSegments = new byte[segmentCount][segmentSize];
    try (SeekableByteChannel readChannel = rawStorage.open(resourceId)) {
      for (int i = 0; i < segmentCount; i++) {
        ByteBuffer segmentBuffer = ByteBuffer.wrap(readSegments[i]);
        int bytesRead = readChannel.read(segmentBuffer);
        assertThat(bytesRead).isEqualTo(segmentSize);
        byte[] expectedSegment =
            Arrays.copyOfRange(
                data, /* from= */ i * segmentSize, /* to= */ i * segmentSize + segmentSize);
        assertWithMessage("Unexpected segment data read.")
            .that(readSegments[i])
            .isEqualTo(expectedSegment);
      }
    }

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadSeekToOffsetGreaterThanMinRangeRequestSize() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(
            BUCKET_NAME, "testReadSeekToOffsetGreaterThanMinRangeRequestSize_Object");
    int objectSize = 20 * 1024;
    int inPlaceSeekLimit = 8 * 1024;
    int minRangeRequestSize = 4 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);
    int totalBytes = 1;
    byte[] readArray = new byte[totalBytes];
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder()
            .setInplaceSeekLimit(inPlaceSeekLimit)
            .setMinRangeRequestSize(minRangeRequestSize)
            .setGrpcChecksumsEnabled(false)
            .setFadvise(Fadvise.RANDOM)
            .build();
    SeekableByteChannel readableByteChannel = rawStorage.open(objectToCreate, readOptions);
    int newPosition = 7 * 1024;
    readableByteChannel.position(newPosition);
    ByteBuffer readBuffer = ByteBuffer.wrap(readArray);
    int bytesRead = readableByteChannel.read(readBuffer);
    byte[] trimmedObjectBytes =
        Arrays.copyOfRange(objectBytes, newPosition, newPosition + totalBytes);
    byte[] readBufferByteArray = Arrays.copyOf(readBuffer.array(), readBuffer.limit());

    assertEquals(totalBytes, bytesRead);
    assertByteArrayEquals(trimmedObjectBytes, readBufferByteArray);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadBeyondRangeWithFadviseRandom() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testReadBeyondRangeWithFadviseRandom_Object");
    int objectSize = 20 * 1024;
    int inPlaceSeekLimit = 8 * 1024;
    int minRangeRequestSize = 4 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);
    int totalBytes = 2 * 1024;
    byte[] readArray = new byte[totalBytes];
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder()
            .setInplaceSeekLimit(inPlaceSeekLimit)
            .setMinRangeRequestSize(minRangeRequestSize)
            .setGrpcChecksumsEnabled(false)
            .setFadvise(Fadvise.RANDOM)
            .build();
    SeekableByteChannel readableByteChannel = rawStorage.open(objectToCreate, readOptions);
    int newPosition = 7 * 1024;
    readableByteChannel.position(newPosition);
    ByteBuffer readBuffer = ByteBuffer.wrap(readArray);
    int bytesRead = readableByteChannel.read(readBuffer);
    byte[] trimmedObjectBytes =
        Arrays.copyOfRange(objectBytes, newPosition, newPosition + totalBytes);
    byte[] readBufferByteArray = Arrays.copyOf(readBuffer.array(), readBuffer.limit());

    assertEquals(totalBytes, bytesRead);
    assertByteArrayEquals(trimmedObjectBytes, readBufferByteArray);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadBeyondRangeWithFadviseAuto() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testReadBeyondRangeWithFadviseAuto_Object");
    int objectSize = 20 * 1024;
    int inPlaceSeekLimit = 8 * 1024;
    int minRangeRequestSize = 4 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);
    int totalBytes = 2 * 1024;
    byte[] readArray = new byte[totalBytes];
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder()
            .setInplaceSeekLimit(inPlaceSeekLimit)
            .setMinRangeRequestSize(minRangeRequestSize)
            .setGrpcChecksumsEnabled(false)
            .setFadvise(Fadvise.AUTO)
            .build();
    SeekableByteChannel readableByteChannel = rawStorage.open(objectToCreate, readOptions);
    int newPosition = 7 * 1024;
    readableByteChannel.position(newPosition);
    ByteBuffer readBuffer = ByteBuffer.wrap(readArray);
    int bytesRead = readableByteChannel.read(readBuffer);
    byte[] trimmedObjectBytes =
        Arrays.copyOfRange(objectBytes, newPosition, newPosition + totalBytes);
    byte[] readBufferByteArray = Arrays.copyOf(readBuffer.array(), readBuffer.limit());

    assertEquals(totalBytes, bytesRead);
    assertByteArrayEquals(trimmedObjectBytes, readBufferByteArray);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testReadBeyondRangeWithFadviseSequential() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    AsyncWriteChannelOptions asyncWriteChannelOptions = AsyncWriteChannelOptions.builder().build();
    GoogleCloudStorage rawStorage = createGoogleCloudStorage(asyncWriteChannelOptions);
    StorageResourceId objectToCreate =
        new StorageResourceId(BUCKET_NAME, "testReadBeyondRangeWithFadviseSequential_Object");
    int objectSize = 20 * 1024;
    int inPlaceSeekLimit = 8 * 1024;
    int minRangeRequestSize = 4 * 1024;
    byte[] objectBytes = writeObject(rawStorage, objectToCreate, /* objectSize= */ objectSize);
    int totalBytes = 2 * 1024;
    byte[] readArray = new byte[totalBytes];
    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder()
            .setInplaceSeekLimit(inPlaceSeekLimit)
            .setMinRangeRequestSize(minRangeRequestSize)
            .setGrpcChecksumsEnabled(false)
            .setFadvise(Fadvise.SEQUENTIAL)
            .build();
    SeekableByteChannel readableByteChannel = rawStorage.open(objectToCreate, readOptions);
    int newPosition = 7 * 1024;
    readableByteChannel.position(newPosition);
    ByteBuffer readBuffer = ByteBuffer.wrap(readArray);
    int bytesRead = readableByteChannel.read(readBuffer);
    byte[] trimmedObjectBytes =
        Arrays.copyOfRange(objectBytes, newPosition, newPosition + totalBytes);
    byte[] readBufferByteArray = Arrays.copyOf(readBuffer.array(), readBuffer.limit());

    assertEquals(totalBytes, bytesRead);
    assertByteArrayEquals(trimmedObjectBytes, readBufferByteArray);

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }

  @Test
  public void testChannelClosedException() throws IOException {
    logger.atInfo().log(
        "Running test: name=%s; tdenabled=%s",
        testName.getMethodName(), Boolean.toString(tdEnabled));
    GoogleCloudStorage rawStorage = createGoogleCloudStorage();
    int totalBytes = 1200;

    StorageResourceId resourceId =
        new StorageResourceId(BUCKET_NAME, "testChannelClosedException_Object");
    writeObject(rawStorage, resourceId, /* objectSize= */ totalBytes);

    byte[] readArray = new byte[totalBytes];
    SeekableByteChannel readableByteChannel = rawStorage.open(resourceId);
    ByteBuffer readBuffer = ByteBuffer.wrap(readArray);
    readBuffer.limit(5);
    readableByteChannel.read(readBuffer);
    assertThat(readableByteChannel.position()).isEqualTo(readBuffer.position());

    readableByteChannel.close();
    readBuffer.clear();

    assertThrows(ClosedChannelException.class, () -> readableByteChannel.read(readBuffer));

    logger.atInfo().log(
        "Running test: name=%s - completed; duration=%s",
        testName.getMethodName(), stopwatch.elapsed().getSeconds());
  }
}
