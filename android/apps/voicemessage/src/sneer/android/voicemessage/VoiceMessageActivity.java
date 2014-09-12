package sneer.android.voicemessage;

import java.io.*;

import sneer.android.ui.*;
import sneer.commons.exceptions.*;
import android.media.*;
import android.os.*;
import android.widget.*;

public class VoiceMessageActivity extends MessageActivity {

	static final String LOG_TAG = "----> Sneer VoiceMessage";
	
	private final String audioFileName = new File(System.getProperty("java.io.tmpdir"), "voicemessage.3gp").getAbsolutePath();

	private volatile MediaRecorder recorder = null;
	
	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);
		
		if (message() != null) {
			open(message());
		} else {
			composeMessage();
		}
	}


	private void composeMessage() {
//		setContentView(R.layout.activity_voice_message);
//		
//		button(R.id.btnSend).setOnClickListener(new OnClickListener() { @Override public void onClick(View v) {
//			send();
//		}});
//		
//		button(R.id.btnCancel).setOnClickListener(new OnClickListener() { @Override public void onClick(View v) {
//			finish();
//		}});
//		
//		startRecording();
		
		// Temporary solution
		navigateTo(OpenVoiceMessageActivity.class);
	}


	private void open(Object message) {
		// Deprecated - Use OpenVoiceMessageActivity.	
	}


	@Override
	public void onPause() {
		super.onPause();
		finish();	
	}


	@Override
	protected void onStop() {
		super.onStop();
		finish();
	}


	@Override
	protected void onDestroy() {
		stopRecording();
//		new File(audioFileName).delete();
		super.onDestroy();
	}


	private void startRecording() {
		startTimer();
		
		try {
			initRecorder();
		} catch (IOException e) {
			toast("Voice recorder failed", Toast.LENGTH_LONG);
			finish();
			return;
		}
	}


	private void startTimer() {
		new Thread() { @Override public void run() {
			final long t0 = now();
			while (recorder != null) {
				updateRecordingTimeSince(t0);
				sleepOneSecond();
			}
		}}.start();
	}
	
	
	private void initRecorder() throws IOException {
		recorder = new MediaRecorder();
		recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
		recorder.setOutputFile(audioFileName);
		recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
		recorder.prepare();
		recorder.start();
	}
	

	private void send() {
		stopRecording();
		
		byte[] bytes = null;
		try {
			bytes = recordingBytes();
		} catch (FriendlyException e) {
			toast(e);
			finish();
		}
		if (bytes != null)
			send(bytes);
	}


	private byte[] recordingBytes() throws FriendlyException {
		try {
			return readFully(new FileInputStream(audioFileName));
		} catch (IOException e) {
			throw new FriendlyException("Problem with recording");
		}
	}


	private void stopRecording() {
		if (recorder == null) return;
		try {
			recorder.stop();
		} catch (RuntimeException e) {
			// This can happen if stop() is called immediately after start() but this doesn't affect us.
		}
		recorder.release();
		recorder = null;
	}
	

	private void updateRecordingTimeSince(final long t0) {
		runOnUiThread(new Runnable() { @Override public void run() {
			long seconds = (now() - t0) / 1000;
			long minutes = seconds / 60;
			textView(R.id.recordingTime).setText(String.format("%02d : %02d", minutes, seconds % 60));
		}});
	}


	static private void sleepOneSecond() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}


	static private byte[] readFully(InputStream in) throws IOException {
		byte[] b = new byte[8192];
		int read;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		while ((read = in.read(b)) != -1) {
			out.write(b, 0, read);
		}
		return out.toByteArray();
	}


	static private long now() {
		return System.currentTimeMillis();
	}
	
}