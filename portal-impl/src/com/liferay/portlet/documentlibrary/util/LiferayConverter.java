/**
 * Copyright (c) 2000-2011 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portlet.documentlibrary.util;

import com.liferay.portal.kernel.image.ImageProcessorUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;

import com.xuggle.xuggler.Global;
import com.xuggle.xuggler.IAudioResampler;
import com.xuggle.xuggler.IAudioSamples.Format;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IRational;
import com.xuggle.xuggler.IStream;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IVideoPicture;
import com.xuggle.xuggler.IVideoResampler;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.IConverter;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;

import java.io.File;
import java.io.FileOutputStream;

import javax.imageio.ImageIO;

/**
 * @author Juan González
 * @author Sergio González
 * @author Brian Wing Shun Chan
 */
public abstract class LiferayConverter {

	protected void cleanUp(
		IStreamCoder[] inputIStreamCoders, IStreamCoder[] outputIStreamCoders) {

		for (int i = 0; i < inputIStreamCoders.length; i++) {
			IStreamCoder inputIStreamCoder = inputIStreamCoders[i];

			if (inputIStreamCoder != null) {
				inputIStreamCoder.close();
			}

			IStreamCoder outputIStreamCoder = outputIStreamCoders[i];

			if (outputIStreamCoder != null) {
				outputIStreamCoder.close();
			}
		}
	}

	protected int countNonKeyAfterKey(
		IPacket inputIPacket, Boolean keyPacketFound, int nonKeyAfterKeyCount) {

		if (inputIPacket.isKey()) {
			nonKeyAfterKeyCount = 0;
		}
		else if (keyPacketFound) {
			nonKeyAfterKeyCount++;
		}

		return nonKeyAfterKeyCount;
	}

	protected IAudioResampler createIAudioResampler(
			IStreamCoder inputIStreamCoder, IStreamCoder outputIStreamCoder)
		throws Exception {

		IAudioResampler iAudioResampler = null;

		Format inputSampleFormat = inputIStreamCoder.getSampleFormat();
		Format outputSampleFormat = outputIStreamCoder.getSampleFormat();

		if ((inputIStreamCoder.getChannels() ==
				outputIStreamCoder.getChannels()) &&
			(inputIStreamCoder.getSampleRate() ==
				outputIStreamCoder.getSampleRate()) &&
			inputSampleFormat.equals(outputSampleFormat)) {

			return iAudioResampler;
		}

		iAudioResampler = IAudioResampler.make(
			outputIStreamCoder.getChannels(), inputIStreamCoder.getChannels(),
			outputIStreamCoder.getSampleRate(),
			inputIStreamCoder.getSampleRate(),
			outputIStreamCoder.getSampleFormat(),
			inputIStreamCoder.getSampleFormat());

		if (iAudioResampler == null) {
			throw new RuntimeException("Audio resampling is not supported");
		}

		return iAudioResampler;
	}

	protected IVideoResampler createIVideoResampler(
			IStreamCoder inputIStreamCoder, IStreamCoder outputIStreamCoder,
			int height, int width)
		throws Exception {

		IVideoResampler iVideoResampler = null;

		IPixelFormat.Type inputIPixelFormatType =
			inputIStreamCoder.getPixelType();
		IPixelFormat.Type outputIPixelFormatType =
			outputIStreamCoder.getPixelType();

		if ((height == inputIStreamCoder.getHeight()) &&
			(width == inputIStreamCoder.getWidth()) &&
			inputIPixelFormatType.equals(outputIPixelFormatType)) {

			return iVideoResampler;
		}

		iVideoResampler = IVideoResampler.make(
			width, height, outputIStreamCoder.getPixelType(),
			inputIStreamCoder.getWidth(), inputIStreamCoder.getHeight(),
			inputIStreamCoder.getPixelType());

		if (iVideoResampler == null) {
			throw new RuntimeException("Video resampling is not supported");
		}

		return iVideoResampler;
	}

	protected void decodeAudio(
			IAudioResampler iAudioResampler, IAudioSamples inputIAudioSample,
			IAudioSamples resampledIAudioSample, IPacket inputIPacket,
			IPacket outputIPacket, IStreamCoder inputIStreamCoder,
			IStreamCoder outputIStreamCoder, IContainer outputIContainer,
			int currentPacketSize, int previousPacketSize, int streamIndex,
			long timeStampOffset)
		throws Exception {

		int offset = 0;

		while (offset < inputIPacket.getSize()) {
			boolean stopDecoding = false;

			int value = inputIStreamCoder.decodeAudio(
				inputIAudioSample, inputIPacket, offset);

			if (value <= 0) {
				if ((previousPacketSize == currentPacketSize) &&
					(previousPacketSize != -1)) {

					throw new RuntimeException(
						"Unable to decode audio stream " + streamIndex);
				}
				else {
					stopDecoding = true;
				}
			}

			updateAudioTimeStamp(inputIAudioSample, timeStampOffset);

			offset += value;

			IAudioSamples outputIAudioSample = resampleAudio(
				iAudioResampler, inputIAudioSample, resampledIAudioSample);

			encodeAudio(
				outputIStreamCoder, outputIPacket, outputIAudioSample,
				outputIContainer);

			if (stopDecoding) {
				if (_log.isDebugEnabled()) {
					_log.debug("Stop decoding audio stream " + streamIndex);
				}

				break;
			}
		}
	}

	protected int decodeVideo(
			IVideoResampler iVideoResampler, IVideoPicture inputIVideoPicture,
			IVideoPicture resampledIVideoPicture, IPacket inputIPacket,
			IPacket outputIPacket, IStreamCoder inputIStreamCoder,
			IStreamCoder outputIStreamCoder, IContainer outputIContainer,
			File thumbnailFile, String thumbnailExtension, int thumbnailHeight,
			int thumbnailWidth, long timeStampOffset)
		throws Exception {

		int value = inputIStreamCoder.decodeVideo(
			inputIVideoPicture, inputIPacket, 0);

		if (value <= 0) {
			return value;
		}

		updateVideoTimeStamp(inputIVideoPicture, timeStampOffset);

		if (!inputIVideoPicture.isComplete()) {
			return 1;
		}

		if (thumbnailFile != null) {
			BufferedImage bufferedImage = null;

			if (_converterFactoryType == null) {
				_converterFactoryType =
					ConverterFactory.findRegisteredConverter(
						ConverterFactory.XUGGLER_BGR_24);
			}

			if (_converterFactoryType == null) {
				throw new UnsupportedOperationException(
					"No converter found for " +
						ConverterFactory.XUGGLER_BGR_24);
			}

			if (_videoIConverter == null) {
				_videoIConverter = ConverterFactory.createConverter(
					_converterFactoryType.getDescriptor(),
					inputIVideoPicture);
			}

			bufferedImage = _videoIConverter.toImage(inputIVideoPicture);

			thumbnailFile.createNewFile();

			RenderedImage renderedImage = ImageProcessorUtil.scale(
				bufferedImage, thumbnailHeight, thumbnailWidth);

			ImageIO.write(
				renderedImage, thumbnailExtension,
				new FileOutputStream(thumbnailFile));

			return DECODE_VIDEO_THUMBNAIL;
		}

		if ((outputIStreamCoder != null) && (outputIContainer != null)) {
			IVideoPicture outputIVideoPicture = null;

			outputIVideoPicture = resampleVideo(
				iVideoResampler, inputIVideoPicture, resampledIVideoPicture);

			outputIVideoPicture.setQuality(0);

			encodeVideo(
				outputIStreamCoder, outputIVideoPicture, outputIPacket,
				outputIContainer);
		}

		return 1;
	}

	protected void encodeAudio(
			IStreamCoder outputIStreamCoder, IPacket outputIPacket,
			IAudioSamples outputIAudioSample, IContainer outputIContainer)
		throws Exception {

		int consumedSamplesCount = 0;

		while (consumedSamplesCount < outputIAudioSample.getNumSamples()) {
			int value = outputIStreamCoder.encodeAudio(
				outputIPacket, outputIAudioSample, consumedSamplesCount);

			if (value <= 0) {
				throw new RuntimeException("Unable to encode audio");
			}

			consumedSamplesCount += value;

			if (outputIPacket.isComplete()) {
				value = outputIContainer.writePacket(outputIPacket, true);

				if (value < 0) {
					throw new RuntimeException("Unable to write audio packet");
				}
			}
		}
	}

	protected void encodeVideo(
			IStreamCoder outputIStreamCoder, IVideoPicture outputIVideoPicture,
			IPacket outputIPacket, IContainer outputIContainer)
		throws Exception {

		if (outputIStreamCoder.encodeVideo(
				outputIPacket, outputIVideoPicture, 0) < 0) {

			throw new RuntimeException("Unable to encode video");
		}

		if (outputIPacket.isComplete()) {
			if (outputIContainer.writePacket(outputIPacket, true) < 0) {
				throw new RuntimeException("Unable to write video packet");
			}
		}
	}

	protected void flush(
		IStreamCoder[] outputIStreamCoders, IContainer outputIContainer) {

		for (IStreamCoder outputIStreamCoder : outputIStreamCoders) {
			if (outputIStreamCoder == null) {
				continue;
			}

			IPacket iPacket = IPacket.make();

			if (outputIStreamCoder.getCodecType() ==
					ICodec.Type.CODEC_TYPE_AUDIO) {

				outputIStreamCoder.encodeAudio(iPacket, null, 0);
			}
			else {
				outputIStreamCoder.encodeVideo(iPacket, null, 0);
			}

			if (iPacket.isComplete()) {
				outputIContainer.writePacket(iPacket, true);
			}
		}
	}

	protected int getAudioEncodingChannels(
		IContainer outputIContainer, int channels) {

		if ((channels == 0) || (channels > 2)) {
			channels = 2;
		}

		return channels;
	}

	protected ICodec getAudioEncodingICodec(IContainer outputIContainer) {
		return null;
	}

	protected abstract IContainer getInputIContainer();

	protected long getSeekTimeStamp(int percentage) throws Exception {
		IContainer inputIContainer = getInputIContainer();

		long seekTimeStamp = -1;

		long videoSeconds = inputIContainer.getDuration() / 1000000L;

		long seekSeconds = ((videoSeconds * percentage) / 100L);

		for (int i = 0; i < inputIContainer.getNumStreams(); i++) {
			IStream inputIStream = inputIContainer.getStream(i);

			IStreamCoder inputIStreamCoder = inputIStream.getStreamCoder();

			if (inputIStreamCoder.getCodecType() !=
					ICodec.Type.CODEC_TYPE_VIDEO) {

				continue;
			}

			IRational iRational = inputIStream.getTimeBase();

			long timeStampOffset =
				iRational.getDenominator() / iRational.getNumerator() *
					seekSeconds;

			seekTimeStamp = inputIContainer.getStartTime() + timeStampOffset;

			break;
		}

		return seekTimeStamp;
	}

	protected long getStreamTimeStampOffset(IStream iStream) {
		long timeStampOffset = 0;

		if ((iStream.getStartTime() != Global.NO_PTS) &&
			(iStream.getStartTime() > 0) && (iStream.getTimeBase() != null)) {

			IRational iRational = IRational.make(
				1, (int)Global.DEFAULT_PTS_PER_SECOND);

			timeStampOffset = iRational.rescale(
				iStream.getStartTime(), iStream.getTimeBase());
		}

		return timeStampOffset;
	}

	protected boolean isKeyPacketFound(
		IPacket inputIPacket, boolean keyPacketFound) {

		if (inputIPacket.isKey() && !keyPacketFound) {
			return true;
		}

		return keyPacketFound;
	}

	protected boolean isStartDecoding(
		IPacket inputIPacket, IStreamCoder inputIStreamCoder,
		boolean keyPacketFound, int nonKeyAfterKeyCount,
		boolean onlyDecodeKeyPackets) {

		if (onlyDecodeKeyPackets && !inputIPacket.isKey()) {
			return false;
		}

		ICodec.ID iCodecID = inputIStreamCoder.getCodecID();

		if (iCodecID.equals(ICodec.ID.CODEC_ID_MJPEG)) {
			return true;
		}
		else if (iCodecID.equals(ICodec.ID.CODEC_ID_MPEG2VIDEO)) {
			if (nonKeyAfterKeyCount != 1) {
				return true;
			}

			return false;
		}

		return keyPacketFound;
	}

	protected void openContainer(
			IContainer iContainer, String url, boolean writeContainer)
		throws Exception {

		int value = 0;

		if (writeContainer) {
			value = iContainer.open(url, IContainer.Type.WRITE, null);
		}
		else {
			value = iContainer.open(url, IContainer.Type.READ, null);
		}

		if (value < 0) {
			if (writeContainer) {
				throw new RuntimeException("Unable to open output URL");
			}
			else {
				throw new RuntimeException("Unable to open input URL");
			}
		}
	}

	protected void openStreamCoder(IStreamCoder iStreamCoder)
		throws Exception {

		if ((iStreamCoder != null) && (iStreamCoder.open() < 0)) {
			throw new RuntimeException("Unable to open coder");
		}
	}

	protected void prepareAudio(
			IAudioResampler[] iAudioResamplers,
			IAudioSamples[] inputIAudioSamples,
			IAudioSamples[] outputIAudioSamples, IStreamCoder inputIStreamCoder,
			IStreamCoder[] outputIStreamCoders, IContainer outputIContainer,
			IStream[] outputIStreams, ICodec.Type inputICodecType,
			String outputURL, int index)
		throws Exception {

		IStream outputIStream = outputIContainer.addNewStream(index);

		outputIStreams[index] = outputIStream;

		IStreamCoder outputIStreamCoder = outputIStream.getStreamCoder();

		outputIStreamCoders[index] = outputIStreamCoder;

		int bitRate = inputIStreamCoder.getBitRate();

		if (bitRate == 0) {
			bitRate = AUDIO_BIT_RATE_DEFAULT;
		}

		outputIStreamCoder.setBitRate(bitRate);

		int channels = getAudioEncodingChannels(
			outputIContainer, inputIStreamCoder.getChannels());

		outputIStreamCoder.setChannels(channels);

		ICodec iCodec = getAudioEncodingICodec(outputIContainer);

		if (iCodec == null) {
			iCodec = ICodec.guessEncodingCodec(
				null, null, outputURL, null, inputICodecType);
		}

		if (iCodec == null) {
			throw new RuntimeException(
				"Unable to determine " + inputICodecType + " encoder for " +
					outputURL);
		}

		outputIStreamCoder.setCodec(iCodec);

		outputIStreamCoder.setGlobalQuality(0);

		int sampleRate = AUDIO_SAMPLE_RATE_DEFAULT;

		if (inputIStreamCoder.getSampleRate() > 0) {
			sampleRate = inputIStreamCoder.getSampleRate();
		}

		outputIStreamCoder.setSampleRate(sampleRate);

		iAudioResamplers[index] = createIAudioResampler(
			inputIStreamCoder, outputIStreamCoder);

		inputIAudioSamples[index] = IAudioSamples.make(
			1024, inputIStreamCoder.getChannels());
		outputIAudioSamples[index] = IAudioSamples.make(
			1024, outputIStreamCoder.getChannels());
	}

	protected IAudioSamples resampleAudio(
			IAudioResampler iAudioResampler, IAudioSamples inputIAudioSample,
			IAudioSamples resampledIAudioSample)
		throws Exception {

		if ((iAudioResampler == null) ||
			(inputIAudioSample.getNumSamples() <= 0)) {

			return inputIAudioSample;
		}

		iAudioResampler.resample(
			resampledIAudioSample, inputIAudioSample,
			inputIAudioSample.getNumSamples());

		return resampledIAudioSample;
	}

	protected IVideoPicture resampleVideo(
			IVideoResampler iVideoResampler, IVideoPicture inputIVideoPicture,
			IVideoPicture resampledIVideoPicture)
		throws Exception {

		if (iVideoResampler == null) {
			return inputIVideoPicture;
		}

		if (iVideoResampler.resample(
				resampledIVideoPicture, inputIVideoPicture) < 0) {

			throw new RuntimeException("Unable to resample video");
		}

		return resampledIVideoPicture;
	}

	protected void rewind() throws Exception {
		IContainer inputIContainer = getInputIContainer();

		if (inputIContainer == null) {
			return;
		}

		int value = 0;

		for (int i = 0; i < inputIContainer.getNumStreams(); i++) {
			IStream inputIStream = inputIContainer.getStream(i);

			IStreamCoder inputIStreamCoder = inputIStream.getStreamCoder();

			if (inputIStreamCoder.getCodecType() !=
					ICodec.Type.CODEC_TYPE_VIDEO) {

				continue;
			}

			value = rewind(i);

			if (value < 0) {
				throw new RuntimeException("Error while seeking file");
			}

			break;
		}
	}

	protected int rewind(int index) throws Exception {
		IContainer inputIContainer = getInputIContainer();

		if (inputIContainer == null) {
			return -1;
		}

		int value = inputIContainer.seekKeyFrame(index, -1, 0);

		if (value < 0) {
			throw new RuntimeException("Error while seeking file");
		}

		return value;
	}

	protected int seek(int index, long timeStamp) throws Exception {
		IContainer inputIContainer = getInputIContainer();

		if (inputIContainer == null) {
			return -1;
		}

		int value = inputIContainer.seekKeyFrame(index, timeStamp, 0);

		if (value < 0) {
			throw new RuntimeException("Error while seeking file");
		}

		return value;
	}

	protected long seek(long timeStamp) throws Exception {
		IContainer inputIContainer = getInputIContainer();

		if (inputIContainer == null) {
			return -1;
		}

		int value = 0;

		for (int i = 0; i < inputIContainer.getNumStreams(); i++) {
			IStream inputIStream = inputIContainer.getStream(i);

			IStreamCoder inputIStreamCoder = inputIStream.getStreamCoder();

			if (inputIStreamCoder.getCodecType() !=
					ICodec.Type.CODEC_TYPE_VIDEO) {

				continue;
			}

			value = seek(i, timeStamp);

			if (value < 0) {
				throw new RuntimeException("Error while seeking file");
			}

			break;
		}

		return value;
	}

	protected void updateAudioTimeStamp(
		IAudioSamples inputAudioSample, long timeStampOffset) {

		if (inputAudioSample.getTimeStamp() != Global.NO_PTS) {
			inputAudioSample.setTimeStamp(
				inputAudioSample.getTimeStamp() - timeStampOffset);
		}
	}

	protected void updateVideoTimeStamp(
		IVideoPicture inputIVideoPicture, long timeStampOffset) {

		if (inputIVideoPicture.getTimeStamp() != Global.NO_PTS) {
			inputIVideoPicture.setTimeStamp(
				inputIVideoPicture.getTimeStamp() - timeStampOffset);
		}
	}

	protected static final int AUDIO_BIT_RATE_DEFAULT = 64000;

	protected static final int AUDIO_SAMPLE_RATE_DEFAULT = 44100;

	protected static final int DECODE_VIDEO_THUMBNAIL = 2;

	protected static final int VIDEO_BIT_RATE_DEFAULT = 250000;

	protected static final int VIDEO_BIT_RATE_MAX = 1500000;

	private static Log _log = LogFactoryUtil.getLog(LiferayConverter.class);

	private ConverterFactory.Type _converterFactoryType;
	private IConverter _videoIConverter;

}