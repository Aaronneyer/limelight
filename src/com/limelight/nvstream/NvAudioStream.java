package com.limelight.nvstream;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.LinkedList;

import jlibrtp.Participant;
import jlibrtp.RTPSession;

import com.limelight.nvstream.av.AvByteBufferDescriptor;
import com.limelight.nvstream.av.AvByteBufferPool;
import com.limelight.nvstream.av.AvRtpOrderedQueue;
import com.limelight.nvstream.av.AvRtpPacket;
import com.limelight.nvstream.av.AvShortBufferDescriptor;
import com.limelight.nvstream.av.audio.AvAudioDepacketizer;
import com.limelight.nvstream.av.audio.OpusDecoder;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class NvAudioStream {
	public static final int RTP_PORT = 48000;
	public static final int RTCP_PORT = 47999;
	
	// Audio is RTP packet type 97
	private AvRtpOrderedQueue packets = new AvRtpOrderedQueue((byte)97);
	
	private AudioTrack track;
	
	private RTPSession session;
	private DatagramSocket rtp;
	
	private AvAudioDepacketizer depacketizer = new AvAudioDepacketizer();
	
	private LinkedList<Thread> threads = new LinkedList<Thread>();
	
	private AvByteBufferPool pool = new AvByteBufferPool(1500);
	
	private boolean aborting = false;
	
	public void abort()
	{
		if (aborting) {
			return;
		}
		
		aborting = true;
		
		for (Thread t : threads) {
			t.interrupt();
		}
		
		// Close the socket to interrupt the receive thread
		if (rtp != null) {
			rtp.close();
		}
		
		// Wait for threads to terminate
		for (Thread t : threads) {
			try {
				t.join();
			} catch (InterruptedException e) { }
		}
		

		if (session != null) {
			//session.endSession();
		}
		if (track != null) {
			track.release();
		}
		
		threads.clear();
	}
	
	public void startAudioStream(final String host)
	{		
		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					setupRtpSession(host);
				} catch (SocketException e) {
					e.printStackTrace();
					return;
				}
				
				setupAudio();
				
				startReceiveThread();
				
				startDepacketizerThread();
				
				startDecoderThread();
				
				startUdpPingThread();
			}
			
		}).start();
	}
	
	private void setupRtpSession(String host) throws SocketException
	{
		rtp = new DatagramSocket(RTP_PORT);
		
		rtp.setReceiveBufferSize(1024*512);
		
		session = new RTPSession(rtp, null);
		session.addParticipant(new Participant(host, RTP_PORT, 0));
	}
	
	public void trim()
	{
		depacketizer.trim();
	}
	
	private void setupAudio()
	{
		int channelConfig;
		int err;
		
		err = OpusDecoder.init();
		if (err == 0) {
			System.out.println("Opus decoder initialized");
		}
		else {
			System.err.println("Opus decoder init failed: "+err);
			return;
		}
		
		switch (OpusDecoder.getChannelCount())
		{
		case 1:
			channelConfig = AudioFormat.CHANNEL_OUT_MONO;
			break;
		case 2:
			channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
			break;
		default:
			System.err.println("Unsupported channel count");
			return;
		}

		track = new AudioTrack(AudioManager.STREAM_MUSIC,
				OpusDecoder.getSampleRate(),
				channelConfig,
				AudioFormat.ENCODING_PCM_16BIT,
				1024, // 1KB buffer
				AudioTrack.MODE_STREAM);
		
		track.play();
	}
	
	private void startDepacketizerThread()
	{
		// This thread lessens the work on the receive thread
		// so it can spend more time waiting for data
		Thread t = new Thread() {
			@Override
			public void run() {
				AvRtpPacket packet;
				
				while (!isInterrupted())
				{
					try {
						// Blocks for a maximum of 50ms
						packet = packets.removeNext(50);
					} catch (InterruptedException e) {
						abort();
						return;
					}
					
					depacketizer.decodeInputData(packet);
					
					if (packet != null) {
						pool.free(packet.getBackingBuffer());
					}
				}
			}
		};
		threads.add(t);
		t.start();
	}
	
	private void startDecoderThread()
	{
		// Decoder thread
		Thread t = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted())
				{
					AvShortBufferDescriptor samples;
					
					try {
						samples = depacketizer.getNextDecodedData();
					} catch (InterruptedException e) {
						abort();
						return;
					}
					
					track.write(samples.data, samples.offset, samples.length);
					
					depacketizer.releaseBuffer(samples);
				}
			}
		};
		threads.add(t);
		t.start();
	}
	
	private void startReceiveThread()
	{
		// Receive thread
		Thread t = new Thread() {
			@Override
			public void run() {
				DatagramPacket packet = new DatagramPacket(pool.allocate(), 1500);
				AvByteBufferDescriptor desc = new AvByteBufferDescriptor(null, 0, 0);
				
				while (!isInterrupted())
				{
					try {
						rtp.receive(packet);
					} catch (IOException e) {
						abort();
						return;
					}
					
					desc.length = packet.getLength();
					desc.offset = packet.getOffset();
					desc.data = packet.getData();
					
					// Give the packet to the depacketizer thread
					packets.addPacket(new AvRtpPacket(desc));
					
					// Get a new buffer from the buffer pool
					packet.setData(pool.allocate(), 0, 1500);
				}
			}
		};
		threads.add(t);
		t.start();
	}
	
	private void startUdpPingThread()
	{
		// Ping thread
		Thread t = new Thread() {
			@Override
			public void run() {
				// PING in ASCII
				final byte[] pingPacket = new byte[] {0x50, 0x49, 0x4E, 0x47};
				
				// RTP payload type is 127 (dynamic)
				session.payloadType(127);
				
				// Send PING every 100 ms
				while (!isInterrupted())
				{
					session.sendData(pingPacket);
					
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						abort();
						return;
					}
				}
			}
		};
		threads.add(t);
		t.start();
	}
}
