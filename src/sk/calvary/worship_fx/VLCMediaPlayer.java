/*
 * Created on Nov 10, 2016
 */
package sk.calvary.worship_fx;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaPlayer.Status;
import uk.co.caprica.vlcj.component.DirectMediaPlayerComponent;
import uk.co.caprica.vlcj.player.direct.BufferFormat;
import uk.co.caprica.vlcj.player.direct.DirectMediaPlayer;
import uk.co.caprica.vlcj.player.direct.format.RV32BufferFormat;

public class VLCMediaPlayer {
	private final Object LOCK = new Object();

	Thread loaderThread;

	private DirectMediaPlayerComponent dmpc;

	private DirectMediaPlayer dmp;

	private boolean dispose = false;
	private boolean hasFrame = false;

	List<VLCMediaView> views = new ArrayList<>();

	int width;

	int height;

	private RV32BufferFormat bufferFormat;

	private WritablePixelFormat<ByteBuffer> pixelFormat;

	private AnimationTimer animationTimer;

	private final ObjectProperty<MediaPlayer.Status> status = new SimpleObjectProperty<>(
			this, "status", Status.UNKNOWN);

	public ObjectProperty<MediaPlayer.Status> statusProperty() {
		return status;
	}

	public Status getStatus() {
		return status.get();
	}

	public VLCMediaPlayer(File media) {
		animationTimer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				renderFrame();
			}
		};

		loaderThread = new Thread(() -> {
			pixelFormat = PixelFormat.getByteBgraInstance();
			dmpc = new DirectMediaPlayerComponent((w, h) -> {
				width = w;
				height = h;
				System.out.println(media + " -> " + w + " " + h);
				bufferFormat = new RV32BufferFormat(w, h);
				return bufferFormat;
			}) {
				@Override
				public void display(DirectMediaPlayer mediaPlayer,
						Memory[] nativeBuffers, BufferFormat bufferFormat) {
					hasFrame = true;
				}
			};
			dmp = dmpc.getMediaPlayer();
			dmp.playMedia(media.getAbsolutePath());
			dmp.setRepeat(true);

			Platform.runLater(() -> {
				animationTimer.start();
			});
			synchronized (LOCK) {
				while (!dispose)
					try {
						LOCK.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				dmpc.release();
			}
			loaderThread = null;
		});
		loaderThread.start();
	}

	void renderFrame() {
		if (!hasFrame)
			return;
		Memory[] nativeBuffers = dmp.lock();
		if (nativeBuffers != null) {
			// FIXME there may be more efficient ways to do this...
			// Since this is now being called by a specific rendering time,
			// independent of the native video callbacks being
			// invoked, some more defensive conditional checks are needed
			Memory nativeBuffer = nativeBuffers[0];
			if (nativeBuffer != null) {
				System.out.println(dmpc.getMediaPlayer().getTime());
				ByteBuffer byteBuffer = nativeBuffer.getByteBuffer(0,
						nativeBuffer.size());
				if (bufferFormat != null && bufferFormat.getWidth() > 0
						&& bufferFormat.getHeight() > 0) {
					if (status.get() == Status.UNKNOWN) {
						status.set(Status.READY);
					} else {
						status.set(Status.PLAYING);
					}
					views.forEach(mv -> {
						Canvas c = mv.canvas;
						if (c.getWidth() != width || c.getHeight() != height) {
							System.out.println(
									"MUU " + c.getWidth() + " -> " + width);
							c.setWidth(width);
							c.setHeight(height);
							mv.fitCanvas();
						}
						c.getGraphicsContext2D().getPixelWriter().setPixels(0,
								0, bufferFormat.getWidth(),
								bufferFormat.getHeight(), pixelFormat,
								byteBuffer, bufferFormat.getPitches()[0]);
					});
				}
			}
		}
		dmp.unlock();
		hasFrame = false;
	}

	public void dispose() {
		animationTimer.stop();
		status.set(Status.DISPOSED);
		synchronized (LOCK) {
			dispose = true;
			LOCK.notifyAll();
		}
	}

	public void setVolume(double volume) {
		if (dmp != null)
			dmp.setVolume((int) Math.round(100 * volume));
	}
}
