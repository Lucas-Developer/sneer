package sneer.android.main;

import static sneer.SneerAndroid.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import rx.Observable;
import rx.android.schedulers.*;
import rx.functions.*;
import sneer.*;
import sneer.admin.*;
import sneer.android.main.core.*;
import sneer.commons.*;
import sneer.commons.exceptions.*;
import sneer.impl.simulator.*;
import sneer.utils.*;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.util.*;
import android.widget.*;

public class SneerApp extends Application {
	
	Func1<List<SneerAppInfo>, Observable<List<ConversationMenuItem>>> fromSneerAppInfoList = new Func1<List<SneerAppInfo>, Observable<List<ConversationMenuItem>>>() {  @Override public Observable<List<ConversationMenuItem>> call(List<SneerAppInfo> apps) {
		return Observable.from(apps)
			.map(new Func1<SneerAppInfo, ConversationMenuItem>() { @Override public ConversationMenuItem call(final SneerAppInfo app) {
				return new ConversationMenuItemImpl(app);
			} })
			.toList();
	} };

	private final class ConversationMenuItemImpl implements ConversationMenuItem {
		
		private final SneerAppInfo app;

		private ConversationMenuItemImpl(SneerAppInfo app) {
			this.app = app;
		}

		@Override
		public void call(PublicKey partyPuk) {
			startPlugin(app, partyPuk);
		}

		@Override
		public byte[] icon() {								
			try {
				Drawable icon = resourceForPackage(app.packageName).getDrawable(app.menuIcon);
				return bitmapFor(icon);
			} catch (Exception e) {
				Log.w(SneerApp.class.getSimpleName(), "Error loading bitmap", e);
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public String caption() {
			return app.menuCaption;
		}
	}


	private static AlertDialog errorDialog;

	private static SneerAdmin ADMIN;

	private static Context context;

	private static String error;
	
	@Override
	public void onCreate() {

		context = getApplicationContext();

		SneerAppInfo.initialDiscovery(context);
		
		try {
			initialize();
		} catch (FriendlyException e) {
			error = e.getMessage();
		}
		
		SneerAppInfo.apps()
			.flatMap(fromSneerAppInfoList)
			.subscribe(new Action1<List<ConversationMenuItem>>() {  @Override public void call(List<ConversationMenuItem> menuItems) {
				sneer().setConversationMenuItems(menuItems);
			} });
		
		TupleSpaceService.startTupleSpaceService(context);
		
		super.onCreate();
	}
	
	private AtomicLong nextSessionId = new AtomicLong(0);
	
	private void startPlugin(SneerAppInfo app, PublicKey peer) {
		
		switch (app.interactionType) {
		case SESSION:
			startSession(app, peer);
			break;

		case MESSAGE:
			startMessage(app, peer);
			break;

		}
		
	}


	private void startMessage(final SneerAppInfo app, final PublicKey peer) {
		Intent intent = new Intent();
		intent.setClassName(app.packageName, app.activityName);
		
		final ClassLoader classLoader = getClassLoader();
		
		SharedResultReceiver result = new SharedResultReceiver(new Action1<Bundle>() {  @Override public void call(Bundle t1) {
			
			try {
				t1.setClassLoader(classLoader);
				Object[] ret = (Object[]) ((Value)t1.getParcelable("value")).get();
				info("Receiving " + ret.length + " messages type '"+app.tupleType+"' from " + app.packageName + "." + app.activityName);
				for (Object payload : ret) {
					sneer().tupleSpace().publisher()
						.type(app.tupleType)
						.audience(peer)
						.pub(payload);
				}
			} catch (final Throwable t) {
				toastOnMainThread("Error receiving message from plugin: " + t.getMessage(), Toast.LENGTH_LONG);
				Log.w(SneerApp.class.getSimpleName(), "Error receiving message from plugin", t);
			}
			
		}});
		
		intent.putExtra(RESULT_RECEIVER, result);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
	}

	public static void toastOnMainThread(final String message, final int length) {
		AndroidSchedulers.mainThread().createWorker().schedule(new Action0() { @Override public void call() {
			Toast.makeText(context, message, length).show();
		} });
	}

	private void startSession(SneerAppInfo app, PublicKey peer) {
		long sessionId = nextSessionId.getAndIncrement();
		
		sneer().tupleSpace().publisher()
			.audience(sneer().self().publicKey().current())
			.type("sneer/session")
			.field("session", sessionId)
			.field("partyPuk", peer)
			.field("sessionType", app.tupleType)
			.field("lastMessageSeen", (long)0)
			.pub();

		Intent intent = new Intent();
		intent.setClassName(app.packageName, app.activityName);
		intent.putExtra(SESSION_ID, sessionId);
		intent.putExtra(OWN_PRIK, admin().privateKey());
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
	}

	
	private byte[] bitmapFor(Drawable icon) {
		Bitmap bitmap = ((BitmapDrawable)icon).getBitmap();
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
		return stream.toByteArray();
	}


	private Resources resourceForPackage(String packageName) throws NameNotFoundException {
		return getPackageManager().getResourcesForApplication(packageName);
	}


	private void info(String msg) {
		Log.i(SneerApp.class.getSimpleName(), msg);
	}


	public static Sneer sneer() {
		return admin().sneer();
	}
	
	
	public static SneerAdmin admin() {
		if (ADMIN == null) throw new IllegalStateException("You must call the initialize method before you call this method.");
		return ADMIN;
	}

	
	public static void initialize() throws FriendlyException {
		if (ADMIN != null)
			throw new IllegalStateException("Sneer is being initialized more than once.");

		ADMIN = isCoreAvailable()
			? initialize(context)
			: simulator();
	}

	private static boolean isCoreAvailable() {
		return sneerAdminFactory() != null;
	}

	private static Class<?> sneerAdminFactory() {
		try {
			return Class.forName("sneer.admin.SneerAdminFactory");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	
	private static SneerAdmin simulator() {
		SneerAdminSimulator ret = new SneerAdminSimulator();
		setOwnName(ret.sneer(), "Neide da Silva"); //Comment this line to get an empty name.
		return ret;
	}


	private static void setOwnName(Sneer sneer, String name) {
		sneer.profileFor(sneer.self()).setOwnName(name);
	}

	private static SneerAdmin initialize(Context context) throws FriendlyException {

		File adminDir = new File(context.getFilesDir(), "admin");
		adminDir.mkdirs();
		File secureFile = new File(adminDir, "tupleSpace.sqlite");
		// secureFile.delete();
		try {
			SneerSqliteDatabase db = SneerSqliteDatabase.openDatabase(secureFile);
			SneerAdmin admin = newSneerAdmin(db);
			// createBot("bot", admin.sneer());
			return admin;
		} catch (IOException e) {
			SystemReport.updateReport("Error starting Sneer", e);
			throw new FriendlyException("Error starting Sneer", e);
		}
	}

	private static SneerAdmin newSneerAdmin(SneerSqliteDatabase db) {
		try {
			return (SneerAdmin) sneerAdminFactory().getMethod("create", Object.class).invoke(null, db);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

/*	private static void createBot(final String baseName, final Sneer masterSneer) {
		Observable.range(1, 10).delay(1, TimeUnit.SECONDS)
				.observeOn(Schedulers.newThread())
				.subscribe(new Action1<Integer>() {
					@Override
					public void call(Integer id) {
						try {

							String name = baseName + ":" + id;

							File dbFile = File.createTempFile(name, ".sqlite");
							dbFile.deleteOnExit();
							SneerAdmin botAdmin = newSneerAdmin(SneerSqliteDatabase.openDatabase(dbFile));

							masterSneer.addContact(name, masterSneer.produceParty(botAdmin.privateKey().publicKey()));
							botAdmin.sneer().addContact("master", botAdmin.sneer().produceParty(masterSneer.self().publicKey().current()));

						} catch (IOException e) {
							e.printStackTrace();
						} catch (FriendlyException e) {
							e.printStackTrace();
						}
					}
				});
	}
*/

	private static void finishWith(String message, final Activity activity) {
		if (errorDialog != null) {
			activity.finish();
			return;
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setMessage(message).setCancelable(false).setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int id) {
			errorDialog.dismiss();
			errorDialog = null;
			activity.finish();
		}});
		errorDialog = builder.create();
		errorDialog.show();
	}


	public static boolean checkOnCreate(Activity activity) {
		if (error == null) return true;
		finishWith(error, activity);
		return false;
	}

}
