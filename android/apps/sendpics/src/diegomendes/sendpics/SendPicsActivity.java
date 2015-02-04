package diegomendes.sendpics;

import sneer.android.ui.MessageActivity;
import sneer.commons.exceptions.FriendlyException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;

import static android.widget.Toast.*;

public class SendPicsActivity extends MessageActivity {

	private static final int TAKE_PICTURE = 1;
	
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        composeMessage();
    }
    
    
    private void composeMessage() {
		Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
		Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
		galleryIntent.setType("image/*");
		
		Intent chooser = Intent.createChooser(galleryIntent, "Open with");
		chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
	
		startActivityForResult(chooser, TAKE_PICTURE);
	}


	@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent)  {
    	if (requestCode != TAKE_PICTURE || resultCode != RESULT_OK || intent == null) {
    		finish();
    		return;
    	}
		
        Bitmap bitmap;
		try {
			bitmap = loadBitmap(intent);
		} catch (FriendlyException e) {
			toast(e);
			return;
		}

		byte[] imageBytes = scaledDownTo(bitmap, 40 * 1024);
		send("pic", null, imageBytes);
		finish();
    }

    private void toast(FriendlyException e) {
        Toast.makeText(this, e.getMessage(), LENGTH_LONG).show();
    }


    protected Bitmap loadBitmap(Intent intent) throws FriendlyException {
        final Bundle extras = intent.getExtras();
        if (extras != null && extras.get("data") != null)
            return (Bitmap)extras.get("data");

        Uri uri = intent.getData();
        if (uri == null)
            throw new FriendlyException("No image found.");

        try {
            return BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
        } catch (FileNotFoundException e) {
            throw new FriendlyException("Unable to load image: " + uri);
        }
    }


    public static byte[] scaledDownTo(Bitmap original, int maximumLength) {
        int side = Math.min(original.getHeight(), original.getWidth());
        Bitmap reduced = original;
        while (true) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reduced.compress(Bitmap.CompressFormat.JPEG, 100, out);
            final byte[] bytes = out.toByteArray();
            if (bytes.length <= maximumLength)
                return bytes;
            side = (int) (side * 0.9f);
            reduced = ThumbnailUtils.extractThumbnail(original, side, side);
        }
    }

}
