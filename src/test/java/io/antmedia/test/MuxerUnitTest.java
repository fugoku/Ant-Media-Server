package io.antmedia.test;


import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP8;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY;

import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_stream_get_side_data;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_ATTACHMENT;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_DATA;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_SUBTITLE;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.bytedeco.ffmpeg.global.avutil.av_get_default_channel_layout;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.sf.ehcache.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.RandomUtils;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.tika.io.IOUtils;
import org.awaitility.Awaitility;
import org.bytedeco.ffmpeg.avcodec.AVBSFContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.SizeTPointer;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.red5.codec.IAudioStreamCodec;
import org.red5.codec.IVideoStreamCodec;
import org.red5.codec.StreamCodecInfo;
import org.red5.io.ITag;
import org.red5.io.flv.impl.FLVReader;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IStreamCapableConnection;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.net.rtmp.event.CachedEvent;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.scope.WebScope;
import org.red5.server.service.mp4.impl.MP4Service;
import org.red5.server.stream.AudioCodecFactory;
import org.red5.server.stream.ClientBroadcastStream;
import org.red5.server.stream.VideoCodecFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.AppSettings;
import io.antmedia.RecordType;
import io.antmedia.datastore.db.DataStore;
import io.antmedia.datastore.db.DataStoreFactory;
import io.antmedia.datastore.db.InMemoryDataStore;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.datastore.db.types.Endpoint;
import io.antmedia.datastore.db.types.StreamInfo;
import io.antmedia.integration.AppFunctionalV2Test;
import io.antmedia.integration.MuxingTest;
import io.antmedia.muxer.HLSMuxer;
import io.antmedia.muxer.RecordMuxer;
import io.antmedia.muxer.IAntMediaStreamHandler;
import io.antmedia.muxer.Mp4Muxer;
import io.antmedia.muxer.MuxAdaptor;
import io.antmedia.muxer.Muxer;
import io.antmedia.muxer.RtmpMuxer;
import io.antmedia.muxer.WebMMuxer;
import io.antmedia.muxer.parser.AACConfigParser;
import io.antmedia.muxer.parser.AACConfigParser.AudioObjectTypes;
import io.antmedia.muxer.parser.SpsParser;
import io.antmedia.muxer.parser.codec.AACAudio;
import io.antmedia.plugin.PacketFeeder;
import io.antmedia.plugin.api.IPacketListener;
import io.antmedia.plugin.api.StreamParametersInfo;
import io.antmedia.rest.model.Result;
import io.antmedia.storage.AmazonS3StorageClient;
import io.antmedia.storage.StorageClient;
import io.antmedia.test.utils.VideoInfo;
import io.antmedia.test.utils.VideoProber;
import io.vertx.core.Vertx;
import org.springframework.test.util.ReflectionTestUtils;

@ContextConfiguration(locations = {"test.xml"})
//@ContextConfiguration(classes = {AppConfig.class})
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class MuxerUnitTest extends AbstractJUnit4SpringContextTests {

	protected static Logger logger = LoggerFactory.getLogger(MuxerUnitTest.class);
	protected static final int BUFFER_SIZE = 10240;


	byte[] extradata_original = new byte[] {0x00,0x00,0x00,0x01, 0x67,0x64,0x00,0x15, (byte)0xAC,(byte)0xB2,0x03,(byte)0xC1, 0x7F,(byte)0xCB,(byte)0x80,
			(byte)0x88, 0x00,0x00,0x03,0x00, 0x08,0x00,0x00,0x03, 0x01, (byte)0x94,0x78,(byte)0xB1, 0x72,0x40,0x00,0x00, 0x00,0x01,0x68,
			(byte)0xEB, (byte)0xC3, (byte)0xCB, (byte)0x22, (byte)0xC0};

	byte[] sps_pps_avc = new byte[]{0x01,  0x64, 0x00, 0x15, (byte)0xFF,
			(byte)0xE1, 0x00, 0x1A, 0x67, 0x64,0x00, 0x15, (byte)0xAC, (byte)0xB2, 0x03,
			(byte)0xC1, 0x7F, (byte)0xCB, (byte)0x80, (byte)0x88, 0x00, 0x00, 0x03, 0x00,
			0x08, 0x00, 0x00, 0x03, 0x01, (byte)0x94, (byte)0x78, (byte)0xB1, 0x72, 0x40, 0x01, 0x00, 0x06, 0x68,
			(byte)0xEB, (byte)0xC3, (byte)0xCB, 0x22, (byte)0xC0};

	byte[] aacConfig = new byte[] {0x12, 0x10, 0x56, (byte)0xE5, 0x00};

	protected WebScope appScope;
	private AppSettings appSettings;

	static {
		System.setProperty("red5.deployment.type", "junit");
		System.setProperty("red5.root", ".");
	}

	private DataStore datastore;

	@BeforeClass
	public static void beforeClass() {
		//avformat.av_register_all();
		avformat.avformat_network_init();
		avutil.av_log_set_level(avutil.AV_LOG_INFO);
	}

	@Before
	public void before() {
		File webApps = new File("webapps");
		if (!webApps.exists()) {
			webApps.mkdirs();
		}
		File junit = new File(webApps, "junit");
		if (!junit.exists()) {
			junit.mkdirs();
		}


		//reset values in the bean
		getAppSettings().resetDefaults();
		getAppSettings().setAddDateTimeToMp4FileName(false);
	}

	@After
	public void after() {


		try {
			AppFunctionalV2Test.delete(new File("webapps"));
		} catch (IOException e) {
			e.printStackTrace();
		}

		//reset values in the bean
		getAppSettings().resetDefaults();
		getAppSettings().setAddDateTimeToMp4FileName(false);
	}

	@Rule
	public TestRule watcher = new TestWatcher() {
		protected void starting(Description description) {
			System.out.println("Starting test: " + description.getMethodName());
		}

		protected void failed(Throwable e, Description description) {
			System.out.println("Failed test: " + description.getMethodName());
		}

		protected void finished(Description description) {
			System.out.println("Finishing test: " + description.getMethodName());
		}

	};
	private Vertx vertx;

	public class StreamPacket implements IStreamPacket {

		private ITag readTag;
		private IoBuffer data;

		public StreamPacket(ITag tag) {
			readTag = tag;
			data = readTag.getBody();
		}

		@Override
		public int getTimestamp() {
			return readTag.getTimestamp();
		}

		@Override
		public byte getDataType() {
			return readTag.getDataType();
		}

		@Override
		public IoBuffer getData() {
			return data;
		}

		public void setData(IoBuffer data) {
			this.data = data;
		}
	}


	@Test
	public void testConvertAvcExtraDataToAnnexB()
	{
		byte[] sps_pps_avc = new byte[]{0x01,  0x64, 0x00, 0x15, (byte)0xFF,
				(byte)0xE1, 0x00, 0x1A, 0x67, 0x64,0x00, 0x15, (byte)0xAC, (byte)0xB2, 0x03,
				(byte)0xC1, 0x7F, (byte)0xCB, (byte)0x80, (byte)0x88, 0x00, 0x00, 0x03, 0x00,
				0x08, 0x00, 0x00, 0x03, 0x01, (byte)0x94, (byte)0x78, (byte)0xB1, 0x72, 0x40, 0x01, 0x00, 0x06, 0x68,
				(byte)0xEB, (byte)0xC3, (byte)0xCB, 0x22, (byte)0xC0};

		assertEquals(43, sps_pps_avc.length);

		byte[] extradata_annexb = MuxAdaptor.getAnnexbExtradata(sps_pps_avc);



		assertEquals(extradata_annexb.length, extradata_original.length);

		for (int i = 0; i < extradata_original.length; i++) {
			assertEquals(extradata_annexb[i], extradata_original[i]);
		}

		SpsParser spsParser = new SpsParser(extradata_annexb, 5);

		assertEquals(480, spsParser.getWidth());
		assertEquals(360, spsParser.getHeight());

	}
	
	@Test
	public void testAddAudioStream() 
	{
		Mp4Muxer mp4Muxer = new Mp4Muxer(Mockito.mock(StorageClient.class), vertx, "");
		appScope = (WebScope) applicationContext.getBean("web.scope");
		
		mp4Muxer.init(appScope, "test",0, "", 0);
		assertEquals(0, mp4Muxer.getOutputFormatContext().nb_streams());
		AVChannelLayout layout = new AVChannelLayout();
		av_channel_layout_default(layout, 1);
		assertTrue(mp4Muxer.addAudioStream(44100, layout, AV_CODEC_ID_AAC, 0));
		
		assertEquals(1, mp4Muxer.getOutputFormatContext().nb_streams());
		
	}
	
	@Test
	public void testAddExtradata() 
	{
		Mp4Muxer mp4Muxer = new Mp4Muxer(Mockito.mock(StorageClient.class), vertx, "");
		appScope = (WebScope) applicationContext.getBean("web.scope");
		mp4Muxer.init(appScope, "test",0, "", 0);
		
		AVCodecContext codecContext = new AVCodecContext();
		codecContext.width(640);
		codecContext.height(480);
		
		mp4Muxer.contextChanged(codecContext, 0);
		
		codecContext.extradata_size(10);
		codecContext.extradata(new BytePointer(10));
		
		mp4Muxer.contextChanged(codecContext, 0);
		
		AVPacket pkt = new AVPacket();
		
		mp4Muxer.addExtradataIfRequired(pkt, true);
		
		assertEquals(10, mp4Muxer.getTmpPacket().size());
		
		pkt.data(new BytePointer(15)).size(15);
		mp4Muxer.addExtradataIfRequired(pkt, false);
		assertEquals(10, mp4Muxer.getTmpPacket().size());
		
		mp4Muxer.addExtradataIfRequired(pkt, true);
		assertEquals(25, mp4Muxer.getTmpPacket().size());
	}
	
	@Test
	public void testInitVideoBitstreamFilter() 
	{
		Mp4Muxer mp4Muxer = new Mp4Muxer(Mockito.mock(StorageClient.class), vertx, "");
		appScope = (WebScope) applicationContext.getBean("web.scope");
		mp4Muxer.init(appScope, "test",0, "", 0);
		mp4Muxer.getOutputFormatContext();
		
		mp4Muxer.setBitstreamFilter("h264_mp4toannexb");
		AVCodecParameters codecParameters = new AVCodecParameters();
		codecParameters.codec_id(AV_CODEC_ID_H264);
		codecParameters.codec_type(AVMEDIA_TYPE_VIDEO);
		AVBSFContext avbsfContext = mp4Muxer.initVideoBitstreamFilter(codecParameters, Muxer.avRationalTimeBase);
		assertNotNull(avbsfContext);
		

	}
	
	@Test
	public void testAddStream() 
	{
		Mp4Muxer mp4Muxer = new Mp4Muxer(Mockito.mock(StorageClient.class), vertx, "");
		appScope = (WebScope) applicationContext.getBean("web.scope");
		
		mp4Muxer.clearResource();
		
		mp4Muxer.init(appScope, "test",0, "", 0);
		//increase coverage
		mp4Muxer.init(appScope, "test",0, "", 0);
		
		AVCodecContext codecContext = new AVCodecContext();
		codecContext.width(640);
		codecContext.height(480);
		
		assertEquals(0, mp4Muxer.getOutputFormatContext().nb_streams());
		
		boolean addStream = mp4Muxer.addStream(null, codecContext, 0);
		assertTrue(addStream);
		
		assertEquals(1, mp4Muxer.getOutputFormatContext().nb_streams());
		
		mp4Muxer.getIsRunning().set(true);
		addStream = mp4Muxer.addStream(null, codecContext, 0);
		assertFalse(addStream);
		
		//increase coverage
		mp4Muxer.getIsRunning().set(false);
		mp4Muxer.writePacket(new AVPacket(), codecContext);
		
		//increase coverage
		mp4Muxer.writeVideoBuffer(null, 0, 0, 0, false, 0, 0);	
		mp4Muxer.writeAudioBuffer(null, 0, 0);	
			
	}
	
	@Test
	public void testContextChanged() 
	{
		Mp4Muxer mp4Muxer = new Mp4Muxer(Mockito.mock(StorageClient.class), vertx, "");
		appScope = (WebScope) applicationContext.getBean("web.scope");
		
		mp4Muxer.init(appScope, "test",0, "", 0);
		assertEquals(0, mp4Muxer.getOutputFormatContext().nb_streams());

		AVCodecContext codecContext = new AVCodecContext();
		codecContext.width(640);
		codecContext.height(480);
		assertEquals(0,mp4Muxer.getInputTimeBaseMap().size());
		mp4Muxer.contextWillChange(new AVCodecContext(), 0);
		mp4Muxer.contextChanged(codecContext, 0);
		assertEquals(1, mp4Muxer.getInputTimeBaseMap().size());
		
		assertEquals(640, mp4Muxer.getVideoWidth());
		assertEquals(480, mp4Muxer.getVideoHeight());
		
		codecContext.extradata_size(10);
		codecContext.extradata(new BytePointer(10));
		mp4Muxer.contextWillChange(new AVCodecContext(), 0);
		mp4Muxer.contextChanged(codecContext, 1);
		
		assertEquals(2, mp4Muxer.getInputTimeBaseMap().size());
		
		codecContext = new AVCodecContext();
		codecContext.codec_type(AVMEDIA_TYPE_AUDIO);
		mp4Muxer.contextWillChange(new AVCodecContext(), 0);
		mp4Muxer.contextChanged(codecContext, 3);
		
		assertEquals(3, mp4Muxer.getInputTimeBaseMap().size());
		
	}
	
	@Test
	public void testErrorDefinition() 
	{
		String errorDefinition = Muxer.getErrorDefinition(-1);
		assertNotNull(errorDefinition);
	}

	@Test
	public void testParseAACConfig() 
	{
		AACConfigParser aacParser = new AACConfigParser(aacConfig, 0);
		assertEquals(44100, aacParser.getSampleRate());
		assertEquals(2, aacParser.getChannelCount());
		assertEquals(AACConfigParser.AudioObjectTypes.AAC_LC, aacParser.getObjectType());
		assertEquals(1024, aacParser.getFrameSize());;
		assertFalse(aacParser.isErrorOccured());

		aacParser = new AACConfigParser(new byte[] {0, 0}, 0);
		assertTrue(aacParser.isErrorOccured());		


		aacParser = new AACConfigParser(new byte[] {(byte) 0x80, 0}, 0);
		assertTrue(aacParser.isErrorOccured());	

		aacParser = new AACConfigParser(new byte[] {(byte) 0x17, 0}, 0);
		assertTrue(aacParser.isErrorOccured());

		aacParser = new AACConfigParser(new byte[] {(byte) 0x12, (byte)0x77}, 0);
		assertTrue(aacParser.isErrorOccured());

		aacParser = new AACConfigParser(new byte[] {(byte) 0x12, (byte)0x17}, 0);
		assertFalse(aacParser.isErrorOccured());

		aacParser = new AACConfigParser(new byte[] {(byte) 0x12, (byte)0x38}, 0);
		assertFalse(aacParser.isErrorOccured());

	}

	@Test
	public void testAACAudio() {
		AACAudio aacAudio = new AACAudio();

		assertEquals("AAC", aacAudio.getName());

		IoBuffer result = IoBuffer.allocate(4);
		result.setAutoExpand(true);
		result.put(aacConfig);
		result.rewind();

		assertFalse(aacAudio.canHandleData(result));
		result.limit(0);

		assertFalse(aacAudio.canHandleData(result));
		assertTrue(aacAudio.addData(result));
		assertNull(aacAudio.getDecoderConfiguration());
	}


	@Test
	public void testFFmpegReadPacket()
	{
		AVFormatContext inputFormatContext = avformat.avformat_alloc_context();
		AVInputFormat findInputFormat = avformat.av_find_input_format("flv");
		if (avformat_open_input(inputFormatContext, (String) "src/test/resources/test.flv", findInputFormat,
				(AVDictionary) null) < 0) {
			//	return false;
		}

		long startFindStreamInfoTime = System.currentTimeMillis();

		int ret = avformat_find_stream_info(inputFormatContext, (AVDictionary) null);
		if (ret < 0) {
		}

		AVCodecParameters codecpar = inputFormatContext.streams(1).codecpar();

		byte[] data2 = new byte[codecpar.extradata_size()];


		codecpar.extradata().position(0).get(data2);

		AVPacket pkt = new AVPacket();


		logger.info("codecpar.bit_rate(): {}\n" +
				"		codecpar.bits_per_coded_sample(): {} \n" +
				"		codecpar.bits_per_raw_sample(): {} \n" +
				"		codecpar.block_align(): {}\n" +
				"		codecpar.channel_layout(): {}\n" +
				"		codecpar.channels(): {}\n" +
				"		codecpar.codec_id(): {}\n" +
				"		codecpar.codec_tag(): {}\n" +
				"		codecpar.codec_type(): {} \n" +
				"		codecpar.format(): {}\n" +
				"		codecpar.frame_size():{} \n" +
				"		codecpar.level():{} \n" +
				"		codecpar.profile():{} \n" +
				"		codecpar.sample_rate(): {}",

				codecpar.bit_rate(),
				codecpar.bits_per_coded_sample(),
				codecpar.bits_per_raw_sample(),
				codecpar.block_align(),
				codecpar.channel_layout(),
				codecpar.channels(),
				codecpar.codec_id(),
				codecpar.codec_tag(),
				codecpar.codec_type(),
				codecpar.format(),
				codecpar.frame_size(),
				codecpar.level(),
				codecpar.profile(),
				codecpar.sample_rate());



		int i = 0;
		try {

			while ((ret = av_read_frame(inputFormatContext, pkt)) >= 0) 
			{

				if (inputFormatContext.streams(pkt.stream_index()).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) 
				{
					pkt.data().position(0).limit(pkt.size());


					logger.info("		pkt.duration():{} \n" + 
							"					pkt.flags(): {} \n" + 
							"					pkt.pos(): {}\n" + 
							"					pkt.size(): {}\n" + 
							"					pkt.stream_index():{} ",
							pkt.duration(),
							pkt.flags(),
							pkt.pos(),
							pkt.size(),
							pkt.stream_index());




					byte[] data = new byte[(int) pkt.size()];

					pkt.data().get(data);

					FileOutputStream fos = new FileOutputStream("audio_ffmpeg" + i);

					fos.write(data);

					fos.close();

					i++;
					if (i == 5) {

						break;
					}

				}

			}

		}
		catch (Exception e) {
			e.printStackTrace();
		}

	}


	@Test
	public void testAudioTag() {

		try {
			File file = new File("src/test/resources/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			int i = 0;
			while (flvReader.hasMoreTags()) {
				ITag readTag = flvReader.readTag();
				StreamPacket streamPacket = new StreamPacket(readTag);


				if (streamPacket.getDataType() == Constants.TYPE_AUDIO_DATA)
				{
					int bodySize = streamPacket.getData().limit();

					byte[] bodyBuf = new byte[bodySize-2];
					// put the bytes into the array
					//streamPacket.getData().position(5);
					streamPacket.getData().position(2);
					streamPacket.getData().get(bodyBuf);
					// get the audio or video codec identifier
					streamPacket.getData().position(0);

					FileOutputStream fos = new FileOutputStream("audio_tag" + i);
					fos.write(bodyBuf);


					fos.close();
					i++;
					if (i == 5) {
						break;
					}



				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}


	@Test
	public void testIsCodecSupported() {
		appScope = (WebScope) applicationContext.getBean("web.scope");

		Mp4Muxer mp4Muxer = new Mp4Muxer(null, null, "streams");
		mp4Muxer.init(appScope, "test", 0, null, 0);


		WebMMuxer webMMuxer = new WebMMuxer(null, null, "streams");
		webMMuxer.init(appScope, "test", 0, null, 0);


		assertFalse(webMMuxer.isCodecSupported(AV_CODEC_ID_H264));
		assertTrue(mp4Muxer.isCodecSupported(AV_CODEC_ID_H264));

		assertFalse(mp4Muxer.isCodecSupported(AV_CODEC_ID_VP8));
		assertTrue(webMMuxer.isCodecSupported(AV_CODEC_ID_VP8));


	}

	@Test
	public void testStreamIndex() {
		Mp4Muxer mp4Muxer = new Mp4Muxer(null, vertx, "streams");

		appScope = (WebScope) applicationContext.getBean("web.scope");
		mp4Muxer.init(appScope, "test", 0, null, 0);

		SpsParser spsParser = new SpsParser(extradata_original, 5);

		AVCodecParameters codecParameters = new AVCodecParameters();
		codecParameters.width(spsParser.getWidth());
		codecParameters.height(spsParser.getHeight());
		codecParameters.codec_id(AV_CODEC_ID_H264);
		codecParameters.codec_type(AVMEDIA_TYPE_VIDEO);
		codecParameters.extradata_size(sps_pps_avc.length);
		BytePointer extraDataPointer = new BytePointer(sps_pps_avc);
		codecParameters.extradata(extraDataPointer);
		codecParameters.format(AV_PIX_FMT_YUV420P);
		codecParameters.codec_tag(0);

		AVRational rat = new AVRational().num(1).den(1000);
		//mp4Muxer.addVideoStream(spsParser.getWidth(), spsParser.getHeight(), rat, AV_CODEC_ID_H264, 0, true, codecParameters);

		mp4Muxer.addStream(codecParameters, rat, 5);
		mp4Muxer.setPreviewPath("/path");

		assertTrue(mp4Muxer.getRegisteredStreamIndexList().contains(5));


		HLSMuxer hlsMuxer = new HLSMuxer(vertx, Mockito.mock(StorageClient.class), "streams", 0, "http://example.com", false);
		hlsMuxer.setHlsParameters( null, null, null, null, null);
		hlsMuxer.init(appScope, "test", 0, null,0);
		hlsMuxer.addStream(codecParameters, rat, 50);
		assertTrue(hlsMuxer.getRegisteredStreamIndexList().contains(50));
		hlsMuxer.writeTrailer();


		RtmpMuxer rtmpMuxer = new RtmpMuxer("any_url", vertx);
		rtmpMuxer.init(appScope, "test", 0, null, 0);
		rtmpMuxer.addStream(codecParameters, rat, 50);

	}

	@Test
	public void testGetAudioCodecParameters() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		logger.info("Application / web scope: {}", appScope);
		assertEquals(1, appScope.getDepth());

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(Mockito.mock(ClientBroadcastStream.class), false, appScope));
		muxAdaptor.setAudioDataConf(new byte[] {0, 0});

		assertNull(muxAdaptor.getAudioCodecParameters());

		try {
			muxAdaptor.setEnableAudio(true);
			assertTrue(muxAdaptor.isEnableAudio());

			muxAdaptor.prepare();

			assertFalse(muxAdaptor.isEnableAudio());
		} 
		catch (Exception e) 
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testStopRtmpStreamingWhenRtmpMuxerNull() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		logger.info("Application / web scope: {}", appScope);
		assertTrue(appScope.getDepth() == 1);

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));
		String rtmpUrl = "rtmp://test.com/live/stream";
		Integer resolution = 0;

		ConcurrentHashMap<String, String> statusMap = Mockito.mock(ConcurrentHashMap.class);
		ReflectionTestUtils.setField(muxAdaptor, "statusMap", statusMap);
		Mockito.doReturn(null).when(muxAdaptor).getRtmpMuxer(rtmpUrl);

		Mockito.doReturn(null).when(statusMap).getValueOrDefault(rtmpUrl, null);
		assertTrue(muxAdaptor.stopRtmpStreaming(rtmpUrl, resolution).isSuccess());

		Mockito.doReturn(IAntMediaStreamHandler.BROADCAST_STATUS_ERROR).when(statusMap).getValueOrDefault(rtmpUrl, null);
		assertTrue(muxAdaptor.stopRtmpStreaming(rtmpUrl, resolution).isSuccess());

		Mockito.doReturn(IAntMediaStreamHandler.BROADCAST_STATUS_FAILED).when(statusMap).getValueOrDefault(rtmpUrl, null);
		assertTrue(muxAdaptor.stopRtmpStreaming(rtmpUrl, resolution).isSuccess());

		Mockito.doReturn(IAntMediaStreamHandler.BROADCAST_STATUS_FINISHED).when(statusMap).getValueOrDefault(rtmpUrl, null);
		assertTrue(muxAdaptor.stopRtmpStreaming(rtmpUrl, resolution).isSuccess());

		Mockito.doReturn(IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING).when(statusMap).getValueOrDefault(rtmpUrl, null);
		assertFalse(muxAdaptor.stopRtmpStreaming(rtmpUrl, resolution).isSuccess());
	}

	@Test
	public void testMuxerStartStopRTMPStreaming() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		logger.info("Application / web scope: {}", appScope);
		assertTrue(appScope.getDepth() == 1);

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));

		muxAdaptor.setIsRecording(true);
		Mockito.doReturn(true).when(muxAdaptor).prepareMuxer(Mockito.any());

		String rtmpUrl = "rtmp://localhost";
		int resolutionHeight = 480;
		Result result = muxAdaptor.startRtmpStreaming(rtmpUrl, resolutionHeight);
		assertFalse(result.isSuccess());

		muxAdaptor.setHeight(480);
		result = muxAdaptor.startRtmpStreaming(rtmpUrl, resolutionHeight);
		assertTrue(result.isSuccess());


		result = muxAdaptor.startRtmpStreaming(rtmpUrl, 0);
		assertTrue(result.isSuccess());



		RtmpMuxer rtmpMuxer = Mockito.mock(RtmpMuxer.class);
		Mockito.doReturn(rtmpMuxer).when(muxAdaptor).getRtmpMuxer(Mockito.any());
		muxAdaptor.stopRtmpStreaming(rtmpUrl, resolutionHeight);
		Mockito.verify(rtmpMuxer).writeTrailer();


		muxAdaptor.stopRtmpStreaming(rtmpUrl, 0);
		Mockito.verify(rtmpMuxer, Mockito.times(2)).writeTrailer();


		muxAdaptor.stopRtmpStreaming(rtmpUrl, 360);
		//it should be 2 times again because 360 and 480 don't match
		Mockito.verify(rtmpMuxer, Mockito.times(2)).writeTrailer();

	}

	@Test
	public void testMuxerEndpointStatusUpdate() {

		appScope = (WebScope) applicationContext.getBean("web.scope");
		logger.info("Application / web scope: {}", appScope);
		assertTrue(appScope.getDepth() == 1);

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));
		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId("test");
		} catch (Exception e) {
			e.printStackTrace();
		}

		muxAdaptor.setBroadcast(broadcast);
		Endpoint rtmpEndpoint = new Endpoint();
		String rtmpUrl = "rtmp://localhost/LiveApp/test12";
		rtmpEndpoint.setRtmpUrl(rtmpUrl);
		List<Endpoint> endpointList = new ArrayList<>();
		endpointList.add(rtmpEndpoint);

		broadcast.setEndPointList(endpointList);
		boolean result = muxAdaptor.init(appScope, "test", false);
		muxAdaptor.getDataStore().save(broadcast);

		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING);


		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING.equals(broadcastLocal.getEndPointList().get(0).getStatus());
		});


		muxAdaptor.getDataStore().delete(broadcast.getStreamId());

		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING);
		assertEquals(1, muxAdaptor.getEndpointStatusUpdateMap().size());

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			return 0 == muxAdaptor.getEndpointStatusUpdateMap().size();
		});

	}

	@Test
	public void testBroadcastHasBeenDeleted() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		logger.info("Application / web scope: {}", appScope);
		assertTrue(appScope.getDepth() == 1);

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));

		String rtmpUrl = "rtmp://localhost/LiveApp/test12";
		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId("test");
		} catch (Exception e) {
			e.printStackTrace();
		}

		getAppSettings().setEndpointRepublishLimit(1);
		getAppSettings().setEndpointHealthCheckPeriodMs(2000);
		muxAdaptor.setBroadcast(broadcast);

		boolean result = muxAdaptor.init(appScope, "test", false);

		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_ERROR);

		muxAdaptor.setBroadcast(null);
		Mockito.verify(muxAdaptor, timeout(3000)).clearCounterMapsAndCancelTimer(Mockito.anyString(), Mockito.anyLong());



	}
	
	@Test
	public void testRTMPCodecSupport() {
		RtmpMuxer rtmpMuxer = new RtmpMuxer(null, vertx);
		
		assertTrue(rtmpMuxer.isCodecSupported(AV_CODEC_ID_H264));
		assertTrue(rtmpMuxer.isCodecSupported(AV_CODEC_ID_AAC));
		
		assertFalse(rtmpMuxer.isCodecSupported(AV_CODEC_ID_AC3));
		
	}
	
	
	
	@Test
	public void testHLSAddStream() 
	{
		HLSMuxer hlsMuxer = new HLSMuxer(vertx,Mockito.mock(StorageClient.class), "", 7, null, false);
		appScope = (WebScope) applicationContext.getBean("web.scope");
		hlsMuxer.init(appScope, "test", 0, "", 100);
		
		assertFalse(hlsMuxer.writeHeader());
		
		AVCodecContext codecContext = new AVCodecContext();
		codecContext.width(640);
		codecContext.height(480);
		codecContext.codec_id(AV_CODEC_ID_H264);
		
		boolean addStream = hlsMuxer.addStream(null, codecContext, 0);
		assertTrue(addStream);
		
		assertNull(hlsMuxer.getBitStreamFilter());
	}
	
	@Test
	public void testAVWriteFrame() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		vertx = (Vertx)appScope.getContext().getApplicationContext().getBean(IAntMediaStreamHandler.VERTX_BEAN_NAME);

		RtmpMuxer rtmpMuxer = Mockito.spy(new RtmpMuxer(null, vertx));
		
		AVFormatContext context = new AVFormatContext(null);
		int ret = avformat_alloc_output_context2(context, null, "flv", "test.flv");
		
		
		AVPacket pkt = av_packet_alloc();
		
		rtmpMuxer.avWriteFrame(pkt, context);
		
		Mockito.verify(rtmpMuxer).addExtradataIfRequired(pkt, false);
		
		av_packet_free(pkt);
		avformat_free_context(context);
	}
	
	@Test
	public void testRTMPAddStream() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		vertx = (Vertx)appScope.getContext().getApplicationContext().getBean(IAntMediaStreamHandler.VERTX_BEAN_NAME);

		RtmpMuxer rtmpMuxer = new RtmpMuxer(null, vertx);
		
		AVCodecContext codecContext = new AVCodecContext();
		codecContext.width(640);
		codecContext.height(480);
		
		
		boolean addStream = rtmpMuxer.addStream(null, codecContext, 0);
		assertFalse(addStream);
		
		
		codecContext.codec_id(AV_CODEC_ID_H264);
		addStream = rtmpMuxer.addStream(null, codecContext, BUFFER_SIZE);
		assertTrue(addStream);
		
		
		addStream = rtmpMuxer.addVideoStream(480, 360, Muxer.avRationalTimeBase, AV_CODEC_ID_H264, 0, true, null);
		assertTrue(addStream);
		
	}
	
	@Test
	public void testRTMPPrepareIO() {
		appScope = (WebScope) applicationContext.getBean("web.scope");
		vertx = (Vertx)appScope.getContext().getApplicationContext().getBean(IAntMediaStreamHandler.VERTX_BEAN_NAME);

		RtmpMuxer rtmpMuxer = new RtmpMuxer("rtmp://no_server", vertx);
		
		//it should return false because there is no thing to send.
		assertFalse(rtmpMuxer.prepareIO());
		
		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> {
			return rtmpMuxer.getStatus().equals(IAntMediaStreamHandler.BROADCAST_STATUS_FAILED);
		});
		
		assertFalse(rtmpMuxer.prepareIO());
		
	}
	
	

	@Test
	public void testRTMPHealthCheckProcess(){
		appScope = (WebScope) applicationContext.getBean("web.scope");
		logger.info("Application / web scope: {}", appScope);
		assertTrue(appScope.getDepth() == 1);

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));
		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId("test");
		} catch (Exception e) {
			e.printStackTrace();
		}

		getAppSettings().setEndpointRepublishLimit(1);
		getAppSettings().setEndpointHealthCheckPeriodMs(2000);

		muxAdaptor.setBroadcast(broadcast);
		Endpoint rtmpEndpoint = new Endpoint();
		String rtmpUrl = "rtmp://localhost/LiveApp/test12";
		rtmpEndpoint.setRtmpUrl(rtmpUrl);
		List<Endpoint> endpointList = new ArrayList<>();
		endpointList.add(rtmpEndpoint);

		broadcast.setEndPointList(endpointList);
		boolean result = muxAdaptor.init(appScope, "test", false);
		muxAdaptor.getDataStore().save(broadcast);

		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_ERROR);

		assertTrue(muxAdaptor.getIsHealthCheckStartedMap().get(rtmpUrl));

		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING);
		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return muxAdaptor.getIsHealthCheckStartedMap().getOrDefault(rtmpUrl, false) == false;
		});

		//ERROR SCENARIO
		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_ERROR);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return IAntMediaStreamHandler.BROADCAST_STATUS_ERROR.equals(broadcastLocal.getEndPointList().get(0).getStatus());
		});

		assertTrue(muxAdaptor.getIsHealthCheckStartedMap().get(rtmpUrl));
		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(()-> {
			return muxAdaptor.getIsHealthCheckStartedMap().getOrDefault(rtmpUrl, false) == false;
		});

		//SET BROADCASTING AGAIN
		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING);


		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING.equals(broadcastLocal.getEndPointList().get(0).getStatus());
		});

		//FAILED SCENARIO
		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_FAILED);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return IAntMediaStreamHandler.BROADCAST_STATUS_FAILED.equals(broadcastLocal.getEndPointList().get(0).getStatus());
		});

		assertTrue(muxAdaptor.getIsHealthCheckStartedMap().get(rtmpUrl));
		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(()-> {
			return muxAdaptor.getIsHealthCheckStartedMap().getOrDefault(rtmpUrl, false) == false;
		});

		//FINISHED SCENARIO
		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_FINISHED);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return IAntMediaStreamHandler.BROADCAST_STATUS_FINISHED.equals(broadcastLocal.getEndPointList().get(0).getStatus());
		});

		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(()-> {
			return muxAdaptor.getIsHealthCheckStartedMap().getOrDefault(rtmpUrl, false) == false;
		});


		//RETRY LIMIT EXCEEDED SCENARIO
		getAppSettings().setEndpointRepublishLimit(0);

		muxAdaptor.endpointStatusUpdated(rtmpUrl, IAntMediaStreamHandler.BROADCAST_STATUS_ERROR);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(()-> {
			Broadcast broadcastLocal = muxAdaptor.getDataStore().get(broadcast.getStreamId());
			return IAntMediaStreamHandler.BROADCAST_STATUS_ERROR.equals(broadcastLocal.getEndPointList().get(0).getStatus());
		});

		assertTrue(muxAdaptor.getIsHealthCheckStartedMap().get(rtmpUrl));
		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(()-> {
			return muxAdaptor.getIsHealthCheckStartedMap().getOrDefault(rtmpUrl, false) == false;
		});

		verify(muxAdaptor, Mockito.timeout(5000)).sendEndpointErrorNotifyHook(rtmpUrl);

	}
	@Test
	public void testRTMPWriteCrash(){

		appScope = (WebScope) applicationContext.getBean("web.scope");

		SpsParser spsParser = new SpsParser(extradata_original, 5);

		AVCodecParameters codecParameters = new AVCodecParameters();
		codecParameters.width(spsParser.getWidth());
		codecParameters.height(spsParser.getHeight());
		codecParameters.codec_id(AV_CODEC_ID_H264);
		codecParameters.codec_type(AVMEDIA_TYPE_VIDEO);
		codecParameters.extradata_size(sps_pps_avc.length);
		BytePointer extraDataPointer = new BytePointer(sps_pps_avc);
		codecParameters.extradata(extraDataPointer);
		codecParameters.format(AV_PIX_FMT_YUV420P);
		codecParameters.codec_tag(0);
		AVRational rat = new AVRational().num(1).den(1000);

		RtmpMuxer rtmpMuxer = new RtmpMuxer("any_url", vertx);

		rtmpMuxer.init(appScope, "test", 0, null, 0);
		rtmpMuxer.addStream(codecParameters, rat, 50);
		assertTrue(rtmpMuxer.openIO());

		rtmpMuxer.setIsRunning(new AtomicBoolean(true));

		//This was a crash if we don't check headerWritten after we initialize the context and get isRunning true
		//To test the scenarios of that crash;
		rtmpMuxer.writeTrailer();

		//This should work since the trailer is not written yet
		rtmpMuxer.writeHeader();

		//This should work since header is written
		rtmpMuxer.writeTrailer();

		//This is for testing writeHeader after writeTrailer.
		rtmpMuxer.writeHeader();
	}

	@Test
	public void testMp4MuxerDirectStreaming() {

		appScope = (WebScope) applicationContext.getBean("web.scope");
		vertx = (Vertx)appScope.getContext().getApplicationContext().getBean(IAntMediaStreamHandler.VERTX_BEAN_NAME);

		Mp4Muxer mp4Muxer = new Mp4Muxer(null, vertx, "streams");

		mp4Muxer.init(appScope, "test", 0, null, 0);


		SpsParser spsParser = new SpsParser(extradata_original, 5);

		AVCodecParameters codecParameters = new AVCodecParameters();
		codecParameters.width(spsParser.getWidth());
		codecParameters.height(spsParser.getHeight());
		codecParameters.codec_id(AV_CODEC_ID_H264);
		codecParameters.codec_type(AVMEDIA_TYPE_VIDEO);
		codecParameters.extradata_size(sps_pps_avc.length);
		BytePointer extraDataPointer = new BytePointer(sps_pps_avc);
		codecParameters.extradata(extraDataPointer);
		codecParameters.format(AV_PIX_FMT_YUV420P);
		codecParameters.codec_tag(0);

		AVRational rat = new AVRational().num(1).den(1000);
		//mp4Muxer.addVideoStream(spsParser.getWidth(), spsParser.getHeight(), rat, AV_CODEC_ID_H264, 0, true, codecParameters);

		mp4Muxer.addStream(codecParameters, rat, 0);


		AACConfigParser aacConfigParser = new AACConfigParser(aacConfig, 0);
		AVCodecParameters audioCodecParameters = new AVCodecParameters();
		audioCodecParameters.sample_rate(aacConfigParser.getSampleRate());

		AVChannelLayout chLayout = new AVChannelLayout();
		avutil.av_channel_layout_default(chLayout, aacConfigParser.getChannelCount());
		audioCodecParameters.ch_layout(chLayout);

		audioCodecParameters.codec_id(AV_CODEC_ID_AAC);
		audioCodecParameters.codec_type(AVMEDIA_TYPE_AUDIO);

		if (aacConfigParser.getObjectType() == AudioObjectTypes.AAC_LC) {

			audioCodecParameters.profile(AVCodecContext.FF_PROFILE_AAC_LOW);
		}
		else if (aacConfigParser.getObjectType() == AudioObjectTypes.AAC_LTP) {

			audioCodecParameters.profile(AVCodecContext.FF_PROFILE_AAC_LTP);
		}
		else if (aacConfigParser.getObjectType() == AudioObjectTypes.AAC_MAIN) {

			audioCodecParameters.profile(AVCodecContext.FF_PROFILE_AAC_MAIN);
		}
		else if (aacConfigParser.getObjectType() == AudioObjectTypes.AAC_SSR) {

			audioCodecParameters.profile(AVCodecContext.FF_PROFILE_AAC_SSR);
		}

		audioCodecParameters.frame_size(aacConfigParser.getFrameSize());
		audioCodecParameters.format(AV_SAMPLE_FMT_FLTP);
		BytePointer extraDataPointer2 = new BytePointer(aacConfig);
		audioCodecParameters.extradata(extraDataPointer2);
		audioCodecParameters.extradata_size(aacConfig.length);
		audioCodecParameters.codec_tag(0);


		mp4Muxer.addStream(audioCodecParameters, rat, 1);


		mp4Muxer.prepareIO();

		int i = 0;
		try {
			File file = new File("src/test/resources/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);
			while (flvReader.hasMoreTags())
			{
				ITag readTag = flvReader.readTag();
				StreamPacket streamPacket = new StreamPacket(readTag);


				if (streamPacket.getDataType() == Constants.TYPE_VIDEO_DATA)
				{
					int bodySize = streamPacket.getData().limit();

					byte frameType = streamPacket.getData().position(0).get();

					// get the audio or video codec identifier

					ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bodySize-5);
					byteBuffer.put(streamPacket.getData().buf().position(5));

					mp4Muxer.writeVideoBuffer(byteBuffer, streamPacket.getTimestamp(), 0, 0, (frameType & 0xF0) == IVideoStreamCodec.FLV_FRAME_KEY, 0, streamPacket.getTimestamp());

				}
				else if (streamPacket.getDataType() == Constants.TYPE_AUDIO_DATA) {
					i++;
					if (i == 1) {
						continue;
					}
					int bodySize = streamPacket.getData().limit();

					ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bodySize-2);
					byteBuffer.put(streamPacket.getData().buf().position(2));

					mp4Muxer.writeAudioBuffer(byteBuffer, 1, streamPacket.getTimestamp());

				}

			}

			mp4Muxer.writeTrailer();


			Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> MuxingTest.testFile(mp4Muxer.getFile().getAbsolutePath(), 697000));


		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}


	}

	@Test
	public void testMuxAdaptorEnableSettingsPreviewCreatePeriod() {

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.info("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(null, false, appScope);
		int createPreviewPeriod = (int) (Math.random() * 10000);
		assertNotEquals(0, createPreviewPeriod);
		getAppSettings().setCreatePreviewPeriod(createPreviewPeriod);
		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId("test");
		} catch (Exception e) {
			e.printStackTrace();
		}

		muxAdaptor.setBroadcast(broadcast);
		boolean result = muxAdaptor.init(appScope, "test", false);
		assertTrue(result);

		assertEquals(createPreviewPeriod, muxAdaptor.getPreviewCreatePeriod());
	}

	@Test
	public void testMuxingSimultaneously() {

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.info("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		getAppSettings().setMp4MuxingEnabled(true);
		getAppSettings().setAddDateTimeToMp4FileName(false);
		getAppSettings().setHlsMuxingEnabled(true);
		getAppSettings().setDeleteHLSFilesOnEnded(false);

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		//this value should be -1. It means it is uninitialized
		assertEquals(0, muxAdaptor.getPacketTimeList().size());
		File file = null;

		try {
			file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());
			String streamId = "test" + (int) (Math.random() * 991000);
			Broadcast broadcast = new Broadcast();
			broadcast.setStreamId(streamId);
			getDataStore().save(broadcast);
			boolean result = muxAdaptor.init(appScope, streamId, false);
			assertTrue(result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			assertTrue(muxAdaptor.isRecording());

			muxAdaptor.stop(true);

			flvReader.close();


			Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());

			assertFalse(muxAdaptor.isRecording());

			Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).until(() -> {
				File f1 = new File(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath());
				File f2 = new File(muxAdaptor.getMuxerList().get(1).getFile().getAbsolutePath());
				return f1.exists() && f2.exists();
			});


			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath()));
			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(1).getFile().getAbsolutePath()));

		} catch (Exception e) {
			e.printStackTrace();
			fail("excsereption:" + e);
		}

	}


	@Test
	public void testStressMp4Muxing() {

		long startTime = System.nanoTime();
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		try {

			ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
			StreamCodecInfo info = new StreamCodecInfo();
			clientBroadcastStream.setCodecInfo(info);

			getAppSettings().setMaxAnalyzeDurationMS(50000);
			getAppSettings().setHlsMuxingEnabled(false);
			getAppSettings().setMp4MuxingEnabled(true);
			getAppSettings().setAddDateTimeToMp4FileName(false);

			List<MuxAdaptor> muxAdaptorList = new ArrayList<>();
			for (int j = 0; j < 5; j++) {
				MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);
				muxAdaptorList.add(muxAdaptor);
			}
			{

				File file = new File("src/test/resources/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
				final FLVReader flvReader = new FLVReader(file);

				logger.debug("f path: " + file.getAbsolutePath());
				assertTrue(file.exists());

				for (Iterator<MuxAdaptor> iterator = muxAdaptorList.iterator(); iterator.hasNext(); ) {
					MuxAdaptor muxAdaptor = (MuxAdaptor) iterator.next();
					String streamId = "test" + (int) (Math.random() * 991000);
					Broadcast broadcast = new Broadcast();
					broadcast.setStreamId(streamId);
					getDataStore().save(broadcast);

					boolean result = muxAdaptor.init(appScope, streamId, false);
					assertTrue(result);
					muxAdaptor.start();
					logger.info("Mux adaptor instance initialized for {}", streamId);
				}

				feedMuxAdaptor(flvReader, muxAdaptorList, info);

				for (MuxAdaptor muxAdaptor : muxAdaptorList) {
					logger.info("Check if is recording: {}", muxAdaptor.getStreamId());
					Awaitility.await().atMost(50, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
						return muxAdaptor.isRecording();
					});
				}

				for (MuxAdaptor muxAdaptor : muxAdaptorList) {
					muxAdaptor.stop(true);
				}


				flvReader.close();

				for (MuxAdaptor muxAdaptor : muxAdaptorList) {
					Awaitility.await().atMost(50, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).until(() -> {
						logger.info("Check if it's not recording: {}", muxAdaptor.getStreamId());
						return !muxAdaptor.isRecording();
					});
				}


			}

			int count = 0;
			for (MuxAdaptor muxAdaptor : muxAdaptorList) {
				List<Muxer> muxerList = muxAdaptor.getMuxerList();
				for (Muxer abstractMuxer : muxerList) {
					if (abstractMuxer instanceof Mp4Muxer) {
						assertTrue(MuxingTest.testFile(abstractMuxer.getFile().getAbsolutePath(), 697000));
						count++;
					}
				}
			}

			assertEquals(muxAdaptorList.size(), count);

			long diff = (System.nanoTime() - startTime) / 1000000;

			System.out.println(" time diff: " + diff + " ms");
		} catch (Exception e) {

			e.printStackTrace();
			fail(e.getMessage());
		}

		getAppSettings().setHlsMuxingEnabled(true);

	}

	@Test
	public void testMuxerStreamType() 
	{
		assertEquals("video", MuxAdaptor.getStreamType(AVMEDIA_TYPE_VIDEO));
		assertEquals("audio", MuxAdaptor.getStreamType(AVMEDIA_TYPE_AUDIO));
		assertEquals("data", MuxAdaptor.getStreamType(AVMEDIA_TYPE_DATA));
		assertEquals("subtitle", MuxAdaptor.getStreamType(AVMEDIA_TYPE_SUBTITLE));
		assertEquals("attachment", MuxAdaptor.getStreamType(AVMEDIA_TYPE_ATTACHMENT));	
		assertEquals("not_known", MuxAdaptor.getStreamType(55));	
	}


	@Test
	public void testMp4MuxingWithWithMultipleDepth() {
		File file = testMp4Muxing("test_test/test");
		assertEquals("test.mp4", file.getName());

		file = testMp4Muxing("dir1/dir2/file");
		assertTrue(file.exists());

		file = testMp4Muxing("dir1/dir2/dir3/file");
		assertTrue(file.exists());

		file = testMp4Muxing("dir1/dir2/dir3/dir4/file");
		assertTrue(file.exists());
	}

	@Test
	public void testMp4MuxingWithSameName() {
		logger.info("running testMp4MuxingWithSameName");

		Application.resetFields();

		assertTrue(Application.id.isEmpty());
		assertTrue(Application.file.isEmpty());
		assertTrue(Application.duration.isEmpty());

		File file = testMp4Muxing("test_test");
		assertEquals("test_test.mp4", file.getName());

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return "test_test".equals(Application.id.get(0));
		});

		assertEquals("test_test", Application.id.get(0));

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return "test_test.mp4".equals(Application.file.get(0).getName());
		});

		assertEquals("test_test.mp4", Application.file.get(0).getName());
		assertNotEquals(0L, (long)Application.duration.get(0));

		Application.resetFields();

		assertTrue(Application.id.isEmpty());
		assertTrue(Application.file.isEmpty());
		assertTrue(Application.duration.isEmpty());

		file = testMp4Muxing("test_test");
		assertEquals("test_test_1.mp4", file.getName());

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return "test_test".equals(Application.id.get(0));
		});

		assertEquals("test_test", Application.id.get(0));

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return "test_test_1.mp4".equals(Application.file.get(0).getName());
		});

		assertEquals("test_test_1.mp4", Application.file.get(0).getName());
		assertNotEquals(0L, Application.duration);

		Application.resetFields();

		assertTrue(Application.id.isEmpty());
		assertTrue(Application.file.isEmpty());
		assertTrue(Application.duration.isEmpty());

		file = testMp4Muxing("test_test");
		assertEquals("test_test_2.mp4", file.getName());

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return "test_test".equals(Application.id.get(0));
		});

		assertEquals("test_test", Application.id.get(0));

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return "test_test_2.mp4".equals(Application.file.get(0).getName());
		});

		assertEquals("test_test_2.mp4", Application.file.get(0).getName());
		assertNotEquals(0L, (long)Application.duration.get(0));

		logger.info("leaving testMp4MuxingWithSameName");
	}


	@Test
	public void testBaseStreamFileServiceBug() {
		MP4Service mp4Service = new MP4Service();

		String fileName = mp4Service.prepareFilename("mp4:1");
		assertEquals("1.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:12");
		assertEquals("12.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:123");
		assertEquals("123.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:1234");
		assertEquals("1234.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:12345");
		assertEquals("12345.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:123456");
		assertEquals("123456.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:1.mp4");
		assertEquals("1.mp4", fileName);

		fileName = mp4Service.prepareFilename("mp4:123456789.mp4");
		assertEquals("123456789.mp4", fileName);

	}

	@Test
	public void testApplicationStreamLimit()
	{
		AntMediaApplicationAdapter appAdaptor = Mockito.spy((AntMediaApplicationAdapter) applicationContext.getBean("web.handler"));
		assertNotNull(appAdaptor);

		String streamId = "stream " + (int)(Math.random()*10000);

		appAdaptor.setDataStore(new InMemoryDataStore("dbtest"));
		long activeBroadcastCount = appAdaptor.getDataStore().getActiveBroadcastCount();

		logger.info("Active broadcast count: {}", activeBroadcastCount);
		long broadcastCount = appAdaptor.getDataStore().getBroadcastCount();
		logger.info("Total broadcast count: {}", broadcastCount);
		if (activeBroadcastCount > 0)
		{
			long pageSize = broadcastCount / 50 + 1;

			for (int i = 0; i < pageSize; i++)
			{
				List<Broadcast> broadcastList = appAdaptor.getDataStore().getBroadcastList(i*50, 50, "", "status", "", "");

				for (Broadcast broadcast : broadcastList)
				{
					logger.info("Broadcast id: {} status:{}", broadcast.getStreamId(), broadcast.getStatus());
				}
			}
		}

		activeBroadcastCount = appAdaptor.getDataStore().getActiveBroadcastCount();

		appSettings.setIngestingStreamLimit(2);


		appAdaptor.startPublish(streamId, 0, null);


		streamId = "stream " + (int)(Math.random()*10000);

		appAdaptor.startPublish(streamId, 0, null);

		long activeBroadcastCountFinal = activeBroadcastCount;
		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
		.until(() -> {
			return activeBroadcastCountFinal + 2 == appAdaptor.getDataStore().getActiveBroadcastCount();
		});

		if (activeBroadcastCount == 1) {
			Mockito.verify(appAdaptor, timeout(1000)).stopStreaming(Mockito.any());
		}

		streamId = "stream " + (int)(Math.random()*10000);

		appAdaptor.startPublish(streamId, 0, null);

		Mockito.verify(appAdaptor, timeout(1000).times((int)activeBroadcastCount+1)).stopStreaming(Mockito.any());

	}

	@Test
	public void testAbsoluteStartTimeMs()
	{
		AntMediaApplicationAdapter appAdaptor = ((AntMediaApplicationAdapter) applicationContext.getBean("web.handler"));
		assertNotNull(appAdaptor);

		AntMediaApplicationAdapter spyAdaptor = Mockito.spy(appAdaptor);

		ClientBroadcastStream stream = Mockito.mock(ClientBroadcastStream.class);

		String streamId = "stream" + (int)(Math.random() * 10000000);
		Mockito.when(stream.getPublishedName()).thenReturn(streamId);

		doReturn(stream).when(spyAdaptor).getBroadcastStream(Mockito.any(), Mockito.any());

		spyAdaptor.startPublish(streamId,0, null);


		long absoluteTimeMS = System.currentTimeMillis();
		when(stream.getAbsoluteStartTimeMs()).thenReturn(absoluteTimeMS);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
		.until(() ->
		appAdaptor.getDataStore().get(streamId).getAbsoluteStartTimeMs() == absoluteTimeMS);

		spyAdaptor.stopPublish(stream.getPublishedName());


		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
		.until(() ->
		appAdaptor.getDataStore().get(streamId) == null);



	}


	@Test
	public void testMp4MuxingAndNotifyCallback() {

		Application app =  (Application) applicationContext.getBean("web.handler");
		AntMediaApplicationAdapter appAdaptor = Mockito.spy(app);

		doReturn(new StringBuilder("")).when(appAdaptor).notifyHook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
		assertNotNull(appAdaptor);

		//just check below value that it is not null, this is not related to this case but it should be tested
		String hookUrl = "http://google.com";
		String name = "namer123";
		Broadcast broadcast = new Broadcast(AntMediaApplicationAdapter.BROADCAST_STATUS_CREATED, name);
		broadcast.setListenerHookURL(hookUrl);
		String streamId = appAdaptor.getDataStore().save(broadcast);

		Application.resetFields();
		testMp4Muxing(streamId, false, true);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return streamId.equals(Application.id.get(0));
		});

		assertTrue(Application.id.contains(streamId));
		assertEquals(Application.file.get(0).getName(), streamId + ".mp4");
		assertTrue(Math.abs(697202l - Application.duration.get(0)) < 250);

		broadcast = appAdaptor.getDataStore().get(streamId);
		//we do not save duration of the finished live streams
		//assertEquals((long)broadcast.getDuration(), 697132L);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(()-> {
			return Application.notifyHookAction.contains(AntMediaApplicationAdapter.HOOK_ACTION_VOD_READY);
		});

		assertTrue(Application.notifyId.contains(streamId));
		assertTrue(Application.notitfyURL.contains(hookUrl));
		assertTrue(Application.notifyHookAction.contains(AntMediaApplicationAdapter.HOOK_ACTION_VOD_READY));
		assertTrue(Application.notifyVodName.contains(streamId));

		Application.resetFields();
		assertTrue(Application.notifyId.isEmpty());
		assertTrue(Application.notitfyURL.isEmpty());
		assertTrue(Application.notifyHookAction.isEmpty());

		//test with same id again
		testMp4Muxing(streamId, true, true);

		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(()-> {
			return Application.notifyHookAction.contains(AntMediaApplicationAdapter.HOOK_ACTION_VOD_READY);
		});

		assertTrue(Application.notifyId.contains(streamId));
		assertTrue(Application.notitfyURL.contains(hookUrl));
		assertTrue(Application.notifyHookAction.contains(AntMediaApplicationAdapter.HOOK_ACTION_VOD_READY));
		assertTrue(Application.notifyVodName.contains(streamId+"_1"));



		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
			return Application.id.contains(streamId);
		});
		assertEquals(Application.id.get(0), streamId);
		assertEquals(Application.file.get(0).getName(), streamId + "_1.mp4");
		assertEquals(10120L, (long)Application.duration.get(0));

		broadcast = appAdaptor.getDataStore().get(streamId);
		//we do not save duration of the finished live streams
		//assertEquals((long)broadcast.getDuration(), 10080L);

	}

	@Test
	public void testMp4MuxingHighProfileDelayedVideo() {

		String name = "high_profile_delayed_video_" + (int)(Math.random()*10000);
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();

		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		if(getDataStore().get(name) == null) {
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId(name);
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			getDataStore().save(broadcast);
		}
		getAppSettings().setMp4MuxingEnabled(true);
		getAppSettings().setHlsMuxingEnabled(false);

		logger.info("HLS muxing enabled {}", appSettings.isHlsMuxingEnabled());

		//File file = new File(getResource("test.mp4").getFile());
		File file = null;

		try {

			file = new File("src/test/resources/high_profile_delayed_video.flv");

			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());

			boolean result = muxAdaptor.init(appScope, name, false);

			assertTrue(result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			Awaitility.await().atMost(90, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());
			assertTrue(muxAdaptor.isRecording());

			muxAdaptor.stop(true);

			flvReader.close();


			Awaitility.await().atMost(40, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());
			assertFalse(muxAdaptor.isRecording());

			int finalDuration = 20000;
			Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).until(()->
			MuxingTest.testFile(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath(), finalDuration));

			assertEquals(0, MuxingTest.videoStartTimeMs);
			assertEquals(0, MuxingTest.audioStartTimeMs);

		} catch (Exception e) {
			e.printStackTrace();
			fail("exception:" + e);
		}
		logger.info("leaving testMp4Muxing");
	}


	public File testMp4Muxing(String name) {
		return testMp4Muxing(name, true, true);
	}

	@Test
	public void testMuxAdaptorClose() {

		appScope = (WebScope) applicationContext.getBean("web.scope");

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(null, false, appScope);
		String streamId = "stream_id" + (int)(Math.random()*10000);

		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId(streamId);
		} catch (Exception e) {
			e.printStackTrace();
		}

		muxAdaptor.setBroadcast(broadcast);
		boolean result = muxAdaptor.init(appScope, streamId, false);

		assertTrue(result);

		muxAdaptor.closeResources();
	}

	@Test
	public void testWriteBufferedPacket() {

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));

		muxAdaptor.setBuffering(true);
		muxAdaptor.writeBufferedPacket();
		assertTrue(muxAdaptor.isBuffering());

		muxAdaptor.setBuffering(false);
		muxAdaptor.writeBufferedPacket();
		//it should false because there is no packet in the queue
		assertTrue(muxAdaptor.isBuffering());

		Queue<IStreamPacket> bufferQueue = muxAdaptor.getBufferQueue();
		muxAdaptor.setBuffering(false);
		AVStream stream = Mockito.mock(AVStream.class);
		when(stream.time_base()).thenReturn(MuxAdaptor.TIME_BASE_FOR_MS);


		ITag tag = mock(ITag.class);
		when(tag.getTimestamp()).thenReturn(1000);

		IStreamPacket pkt = new StreamPacket(tag);

		//pkt.stream_index(0);

		bufferQueue.add(pkt);

		doNothing().when(muxAdaptor).writeStreamPacket(any());
		muxAdaptor.writeBufferedPacket();
		verify(muxAdaptor).writeStreamPacket(any());
		assertTrue(muxAdaptor.isBuffering());
		assertTrue(bufferQueue.isEmpty());

		muxAdaptor.setBuffering(false);
		muxAdaptor.setBufferingFinishTimeMs(System.currentTimeMillis());

		ITag tag2 = mock(ITag.class);
		int timeStamp = 10000;
		when(tag2.getTimestamp()).thenReturn(timeStamp);
		pkt = new StreamPacket(tag2);
		bufferQueue.add(pkt);
		muxAdaptor.writeBufferedPacket();
		assertFalse(muxAdaptor.isBuffering());

	}

	@Test
	public void testRtmpIngestBufferTime()
	{

		try {
			if (appScope == null) {
				appScope = (WebScope) applicationContext.getBean("web.scope");
				logger.debug("Application / web scope: {}", appScope);
				assertTrue(appScope.getDepth() == 1);
			}

			ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
			StreamCodecInfo info = new StreamCodecInfo();

			clientBroadcastStream.setCodecInfo(info);

			MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

			//increase max analyze duration to some higher value because it's also to close connections if packet is not received
			getAppSettings().setMaxAnalyzeDurationMS(5000);
			getAppSettings().setRtmpIngestBufferTimeMs(1000);
			getAppSettings().setMp4MuxingEnabled(false);
			getAppSettings().setHlsMuxingEnabled(false);

			File file = new File("target/test-classes/test.flv");

			String streamId = "streamId" + (int)(Math.random()*10000);
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId(streamId);
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);
			boolean result = muxAdaptor.init(appScope, streamId, false);
			assertTrue(result);

			muxAdaptor.start();


			final FLVReader flvReader = new FLVReader(file);
			boolean firstAudioPacketReceived = false;
			boolean firstVideoPacketReceived = false;
			long lastTimeStamp = 0;
			while (flvReader.hasMoreTags())
			{
				ITag readTag = flvReader.readTag();
				StreamPacket streamPacket = new StreamPacket(readTag);
				lastTimeStamp = readTag.getTimestamp();
				if (!firstAudioPacketReceived && streamPacket.getDataType() == Constants.TYPE_AUDIO_DATA)
				{
					IAudioStreamCodec audioStreamCodec = AudioCodecFactory.getAudioCodec(streamPacket.getData().position(0));
					info.setAudioCodec(audioStreamCodec);
					audioStreamCodec.addData(streamPacket.getData().position(0));
					info.setHasAudio(true);
					firstAudioPacketReceived = true;
				}
				else if (!firstVideoPacketReceived && streamPacket.getDataType() == Constants.TYPE_VIDEO_DATA)
				{
					IVideoStreamCodec videoStreamCodec = VideoCodecFactory.getVideoCodec(streamPacket.getData().position(0));
					videoStreamCodec.addData(streamPacket.getData().position(0));
					info.setVideoCodec(videoStreamCodec);
					info.setHasVideo(true);
					firstVideoPacketReceived = true;
				}


				if (lastTimeStamp < 6000) {

					muxAdaptor.packetReceived(null, streamPacket);
				}
				else {
					break;
				}
			}
			System.gc();

			Awaitility.await().atMost(5, TimeUnit.SECONDS).until(muxAdaptor::isRecording);
			//let the buffered time finish and buffering state is true
			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(muxAdaptor::isBuffering);

			//load again for 6 more seconds
			while (flvReader.hasMoreTags())
			{
				ITag readTag = flvReader.readTag();

				if (readTag.getTimestamp() - lastTimeStamp  < 6000) {
					StreamPacket streamPacket = new StreamPacket(readTag);
					muxAdaptor.packetReceived(null, streamPacket);
				}
				else {
					break;
				}
			}
			System.out.println("finish feeding");
			//buffering should be false after a while because it's loaded with 5 seconds
			Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !muxAdaptor.isBuffering());

			//after 6 seconds buffering should be also true again because it's finished
			Awaitility.await().atMost(6, TimeUnit.SECONDS).until(muxAdaptor::isBuffering);

			muxAdaptor.stop(true);

			Awaitility.await().atMost(4, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());

		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		getAppSettings().setRtmpIngestBufferTimeMs(0);

	}

	@Test
	public void testMp4Muxing() {
		File mp4File = testMp4Muxing("lkdlfkdlfkdlfk");

		VideoInfo fileInfo = VideoProber.getFileInfo(mp4File.getAbsolutePath());
		assertTrue(252 - fileInfo.videoPacketsCount<5);
		assertTrue(431 - fileInfo.audioPacketsCount<5);
	}


	public File testMp4Muxing(String name, boolean shortVersion, boolean checkDuration) {

		logger.info("running testMp4Muxing");

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();

		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		if(getDataStore().get(name) == null) {
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId(name);
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			getDataStore().save(broadcast);
		}
		getAppSettings().setMp4MuxingEnabled(true);
		getAppSettings().setHlsMuxingEnabled(false);

		logger.info("HLS muxing enabled {}", appSettings.isHlsMuxingEnabled());

		//File file = new File(getResource("test.mp4").getFile());
		File file = null;

		try {

			if (shortVersion) {
				file = new File("target/test-classes/test_short.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			} else {
				file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			}

			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());

			boolean result = muxAdaptor.init(appScope, name, false);

			assertTrue(result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			Awaitility.await().atMost(90, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());
			assertTrue(muxAdaptor.isRecording());

			muxAdaptor.stop(true);

			flvReader.close();


			Awaitility.await().atMost(40, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());
			assertFalse(muxAdaptor.isRecording());

			int duration = 697000;
			if (shortVersion) {
				duration = 10080;
			}

			if (checkDuration) {
				int finalDuration = duration;
				Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).until(()->
				MuxingTest.testFile(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath(), finalDuration));
			}
			return muxAdaptor.getMuxerList().get(0).getFile();
		} catch (Exception e) {
			e.printStackTrace();
			fail("exception:" + e);
		}
		logger.info("leaving testMp4Muxing");
		return null;
	}


	@Test
	public void testMp4MuxingSubtitledVideo() {
		getAppSettings().setMp4MuxingEnabled(true);
		getAppSettings().setAddDateTimeToMp4FileName(true);
		getAppSettings().setHlsMuxingEnabled(true);
		getAppSettings().setDeleteHLSFilesOnEnded(false);
		getAppSettings().setUploadExtensionsToS3(2);


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		//File file = new File(getResource("test.mp4").getFile());
		File file = null;

		try {

			file = new File("target/test-classes/test_video_360p_subtitle.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));


			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId("video_with_subtitle_stream");
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);

			boolean result = muxAdaptor.init(appScope, "video_with_subtitle_stream", false);
			assertTrue(result);


			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());
			muxAdaptor.stop(true);

			flvReader.close();

			Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());

			// if there is listenerHookURL, a task will be scheduled, so wait a little to make the call happen
			Thread.sleep(200);

			List<Muxer> muxerList = muxAdaptor.getMuxerList();
			Mp4Muxer mp4Muxer = null;
			for (Muxer muxer : muxerList) {
				if (muxer instanceof Mp4Muxer) {
					mp4Muxer = (Mp4Muxer) muxer;
					break;
				}
			}
			assertFalse(mp4Muxer.isUploadingToS3());

			int duration = 146401;

			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath(), duration));
			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(1).getFile().getAbsolutePath()));

		} catch (Exception e) {
			e.printStackTrace();
			fail("exception:" + e);
		}
	}

	@Test
	public void testHLSNormal() {
		testHLSMuxing("hlsmuxing_test");
	}

	@Test
	public void testUploadExtensions(){
		//av_log_set_level (40);
		int hlsListSize = 3;
		int hlsTime = 2;
		String name = "streamtestExtensions";

		getAppSettings().setMp4MuxingEnabled(true);
		getAppSettings().setAddDateTimeToMp4FileName(false);
		getAppSettings().setHlsMuxingEnabled(true);
		getAppSettings().setDeleteHLSFilesOnEnded(false);
		getAppSettings().setHlsTime(String.valueOf(hlsTime));
		getAppSettings().setHlsListSize(String.valueOf(hlsListSize));
		getAppSettings().setUploadExtensionsToS3(3);
		getAppSettings().setS3RecordingEnabled(true);


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		Vertx vertx = (Vertx) applicationContext.getBean(AntMediaApplicationAdapter.VERTX_BEAN_NAME);
		assertNotNull(vertx);

		StorageClient client = Mockito.mock(AmazonS3StorageClient.class);
		HLSMuxer hlsMuxerTester = new HLSMuxer(vertx, client, "streams",1, null, false);
		hlsMuxerTester.setHlsParameters(null, null, null, null, null);
		assertFalse(hlsMuxerTester.isUploadingToS3());


		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);
		muxAdaptor.setStorageClient(client);

		File file = null;
		try {

			file = new File("src/test/resources/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.info("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId(name);
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);
			boolean result = muxAdaptor.init(appScope, name, false);
			assert (result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());

			muxAdaptor.stop(true);

			flvReader.close();

			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());

			Thread.sleep(10000);

			List<Muxer> muxerList = muxAdaptor.getMuxerList();
			HLSMuxer hlsMuxer = null;
			Mp4Muxer mp4Muxer = null;
			for (Muxer muxer : muxerList) {
				if (muxer instanceof HLSMuxer) {
					hlsMuxer = (HLSMuxer) muxer;
					break;
				}
			}
			for (Muxer muxer : muxerList) {
				if (muxer instanceof Mp4Muxer) {
					mp4Muxer = (Mp4Muxer) muxer;
					break;
				}
			}
			assertNotNull(hlsMuxer);
			assertNotNull(mp4Muxer);
			File hlsFile = hlsMuxer.getFile();

			String hlsFilePath = hlsFile.getAbsolutePath();
			int lastIndex = hlsFilePath.lastIndexOf(".m3u8");

			String mp4Filename = hlsFilePath.substring(0, lastIndex) + ".mp4";

			//just check mp4 file is not created
			File mp4File = new File(mp4Filename);
			assertTrue(mp4File.exists());

			assertTrue(MuxingTest.testFile(hlsFile.getAbsolutePath()));

			//The setting is given 3 (011) so both enabled true
			assertTrue(hlsMuxer.isUploadingToS3());
			assertTrue(mp4Muxer.isUploadingToS3());

			File dir = new File(hlsFile.getAbsolutePath()).getParentFile();
			File[] files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts");
				}
			});

			System.out.println("ts file count:" + files.length);

			assertTrue(files.length > 0);
			assertTrue(files.length < (int) Integer.valueOf(hlsMuxer.getHlsListSize()) * (Integer.valueOf(hlsMuxer.getHlsTime()) + 1));

		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testMp4MuxingWithDirectParams() {
		Vertx vertx = (Vertx) applicationContext.getBean(AntMediaApplicationAdapter.VERTX_BEAN_NAME);
		assertNotNull(vertx);

		Mp4Muxer mp4Muxer = new Mp4Muxer(null, vertx, "streams");

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		String streamName = "stream_name_" + (int) (Math.random() * 10000);
		//init
		mp4Muxer.init(appScope, streamName, 0, null, 0);

		//add stream
		int width = 640;
		int height = 480;
		boolean addStreamResult = mp4Muxer.addVideoStream(width, height, null, AV_CODEC_ID_H264, 0, false, null);
		assertTrue(addStreamResult);

		//prepare io
		boolean prepareIOresult = mp4Muxer.prepareIO();
		assertTrue(prepareIOresult);

		try {
			FileInputStream fis = new FileInputStream("src/test/resources/frame0");
			byte[] byteArray = IOUtils.toByteArray(fis);

			fis.close();

			long now = System.currentTimeMillis();
			ByteBuffer encodedVideoFrame = ByteBuffer.wrap(byteArray);

			for (int i = 0; i < 100; i++) {
				//add packet
				mp4Muxer.writeVideoBuffer(encodedVideoFrame, now + i * 100, 0, 0, true, 0,  now + i* 100);
			}

		} catch (IOException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		//write trailer
		mp4Muxer.writeTrailer();

		Awaitility.await().atMost(20, TimeUnit.SECONDS)
		.pollInterval(1, TimeUnit.SECONDS)
		.until(() -> {
			return MuxingTest.testFile("webapps/junit/streams/" + streamName + ".mp4", 10000);
		});


	}

	/**
	 * Real functional test is under enterprise test repo
	 * It is called testReinitializeEncoderContext
	 */
	@Test
	public void testHLSMuxingWithDirectParams() {
		Vertx vertx = (Vertx) applicationContext.getBean(AntMediaApplicationAdapter.VERTX_BEAN_NAME);
		assertNotNull(vertx);

		HLSMuxer hlsMuxer = new HLSMuxer(vertx, Mockito.mock(StorageClient.class), "streams", 7 , null, false);

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		String streamName = "stream_name_" + (int) (Math.random() * 10000);
		//init
		hlsMuxer.init(appScope, streamName, 0, null, 0);

		//add stream
		int width = 640;
		int height = 480;
		boolean addStreamResult = hlsMuxer.addVideoStream(width, height, null, AV_CODEC_ID_H264, 0, false, null);
		assertTrue(addStreamResult);

		//prepare io
		boolean prepareIOresult = hlsMuxer.prepareIO();
		assertTrue(prepareIOresult);
		
		addStreamResult = hlsMuxer.addVideoStream(width, height, null, AV_CODEC_ID_H264, 0, false, null);
		assertFalse(addStreamResult);
		
		addStreamResult = hlsMuxer.addVideoStream(width, height, null, AV_CODEC_ID_HCA, 0, false, null);
		assertFalse(addStreamResult);

		try {
			FileInputStream fis = new FileInputStream("src/test/resources/frame0");
			byte[] byteArray = IOUtils.toByteArray(fis);

			fis.close();

			long now = System.currentTimeMillis();
			ByteBuffer encodedVideoFrame = ByteBuffer.wrap(byteArray);

			AVPacket videoPkt = avcodec.av_packet_alloc();
			av_init_packet(videoPkt);

			for (int i = 0; i < 100; i++) {

				/*
				 * Rotation field is used add metadata to the mp4.
				 * this method is called in directly creating mp4 from coming encoded WebRTC H264 stream
				 */
				videoPkt.stream_index(0);
				videoPkt.pts(now + i* 100);
				videoPkt.dts(now + i * 100);

				encodedVideoFrame.rewind();

				if (i==0) {
					videoPkt.flags(videoPkt.flags() | AV_PKT_FLAG_KEY);
				}
				videoPkt.data(new BytePointer(encodedVideoFrame));
				videoPkt.size(encodedVideoFrame.limit());
				videoPkt.position(0);
				videoPkt.duration(5);
				hlsMuxer.writePacket(videoPkt, new AVCodecContext());

				av_packet_unref(videoPkt);
			}

		} catch (IOException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		//write trailer
		hlsMuxer.writeTrailer();

	}

	/**
	 * Real functional test is under enterprise test repo
	 * It is called testReinitializeEncoderContext
	 */
	@Test
	public void testRecordMuxingWithDirectParams() {
		Vertx vertx = (Vertx) applicationContext.getBean(AntMediaApplicationAdapter.VERTX_BEAN_NAME);
		assertNotNull(vertx);

		Mp4Muxer mp4Muxer = new Mp4Muxer(null, vertx, "streams");

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		String streamName = "stream_name_" + (int) (Math.random() * 10000);
		//init
		mp4Muxer.init(appScope, streamName, 0, null,0 );

		//add stream
		int width = 640;
		int height = 480;
		boolean addStreamResult = mp4Muxer.addVideoStream(width, height, null, AV_CODEC_ID_H264, 0, false, null);
		assertTrue(addStreamResult);

		//prepare io
		boolean prepareIOresult = mp4Muxer.prepareIO();
		assertTrue(prepareIOresult);

		try {
			FileInputStream fis = new FileInputStream("src/test/resources/frame0");
			byte[] byteArray = IOUtils.toByteArray(fis);

			fis.close();

			long now = System.currentTimeMillis();
			ByteBuffer encodedVideoFrame = ByteBuffer.wrap(byteArray);

			AVPacket videoPkt = avcodec.av_packet_alloc();
			av_init_packet(videoPkt);

			for (int i = 0; i < 100; i++) {

				/*
				 * Rotation field is used add metadata to the mp4.
				 * this method is called in directly creating mp4 from coming encoded WebRTC H264 stream
				 */
				videoPkt.stream_index(0);
				videoPkt.pts(now + i* 100);
				videoPkt.dts(now + i * 100);

				encodedVideoFrame.rewind();

				if (i == 0) {
					videoPkt.flags(videoPkt.flags() | AV_PKT_FLAG_KEY);
				}
				videoPkt.data(new BytePointer(encodedVideoFrame));
				videoPkt.size(encodedVideoFrame.limit());
				videoPkt.position(0);
				videoPkt.duration(5);
				mp4Muxer.writePacket(videoPkt, new AVCodecContext());

				av_packet_unref(videoPkt);
			}

		} catch (IOException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		//write trailer
		mp4Muxer.writeTrailer();

	}

	@Test
	public void testMp4FinalName() {
		{
			//Scenario 1
			//1. The file does not exist on local disk -> stream1.mp4
			//2. The same file exists on storage -> stream1.mp4
			//3. The uploaded file should be to the storage should be -> stream1_1.mp4
			
			Vertx vertx = (Vertx) applicationContext.getBean(AntMediaApplicationAdapter.VERTX_BEAN_NAME);
			assertNotNull(vertx);
			String streamName = "stream_name_s" + (int) (Math.random() * 10000);
			getAppSettings().setMp4MuxingEnabled(true);
			getAppSettings().setUploadExtensionsToS3(7);
			getAppSettings().setS3RecordingEnabled(true);

			StorageClient client = Mockito.mock(StorageClient.class);
			doReturn(false).when(client).fileExist(Mockito.any());
			doReturn(true).when(client).fileExist("streams/" + streamName + ".mp4");

			if (appScope == null) {
				appScope = (WebScope) applicationContext.getBean("web.scope");
				logger.debug("Application / web scope: {}", appScope);
				assertTrue(appScope.getDepth() == 1);
			}
			
			//scenario 1
			//1. The file does not exist on local disk -> stream1.mp4
			//2. The same file exists on storage -> stream1.mp4
			//3. The uploaded file should be to the storage should be -> stream1_1.mp4
			{
				Mp4Muxer mp4Muxer = new Mp4Muxer(client, vertx, "streams");
				//init
				mp4Muxer.init(appScope, streamName, 0, null, 0);
				
				//initialize tmp file
				mp4Muxer.getOutputFormatContext();
	
				File finalFileName = mp4Muxer.getFinalFileName(true);
				assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+"_1.mp4"));
			}
			
			//Scenario 2
			//1. The file exists on local disk -> stream1.mp4
			//2. The file does not exist on storage -> stream1.mp4
			//3. The uploaded file should be  -> stream1_1.mp4
			{
				
				try {
					File file1 = new File("webapps/junit/streams/" + streamName + ".mp4");
					file1.createNewFile();
					
					doReturn(false).when(client).fileExist("streams/" + streamName + ".mp4");
			
				
					Mp4Muxer mp4Muxer = new Mp4Muxer(client, vertx, "streams");
					//init
					mp4Muxer.init(appScope, streamName, 0, null, 0);
					
					//initialize tmp file
					mp4Muxer.getOutputFormatContext();
					
					File finalFileName = mp4Muxer.getFinalFileName(true);
					assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+"_1.mp4"));
					
					finalFileName = mp4Muxer.getFinalFileName(false);
					assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+"_1.mp4"));
					
					file1.delete();
					
								
				} 
				catch (IOException e) 
				{
					e.printStackTrace();
					fail(e.getMessage());
				}
				
				
			}
			
			//Scenario 3
			//1. The file does not exists on local disk -> stream1.mp4
			//2. The file does exist on storage -> stream1.mp4, stream1_1.mp4, stream1_2.mp4
			//3. The uploaded file should be  -> stream1_3.mp4
			{
				doReturn(true).when(client).fileExist("streams/" + streamName + ".mp4");
				doReturn(true).when(client).fileExist("streams/" + streamName + "_1.mp4");
				doReturn(true).when(client).fileExist("streams/" + streamName + "_2.mp4");
				
				
				Mp4Muxer mp4Muxer = new Mp4Muxer(client, vertx, "streams");
				//init
				mp4Muxer.init(appScope, streamName, 0, null, 0);
				
				//initialize tmp file
				mp4Muxer.getOutputFormatContext();
				
				File finalFileName = mp4Muxer.getFinalFileName(true);
				assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+"_3.mp4"));
				
				finalFileName = mp4Muxer.getFinalFileName(false);
				assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+".mp4"));
			}
			
			//Scenario 4
			//1. The file does not exists on local disk -> stream1.webm
			//2. The file does exist on storage -> stream1.webm, stream1_1.webm, stream1_2.webm
			//3. The uploaded file should be  -> stream1_3.mp4
			{
				doReturn(true).when(client).fileExist("streams/" + streamName + ".webm");
				doReturn(true).when(client).fileExist("streams/" + streamName + "_1.webm");
				doReturn(true).when(client).fileExist("streams/" + streamName + "_2.webm");
				
				
				WebMMuxer webMMuxer = new WebMMuxer(client, vertx, "streams");
				//init
				webMMuxer.init(appScope, streamName, 0, null, 0);
				
				//initialize tmp file
				webMMuxer.getOutputFormatContext();
				
				File finalFileName = webMMuxer.getFinalFileName(true);
				assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+"_3.webm"));
				
				finalFileName = webMMuxer.getFinalFileName(false);
				assertTrue(finalFileName.getAbsolutePath().endsWith(streamName+".webm"));
			}
				
		}

	}


	@Test
	public void testHLSMuxingWithinChildScope() {

		int hlsTime = 2;
		int hlsListSize = 3;

		getAppSettings().setMp4MuxingEnabled(false);
		getAppSettings().setAddDateTimeToMp4FileName(false);
		getAppSettings().setHlsMuxingEnabled(true);
		getAppSettings().setDeleteHLSFilesOnEnded(true);
		getAppSettings().setHlsTime(String.valueOf(hlsTime));
		getAppSettings().setHlsListSize(String.valueOf(hlsListSize));


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}
		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		appScope.createChildScope("child");


		IScope childScope = appScope.getScope("child");

		childScope.createChildScope("child2");
		IScope childScope2 = childScope.getScope("child2");

		childScope2.createChildScope("child3");
		IScope childScope3 = childScope2.getScope("child3");

		File file = null;
		try {


			file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId("test_within_childscope");
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);


			boolean result = muxAdaptor.init(childScope3, "test_within_childscope", false);
			assert (result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);
			Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());

			muxAdaptor.stop(true);

			flvReader.close();

			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());
			List<Muxer> muxerList = muxAdaptor.getMuxerList();
			HLSMuxer hlsMuxer = null;
			for (Muxer muxer : muxerList) {
				if (muxer instanceof HLSMuxer) {
					hlsMuxer = (HLSMuxer) muxer;
					break;
				}
			}
			assertNotNull(hlsMuxer);
			File hlsFile = hlsMuxer.getFile();

			String hlsFilePath = hlsFile.getAbsolutePath();
			int lastIndex = hlsFilePath.lastIndexOf(".m3u8");

			String mp4Filename = hlsFilePath.substring(0, lastIndex) + ".mp4";

			//just check mp4 file is not created
			File mp4File = new File(mp4Filename);
			assertFalse(mp4File.exists());

			assertTrue(MuxingTest.testFile(hlsFile.getAbsolutePath()));

			File dir = new File(hlsFile.getAbsolutePath()).getParentFile();
			File[] files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts");
				}
			});

			System.out.println("ts file count:" + files.length);

			assertTrue(files.length > 0);
			assertTrue(files.length < (int) Integer.valueOf(hlsMuxer.getHlsListSize()) * (Integer.valueOf(hlsMuxer.getHlsTime()) + 1));

			//wait to let hls muxer delete ts and m3u8 file

			Awaitility.await().atMost(hlsListSize * hlsTime * 1000 + 3000, TimeUnit.MILLISECONDS).pollInterval(1, TimeUnit.SECONDS)
			.until(() -> {
				File[] filesTmp = dir.listFiles(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.endsWith(".ts") || name.endsWith(".m3u8");
					}
				});
				return 0 == filesTmp.length;
			});


			assertFalse(hlsFile.exists());

			files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts") || name.endsWith(".m3u8");
				}
			});

			assertEquals(0, files.length);

		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}


	}


	public void feedMuxAdaptor(FLVReader flvReader,List<MuxAdaptor> muxAdaptorList, StreamCodecInfo info)
	{
		boolean firstAudioPacketReceived = false;
		boolean firstVideoPacketReceived = false;
		while (flvReader.hasMoreTags())
		{
			ITag readTag = flvReader.readTag();
			StreamPacket streamPacket = new StreamPacket(readTag);
			if (!firstAudioPacketReceived && streamPacket.getDataType() == Constants.TYPE_AUDIO_DATA)
			{
				IAudioStreamCodec audioStreamCodec = AudioCodecFactory.getAudioCodec(streamPacket.getData().position(0));
				info.setAudioCodec(audioStreamCodec);
				audioStreamCodec.addData(streamPacket.getData().position(0));
				info.setHasAudio(true);
				firstAudioPacketReceived = true;
			}
			else if (!firstVideoPacketReceived && streamPacket.getDataType() == Constants.TYPE_VIDEO_DATA)
			{
				IVideoStreamCodec videoStreamCodec = VideoCodecFactory.getVideoCodec(streamPacket.getData().position(0));
				videoStreamCodec.addData(streamPacket.getData().position(0));
				info.setVideoCodec(videoStreamCodec);
				info.setHasVideo(true);
				firstVideoPacketReceived = true;

			}
			for (MuxAdaptor muxAdaptor : muxAdaptorList) {

				streamPacket = new StreamPacket(readTag);
				int bodySize = streamPacket.getData().position(0).limit();
				byte[] data = new byte[bodySize];
				streamPacket.getData().get(data);

				streamPacket.setData(IoBuffer.wrap(data));

				muxAdaptor.packetReceived(null, streamPacket);


			}
		}
	}
	

	@Test
	public void testHLSNaming() {
		HLSMuxer hlsMuxer = new HLSMuxer(vertx,Mockito.mock(StorageClient.class), "", 7, null, false);
		appScope = (WebScope) applicationContext.getBean("web.scope");
		hlsMuxer.init(appScope, "test", 0, "", 100);
		assertEquals("./webapps/junit/streams/test%09d.ts", hlsMuxer.getSegmentFilename());

		hlsMuxer = new HLSMuxer(vertx,Mockito.mock(StorageClient.class), "", 7, null, false);
		hlsMuxer.init(appScope, "test", 0, "", 0);
		assertEquals("./webapps/junit/streams/test%09d.ts", hlsMuxer.getSegmentFilename());

		hlsMuxer = new HLSMuxer(vertx,Mockito.mock(StorageClient.class), "", 7, null, false);
		hlsMuxer.init(appScope, "test", 300, "", 0);
		assertEquals("./webapps/junit/streams/test_300p%09d.ts", hlsMuxer.getSegmentFilename());
		

		hlsMuxer = new HLSMuxer(vertx,Mockito.mock(StorageClient.class), "", 7, null, false);
		hlsMuxer.init(appScope, "test", 300, "", 400000);
		assertEquals("./webapps/junit/streams/test_300p400kbps%09d.ts", hlsMuxer.getSegmentFilename());

	}

	public void testHLSMuxing(String name) {

		//av_log_set_level (40);
		int hlsListSize = 3;
		int hlsTime = 2;

		getAppSettings().setMp4MuxingEnabled(false);
		getAppSettings().setAddDateTimeToMp4FileName(false);
		getAppSettings().setHlsMuxingEnabled(true);
		getAppSettings().setDeleteHLSFilesOnEnded(true);
		getAppSettings().setHlsTime(String.valueOf(hlsTime));
		getAppSettings().setHlsListSize(String.valueOf(hlsListSize));
		getAppSettings().setUploadExtensionsToS3(2);


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		File file = null;
		try {

			file = new File("src/test/resources/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.info("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId(name);
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);
			boolean result = muxAdaptor.init(appScope, name, false);
			assert (result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			Awaitility.await().atMost(200, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());


			HLSMuxer hlsMuxer = null;
			{
				List<Muxer> muxerList = muxAdaptor.getMuxerList();

				for (Muxer muxer : muxerList) {
					if (muxer instanceof HLSMuxer) {
						hlsMuxer = (HLSMuxer) muxer;
						break;
					}
				}
				assertNotNull(hlsMuxer);
				//Call it separately for an unexpected case. It increases coverage and it checks not to crash
				hlsMuxer.prepareIO();
			}

			muxAdaptor.stop(true);

			flvReader.close();

			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());


			File hlsFile = hlsMuxer.getFile();

			String hlsFilePath = hlsFile.getAbsolutePath();
			int lastIndex = hlsFilePath.lastIndexOf(".m3u8");

			String mp4Filename = hlsFilePath.substring(0, lastIndex) + ".mp4";

			//just check mp4 file is not created
			File mp4File = new File(mp4Filename);
			assertFalse(mp4File.exists());

			assertTrue(MuxingTest.testFile(hlsFile.getAbsolutePath()));

			File dir = new File(hlsFile.getAbsolutePath()).getParentFile();
			File[] files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts");
				}
			});

			System.out.println("ts file count:" + files.length);

			assertTrue(files.length > 0);
			assertTrue(files.length < (int) Integer.valueOf(hlsMuxer.getHlsListSize()) * (Integer.valueOf(hlsMuxer.getHlsTime()) + 1));


			//wait to let hls muxer delete ts and m3u8 file

			Awaitility.await().atMost(hlsListSize * hlsTime * 1000 + 3000, TimeUnit.MILLISECONDS).pollInterval(1, TimeUnit.SECONDS)
			.until(() -> {
				File[] filesTmp = dir.listFiles(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.endsWith(".ts") || name.endsWith(".m3u8");
					}
				});
				return 0 == filesTmp.length;
			});


		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		getAppSettings().setDeleteHLSFilesOnEnded(false);

	}
	


	@Test
	public void testHLSMuxingWithSubtitle() {

		//av_log_set_level (40);
		int hlsListSize = 3;
		int hlsTime = 2;

		getAppSettings().setMp4MuxingEnabled(false);
		getAppSettings().setAddDateTimeToMp4FileName(false);
		getAppSettings().setHlsMuxingEnabled(true);
		getAppSettings().setDeleteHLSFilesOnEnded(true);
		getAppSettings().setHlsTime(String.valueOf(hlsTime));
		getAppSettings().setHlsListSize(String.valueOf(hlsListSize));


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}
		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		File file = null;
		try {

			file = new File("target/test-classes/test_video_360p_subtitle.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.info("f path: {}" , file.getAbsolutePath());
			assertTrue(file.exists());
			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId("hls_video_subtitle");
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);

			boolean result = muxAdaptor.init(appScope, "hls_video_subtitle", false);
			assert (result);

			muxAdaptor.start();

			feedMuxAdaptor(flvReader, Arrays.asList(muxAdaptor), info);

			Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> muxAdaptor.isRecording());

			muxAdaptor.stop(true);

			flvReader.close();

			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());

			// delete job in the list
			List<Muxer> muxerList = muxAdaptor.getMuxerList();
			HLSMuxer hlsMuxer = null;
			for (Muxer muxer : muxerList) {
				if (muxer instanceof HLSMuxer) {
					hlsMuxer = (HLSMuxer) muxer;
					break;
				}
			}
			assertNotNull(hlsMuxer);
			File hlsFile = hlsMuxer.getFile();

			String hlsFilePath = hlsFile.getAbsolutePath();
			int lastIndex = hlsFilePath.lastIndexOf(".m3u8");

			String mp4Filename = hlsFilePath.substring(0, lastIndex) + ".mp4";

			//just check mp4 file is not created
			File mp4File = new File(mp4Filename);
			assertFalse(mp4File.exists());

			assertTrue(MuxingTest.testFile(hlsFile.getAbsolutePath()));

			File dir = new File(hlsFile.getAbsolutePath()).getParentFile();
			File[] files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts");
				}
			});

			System.out.println("ts file count:" + files.length);

			assertTrue(files.length > 0);

			logger.info("hls list:{}", (int) Integer.valueOf(hlsMuxer.getHlsListSize()));

			logger.info("hls time:{}", (int) Integer.valueOf(hlsMuxer.getHlsTime()));

			assertTrue(files.length < (int) Integer.valueOf(hlsMuxer.getHlsListSize()) * (Integer.valueOf(hlsMuxer.getHlsTime()) + 1));

			//wait to let hls muxer delete ts and m3u8 file
			Awaitility.await().atMost(hlsListSize * hlsTime * 1000 + 3000, TimeUnit.MILLISECONDS).pollInterval(1, TimeUnit.SECONDS)
			.until(() -> {
				File[] filesTmp = dir.listFiles(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.endsWith(".ts") || name.endsWith(".m3u8");
					}
				});
				return 0 == filesTmp.length;
			});



			assertFalse(hlsFile.exists());

			files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts") || name.endsWith(".m3u8");
				}
			});

			assertEquals(0, files.length);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

	}

	public AppSettings getAppSettings() {
		if (appSettings == null) {
			appSettings = (AppSettings) applicationContext.getBean(AppSettings.BEAN_NAME);
		}
		return appSettings;
	}

	public DataStore getDataStore() {
		if (datastore == null) {
			datastore = ((DataStoreFactory) applicationContext.getBean(DataStoreFactory.BEAN_NAME)).getDataStore();
		}
		return datastore;
	}

	@Test
	public void testRecording() {
		testRecording("dasss", true);
	}

	public void testRecording(String name, boolean checkDuration) {
		logger.info("running testMp4Muxing");

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		vertx = (Vertx)appScope.getContext().getApplicationContext().getBean(IAntMediaStreamHandler.VERTX_BEAN_NAME);

		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);
		getAppSettings().setMp4MuxingEnabled(false);
		getAppSettings().setHlsMuxingEnabled(false);

		logger.info("HLS muxing enabled {}", appSettings.isHlsMuxingEnabled());

		//File file = new File(getResource("test.mp4").getFile());
		File file = null;

		try {

			file = new File("target/test-classes/test.flv");

			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());

			Broadcast broadcast = new Broadcast();
			try {
				broadcast.setStreamId(name);
			} catch (Exception e) {
				e.printStackTrace();
			}

			muxAdaptor.setBroadcast(broadcast);
			boolean result = muxAdaptor.init(appScope, name, false);

			assertTrue(result);


			muxAdaptor.start();
			logger.info("2");
			int packetNumber = 0;
			int lastTimeStamp = 0;
			int startOfRecordingTimeStamp = 0;
			boolean firstAudioPacketReceived = false;
			boolean firstVideoPacketReceived = false;
			ArrayList<Integer> timeStamps = new ArrayList<>();
			HLSMuxer hlsMuxer = null;
			while (flvReader.hasMoreTags()) {

				ITag readTag = flvReader.readTag();

				StreamPacket streamPacket = new StreamPacket(readTag);
				lastTimeStamp = streamPacket.getTimestamp();

				if(packetNumber == 0){
					logger.info("timeStamp 1 "+streamPacket.getTimestamp());
				}
				timeStamps.add(lastTimeStamp);
				if (!firstAudioPacketReceived && streamPacket.getDataType() == Constants.TYPE_AUDIO_DATA)
				{
					System.out.println("audio configuration received");
					IAudioStreamCodec audioStreamCodec = AudioCodecFactory.getAudioCodec(streamPacket.getData().position(0));
					info.setAudioCodec(audioStreamCodec);
					audioStreamCodec.addData(streamPacket.getData().position(0));
					info.setHasAudio(true);

					firstAudioPacketReceived = true;
				}
				else if (!firstVideoPacketReceived && streamPacket.getDataType() == Constants.TYPE_VIDEO_DATA)
				{
					System.out.println("video configuration received");
					IVideoStreamCodec videoStreamCodec = VideoCodecFactory.getVideoCodec(streamPacket.getData().position(0));
					videoStreamCodec.addData(streamPacket.getData().position(0));
					info.setVideoCodec(videoStreamCodec);
					IoBuffer decoderConfiguration = info.getVideoCodec().getDecoderConfiguration();
					logger.info("decoder configuration:" + decoderConfiguration);
					info.setHasVideo(true);

					firstVideoPacketReceived = true;

				}


				muxAdaptor.packetReceived(null, streamPacket);


				if (packetNumber == 40000) {
					logger.info("----input queue size: {}", muxAdaptor.getInputQueueSize());
					Awaitility.await().atMost(90, TimeUnit.SECONDS).until(() -> muxAdaptor.getInputQueueSize() == 0);
					logger.info("----input queue size: {}", muxAdaptor.getInputQueueSize());
					startOfRecordingTimeStamp = streamPacket.getTimestamp();
					assertTrue(muxAdaptor.startRecording(RecordType.MP4) != null);
					hlsMuxer = new HLSMuxer(vertx, null, null, 0, null, false);
					
					assertTrue(muxAdaptor.addMuxer(hlsMuxer));
					assertFalse(muxAdaptor.addMuxer(hlsMuxer));
					
				}
				packetNumber++;

				if (packetNumber % 3000 == 0) {
					logger.info("packetNumber " + packetNumber);
				}
			}

			Awaitility.await().atMost(90, TimeUnit.SECONDS).until(muxAdaptor::isRecording);

			assertTrue(muxAdaptor.isRecording());
			final String finalFilePath = muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath();

			int inputQueueSize = muxAdaptor.getInputQueueSize();
			logger.info("----input queue size before stop recording: {}", inputQueueSize);
			Awaitility.await().atMost(90, TimeUnit.SECONDS).until(() -> muxAdaptor.getInputQueueSize() == 0);

			inputQueueSize = muxAdaptor.getInputQueueSize();
			int estimatedLastTimeStamp = lastTimeStamp;
			if (inputQueueSize > 0) {
				estimatedLastTimeStamp = timeStamps.get((timeStamps.size() - inputQueueSize));
			}
			assertTrue(muxAdaptor.stopRecording(RecordType.MP4) != null);
			
			assertTrue(muxAdaptor.removeMuxer(hlsMuxer));
			assertFalse(muxAdaptor.removeMuxer(hlsMuxer));

			muxAdaptor.stop(true);

			flvReader.close();

			Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> !muxAdaptor.isRecording());

			assertFalse(muxAdaptor.isRecording());

			assertTrue(MuxingTest.testFile(finalFilePath, estimatedLastTimeStamp-startOfRecordingTimeStamp));
			
			assertTrue(MuxingTest.testFile(hlsMuxer.getFile().getAbsolutePath()));
			

		} catch (Exception e) {
			e.printStackTrace();
			fail("exception:" + e);
		}
		logger.info("leaving testRecording");
	}



	@Test
	public void testRemux() {
		String input = "target/test-classes/sample_MP4_480.mp4";
		String rotated = "rotated.mp4";

		Mp4Muxer.remux(input, rotated, 90);
		AVFormatContext inputFormatContext = avformat.avformat_alloc_context();

		int ret;
		if (inputFormatContext == null) {
			System.out.println("cannot allocate input context");
		}

		if ((ret = avformat_open_input(inputFormatContext, rotated, null, (AVDictionary) null)) < 0) {
			System.out.println("cannot open input context: " + rotated);
		}

		ret = avformat_find_stream_info(inputFormatContext, (AVDictionary) null);
		if (ret < 0) {
			System.out.println("Could not find stream information\n");
		}

		int streamCount = inputFormatContext.nb_streams();

		for (int i = 0; i < streamCount; i++) {
			AVCodecParameters codecpar = inputFormatContext.streams(i).codecpar();
			if (codecpar.codec_type() == AVMEDIA_TYPE_VIDEO) {
				AVStream videoStream = inputFormatContext.streams(i);
				
				SizeTPointer size = new SizeTPointer(1);
				BytePointer displayMatrixBytePointer = av_stream_get_side_data(videoStream, avcodec.AV_PKT_DATA_DISPLAYMATRIX, size);
				//it should be 36 because it's a 3x3 integer(size=4). 
				assertEquals(36, size.get());
				
				IntPointer displayPointerIntPointer = new IntPointer(displayMatrixBytePointer);
				//it gets counter clockwise
				int rotation = (int) -(avutil.av_display_rotation_get(displayPointerIntPointer));

				assertEquals(90, rotation);
			}
		}

		avformat_close_input(inputFormatContext);

		new File(rotated).delete();

	}

	@Test
	public void testMp4MuxingWithSameNameWhileRecording() {

		/*
		 * In this test we create such a case with spy Files
		 * In record directory
		 * test.mp4						existing
		 * test_1.mp4 					non-existing
		 * test_2.mp4 					non-existing
		 * test.mp4.tmp_extension		non-existing
		 * test_1.mp4.tmp_extension		existing
		 * test_2.mp4.tmp_extension		non-existing
		 *
		 * So we check new record file must be temp_2.mp4
		 */

		String streamId = "test";
		Muxer mp4Muxer = spy(new Mp4Muxer(null, null, "streams"));
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.info("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		File parent = mock(File.class);
		when(parent.exists()).thenReturn(true);

		File existingFile = spy(new File(streamId+".mp4"));
		doReturn(true).when(existingFile).exists();
		doReturn(parent).when(existingFile).getParentFile();

		File nonExistingFile_1 = spy(new File(streamId+"_1.mp4"));
		doReturn(false).when(nonExistingFile_1).exists();

		File nonExistingFile_2 = spy(new File(streamId+"_2.mp4"));
		doReturn(false).when(nonExistingFile_2).exists();


		File nonExistingTempFile = spy(new File(streamId+".mp4"+Muxer.TEMP_EXTENSION));
		doReturn(true).when(nonExistingTempFile).exists();

		File existingTempFile_1 = spy(new File(streamId+"_1.mp4"+Muxer.TEMP_EXTENSION));
		doReturn(true).when(existingTempFile_1).exists();

		File nonExistingTempFile_2 = spy(new File(streamId+"_2.mp4"+Muxer.TEMP_EXTENSION));
		doReturn(false).when(nonExistingTempFile_2).exists();

		doReturn(existingFile).when(mp4Muxer).getResourceFile(any(), eq(streamId), eq(".mp4"), eq(null));
		doReturn(nonExistingFile_1).when(mp4Muxer).getResourceFile(any(), eq(streamId+"_1"), eq(".mp4"), eq(null));
		doReturn(nonExistingFile_2).when(mp4Muxer).getResourceFile(any(), eq(streamId+"_2"), eq(".mp4"), eq(null));

		doReturn(nonExistingTempFile).when(mp4Muxer).getResourceFile(any(), eq(streamId), eq(".mp4"+Muxer.TEMP_EXTENSION), eq(null));
		doReturn(existingTempFile_1).when(mp4Muxer).getResourceFile(any(), eq(streamId+"_1"), eq(".mp4"+Muxer.TEMP_EXTENSION), eq(null));
		doReturn(nonExistingTempFile_2).when(mp4Muxer).getResourceFile(any(), eq(streamId+"_2"), eq(".mp4"+Muxer.TEMP_EXTENSION), eq(null));

		mp4Muxer.init(appScope, streamId, 0, false, null, 0);

		assertEquals(nonExistingFile_2, mp4Muxer.getFile());

	}
	
	@Test
	public void testGetExtendedName(){
		Muxer mp4Muxer = spy(new Mp4Muxer(null, null, "streams"));
		assertEquals( "test_400p",mp4Muxer.getExtendedName("test", 400, 1000000 ,""));
		assertEquals( "test_400p1000kbps",mp4Muxer.getExtendedName("test", 400, 1000000 ,"%r%b"));
		assertEquals( "test_1000kbps",mp4Muxer.getExtendedName("test", 400, 1000000 ,"%b"));
		assertEquals( "test_400p",mp4Muxer.getExtendedName("test", 400, 1000000 ,"%r"));
		assertEquals( "test",mp4Muxer.getExtendedName("test", 0, 1000000 ,"%r"));
		assertEquals( "test_1000kbps",mp4Muxer.getExtendedName("test", 0, 1000000 ,"%b"));
		assertEquals( "test_1000kbps",mp4Muxer.getExtendedName("test", 0, 1000000 ,"%r%b"));
		assertEquals( "test",mp4Muxer.getExtendedName("test", 400, 10,"%b"));
		assertEquals( "test_400p",mp4Muxer.getExtendedName("test", 400, 10,"%r"));
		assertEquals( "test_400p",mp4Muxer.getExtendedName("test", 400, 10,"%r%b"));
		assertEquals( "test_1000kbps400p",mp4Muxer.getExtendedName("test", 400, 1000000,"%b%r"));
		assertEquals( "test_1000kbps",mp4Muxer.getExtendedName("test", 0, 1000000,"%b%r"));
		assertEquals( "test_400p",mp4Muxer.getExtendedName("test", 400, 0,"%b%r"));

	}

	@Test
	public void testMp4MuxingWhileTempFileExist() {

		/*
		 * In this test we create such a case with spy Files
		 * In record directory
		 * test.mp4						non-existing
		 * test_1.mp4 					non-existing
		 * test.mp4.tmp_extension		existing
		 * test_1.mp4.tmp_extension		non-existing
		 *
		 * So we check new record file must be temp_1.mp4
		 */

		String streamId = "test";
		Muxer mp4Muxer = spy(new Mp4Muxer(null, null, "streams"));
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.info("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}
		
		File parent = mock(File.class);
		when(parent.exists()).thenReturn(true);

		File nonExistingFile = spy(new File(streamId+".mp4"));
		doReturn(false).when(nonExistingFile).exists();
		doReturn(parent).when(nonExistingFile).getParentFile();

		File nonExistingFile_1 = spy(new File(streamId+"_1.mp4"));
		doReturn(false).when(nonExistingFile_1).exists();

		File existingTempFile = spy(new File(streamId+".mp4"+Muxer.TEMP_EXTENSION));
		doReturn(true).when(existingTempFile).exists();

		File nonExistingTempFile_1 = spy(new File(streamId+"_1.mp4"+Muxer.TEMP_EXTENSION));
		doReturn(false).when(nonExistingTempFile_1).exists();

		doReturn(nonExistingFile).when(mp4Muxer).getResourceFile(any(), eq(streamId), eq(".mp4"), eq(null));
		doReturn(nonExistingFile_1).when(mp4Muxer).getResourceFile(any(), eq(streamId+"_1"), eq(".mp4"), eq(null));

		doReturn(existingTempFile).when(mp4Muxer).getResourceFile(any(), eq(streamId), eq(".mp4"+Muxer.TEMP_EXTENSION), eq(null));
		doReturn(nonExistingTempFile_1).when(mp4Muxer).getResourceFile(any(), eq(streamId+"_1"), eq(".mp4"+Muxer.TEMP_EXTENSION), eq(null));

		mp4Muxer.init(appScope, streamId, 0, false, null, 0);

		assertEquals(nonExistingFile_1, mp4Muxer.getFile());

	}

	@Test
	public void testAnalyzeTime() {
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.info("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		getAppSettings().setDeleteHLSFilesOnEnded(false);

		ClientBroadcastStream clientBroadcastStream = Mockito.spy(new ClientBroadcastStream());
		Mockito.doReturn(Mockito.mock(IStreamCapableConnection.class)).when(clientBroadcastStream).getConnection();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream.setCodecInfo(info);

		assertFalse(clientBroadcastStream.getCodecInfo().hasVideo());
		assertFalse(clientBroadcastStream.getCodecInfo().hasAudio());

		getAppSettings().setMaxAnalyzeDurationMS(3000);
		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope));
		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId("name");
		} catch (Exception e) {
			e.printStackTrace();
		}

		muxAdaptor.setBroadcast(broadcast);
		muxAdaptor.init(appScope, "name", false);

		clientBroadcastStream.setMuxAdaptor(new WeakReference<MuxAdaptor>(muxAdaptor));

		assertFalse(muxAdaptor.isRecording());

		muxAdaptor.start();		

		Awaitility.await().atLeast(getAppSettings().getMaxAnalyzeDurationMS()*2, TimeUnit.MILLISECONDS)
		.atMost(getAppSettings().getMaxAnalyzeDurationMS()*2+1000, TimeUnit.MILLISECONDS)
		.until(() -> {
			return muxAdaptor.isStopRequestExist();
		});

		Mockito.verify(muxAdaptor, Mockito.timeout(500)).closeRtmpConnection();

		//it should be false because there is no video and audio in the stream.
		assertFalse(muxAdaptor.isRecording());

	}

	@Test
	public void testMuxAdaptorPacketListener() {
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.info("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		String streamId = "stream"+RandomUtils.nextInt(1, 1000);
		Broadcast broadcast = new Broadcast();
		try {
			broadcast.setStreamId(streamId);
		} catch (Exception e) {
			e.printStackTrace();
		}

		MuxAdaptor muxAdaptor = Mockito.spy(MuxAdaptor.initializeMuxAdaptor(null, false, appScope));
		muxAdaptor.setBroadcast(broadcast);
		muxAdaptor.init(appScope, streamId, false);
		doNothing().when(muxAdaptor).updateQualityParameters(Mockito.anyLong(), any());


		IPacketListener listener = mock(IPacketListener.class);
		muxAdaptor.addPacketListener(listener);

		verify(listener, Mockito.times(1)).setVideoStreamInfo(eq(muxAdaptor.getStreamId()), any());
		verify(listener, Mockito.times(1)).setAudioStreamInfo(eq(muxAdaptor.getStreamId()), any());

		AVStream stream = mock(AVStream.class);
		AVCodecParameters codecParameters = mock(AVCodecParameters.class);
		when(stream.codecpar()).thenReturn(codecParameters);
		when(codecParameters.codec_type()).thenReturn(AVMEDIA_TYPE_VIDEO);

		AVPacket pkt = mock(AVPacket.class);
		when(pkt.flags()).thenReturn(AV_PKT_FLAG_KEY);

		muxAdaptor.writePacket(stream, pkt);
		verify(listener, Mockito.times(1)).onVideoPacket(streamId, pkt);

		when(codecParameters.codec_type()).thenReturn(AVMEDIA_TYPE_AUDIO);
		muxAdaptor.writePacket(stream, pkt);
		verify(listener, Mockito.times(1)).onAudioPacket(streamId, pkt);

		muxAdaptor.removePacketListener(listener);

	}

	@Test
	public void testPacketFeeder() {
		String streamId = "stream"+RandomUtils.nextInt(1, 1000);
		PacketFeeder packetFeeder = new PacketFeeder(streamId);
		IPacketListener listener = mock(IPacketListener.class);
		packetFeeder.addListener(listener);

		ByteBuffer encodedVideoFrame = ByteBuffer.allocate(100); 
		packetFeeder.writeVideoBuffer(encodedVideoFrame, 50, 0, 0, false, 0, 50);
		verify(listener, Mockito.times(1)).onVideoPacket(eq(streamId), any());

		ByteBuffer audioFrame = ByteBuffer.allocate(100); 
		packetFeeder.writeAudioBuffer(audioFrame, 1, 50);
		verify(listener, Mockito.times(1)).onAudioPacket(eq(streamId), any());


	}
	
	@Test
	public void testStreamParametersInfo() {
		StreamParametersInfo spi = new StreamParametersInfo();
		AVCodecParameters codecParameters = mock(AVCodecParameters.class);
		AVRational timebase = mock(AVRational.class);
		boolean enable = RandomUtils.nextBoolean();
		boolean hostedInOtherNode = RandomUtils.nextBoolean();
		
		spi.setCodecParameters(codecParameters);
		spi.setTimeBase(timebase);
		spi.setEnabled(enable);
		spi.setHostedInOtherNode(hostedInOtherNode);
		
		assertEquals(codecParameters, spi.getCodecParameters());
		assertEquals(timebase, spi.getTimeBase());
		assertEquals(enable, spi.isEnabled());
		assertEquals(hostedInOtherNode, spi.isHostedInOtherNode());


	}

	@Test
	public void testRegisterRTMPStreamToMainTrack() {
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		ClientBroadcastStream clientBroadcastStream1 = new ClientBroadcastStream();
		StreamCodecInfo info = new StreamCodecInfo();
		clientBroadcastStream1.setCodecInfo(info);
		Map<String, String> params1 = new HashMap<String, String>();
		String mainTrackId = "mainTrack"+RandomUtils.nextInt(0, 10000);
		params1.put("mainTrack", mainTrackId);
		clientBroadcastStream1.setParameters(params1);
		MuxAdaptor muxAdaptor1 = spy(MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream1, false, appScope));
		
		String sub1 = "subtrack1"+RandomUtils.nextInt(0, 10000);;
		muxAdaptor1.setStreamId(sub1);
		DataStore ds1 = spy(new InMemoryDataStore("testdb"));
		doReturn(ds1).when(muxAdaptor1).getDataStore();
		doReturn(new Broadcast()).when(muxAdaptor1).getBroadcast();
		muxAdaptor1.registerToMainTrackIfExists();
		verify(ds1, times(1)).updateBroadcastFields(anyString(), any());

		ArgumentCaptor<Broadcast> argument = ArgumentCaptor.forClass(Broadcast.class);
		verify(ds1, times(1)).save(argument.capture());
		assertEquals(mainTrackId, argument.getValue().getStreamId());
		assertTrue(argument.getValue().getSubTrackStreamIds().contains(sub1));

		String sub2 = "subtrack2"+RandomUtils.nextInt(0, 10000);;
		ClientBroadcastStream clientBroadcastStream2 = new ClientBroadcastStream();
		clientBroadcastStream2.setCodecInfo(info);
		clientBroadcastStream2.setParameters(params1);
		MuxAdaptor muxAdaptor2 = spy(MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream2, false, appScope));
		muxAdaptor2.setStreamId(sub2);
		doReturn(new Broadcast()).when(muxAdaptor2).getBroadcast();
		doReturn(ds1).when(muxAdaptor2).getDataStore();
		muxAdaptor2.registerToMainTrackIfExists();
		
		ArgumentCaptor<Broadcast> argument2 = ArgumentCaptor.forClass(Broadcast.class);
		verify(ds1, times(1)).save(argument2.capture());
		assertEquals(mainTrackId, argument2.getValue().getStreamId());
		assertTrue(argument2.getValue().getSubTrackStreamIds().contains(sub1));
		
		Broadcast mainBroadcast = ds1.get(mainTrackId);
		assertEquals(2, mainBroadcast.getSubTrackStreamIds().size());
		
		ClientBroadcastStream clientBroadcastStream3 = new ClientBroadcastStream();
		clientBroadcastStream3.setCodecInfo(info);
		MuxAdaptor muxAdaptor3 = spy(MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream3, false, appScope));
		muxAdaptor3.setStreamId("stream3");
		DataStore ds2 = mock(DataStore.class);
		doReturn(ds2).when(muxAdaptor3).getDataStore();
		muxAdaptor3.registerToMainTrackIfExists();
		verify(ds2, never()).updateBroadcastFields(anyString(), any());
		
	}
	
	@Test
	public void testOrderAudioPacket() {
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}
		ClientBroadcastStream clientBroadcastStream = new ClientBroadcastStream();
		MuxAdaptor muxAdaptor = MuxAdaptor.initializeMuxAdaptor(clientBroadcastStream, false, appScope);

		muxAdaptor.getBufferQueue().add(createPacket(Constants.TYPE_AUDIO_DATA, 10));
		muxAdaptor.getBufferQueue().add(createPacket(Constants.TYPE_VIDEO_DATA, 15));
		muxAdaptor.getBufferQueue().add(createPacket(Constants.TYPE_AUDIO_DATA, 20));
		muxAdaptor.getBufferQueue().add(createPacket(Constants.TYPE_VIDEO_DATA, 25));
		muxAdaptor.getBufferQueue().add(createPacket(Constants.TYPE_AUDIO_DATA, 14));
		muxAdaptor.getBufferQueue().add(createPacket(Constants.TYPE_AUDIO_DATA, 30));
		
		IStreamPacket p = muxAdaptor.peekTheNextPacketFromBuffer();
		assertTrue(p.getDataType() == Constants.TYPE_AUDIO_DATA && p.getTimestamp() == 10);
		muxAdaptor.getBufferQueue().remove(p);
		p = muxAdaptor.peekTheNextPacketFromBuffer();
		assertTrue(p.getDataType() == Constants.TYPE_VIDEO_DATA && p.getTimestamp() == 15);
		muxAdaptor.getBufferQueue().remove(p);
		p = muxAdaptor.peekTheNextPacketFromBuffer();
		assertTrue(p.getDataType() == Constants.TYPE_AUDIO_DATA && p.getTimestamp() == 14);
		muxAdaptor.getBufferQueue().remove(p);
		p = muxAdaptor.peekTheNextPacketFromBuffer();
		assertTrue(p.getDataType() == Constants.TYPE_AUDIO_DATA && p.getTimestamp() == 20);
		muxAdaptor.getBufferQueue().remove(p);
		p = muxAdaptor.peekTheNextPacketFromBuffer();
		assertTrue(p.getDataType() == Constants.TYPE_VIDEO_DATA && p.getTimestamp() == 25);
		muxAdaptor.getBufferQueue().remove(p);
		p = muxAdaptor.peekTheNextPacketFromBuffer();
		assertTrue(p.getDataType() == Constants.TYPE_AUDIO_DATA && p.getTimestamp() == 30);
	}

	private IStreamPacket createPacket(byte type, int timeStamp) {
		CachedEvent ce = new CachedEvent();
		ce.setDataType(type);
		ce.setTimestamp(timeStamp);
		IoBuffer data = IoBuffer.allocate(1);
		ce.setData(data );
		return ce;
	}
}
