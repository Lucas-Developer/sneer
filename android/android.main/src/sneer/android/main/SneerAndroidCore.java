package sneer.android.main;

import static sneer.SneerAndroidClient.LABEL;
import static sneer.SneerAndroidClient.MESSAGE;
import static sneer.SneerAndroidClient.RESULT_RECEIVER;
import static sneer.android.main.SneerPluginInfo.InteractionType.MESSAGE_COMPOSE;
import static sneer.android.main.SneerPluginInfo.InteractionType.MESSAGE_VIEW;
import static sneer.android.main.SneerPluginInfo.InteractionType.SESSION_PARTNER;
import static sneer.android.main.TupleSpaceService.startTupleSpaceService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import sneer.ConversationMenuItem;
import sneer.Message;
import sneer.PublicKey;
import sneer.Sneer;
import sneer.admin.SneerAdmin;
import sneer.android.main.core.SneerSqliteDatabase;
import sneer.commons.SystemReport;
import sneer.commons.exceptions.FriendlyException;
import sneer.tuples.Tuple;
import sneer.utils.SharedResultReceiver;
import sneer.utils.Value;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

public class SneerAndroidCore implements SneerAndroid {
	
	Func1<List<SneerPluginInfo>, Observable<List<ConversationMenuItem>>> fromSneerPluginInfoList = new Func1<List<SneerPluginInfo>, Observable<List<ConversationMenuItem>>>() {  @Override public Observable<List<ConversationMenuItem>> call(List<SneerPluginInfo> apps) {
		return Observable.from(apps)
			.filter(new Func1<SneerPluginInfo, Boolean>() {  @Override public Boolean call(SneerPluginInfo t1) {
				return t1.canCompose();
			} })
			.map(new Func1<SneerPluginInfo, ConversationMenuItem>() { @Override public ConversationMenuItem call(final SneerPluginInfo app) {
				return new ConversationMenuItemImpl(app);
			} })
			.toList();
	} };

	private final class ConversationMenuItemImpl implements ConversationMenuItem {
		
		private final SneerPluginInfo app;

		private ConversationMenuItemImpl(SneerPluginInfo app) {
			this.app = app;
		}

		@Override
		public void call(PublicKey partyPuk) {
			startComposePlugin(app, partyPuk);
		}

		@Override
		public byte[] icon() {
			try {
				Drawable icon = resourceForPackage(app.packageName).getDrawable(app.menuIcon);
				return bitmapFor(icon);
			} catch (Exception e) {
				Log.w(SneerAndroidCore.class.getSimpleName(), "Error loading bitmap", e);
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public String caption() {
			return app.menuCaption;
		}
	}

	private SneerAdmin sneerAdmin;
	private Context context;
	private static String error;
	private static AlertDialog errorDialog;
	
	
	static private AtomicLong nextSessionId = new AtomicLong(0);
	
	private void startComposePlugin(SneerPluginInfo app, PublicKey peer) {
		if (app.interactionType == SESSION_PARTNER) startPartnerSession(app, peer);
		if (app.interactionType == MESSAGE_COMPOSE) startComposeMessage(app, peer);
	}

	private void startViewPlugin(SneerPluginInfo app, Tuple tuple) {
		if (app.interactionType == SESSION_PARTNER) resumePartnerSession(app, tuple);
		if (app.interactionType == MESSAGE_VIEW) startViewMessage(app, (String)tuple.get("label"), tuple.payload());
	}


	private void startComposeMessage(final SneerPluginInfo app, final PublicKey peer) {
		Intent intent = new Intent();
		intent.setClassName(app.packageName, app.activityName);
		
		final ClassLoader classLoader = context.getClassLoader();
		
		SharedResultReceiver result = new SharedResultReceiver(new SharedResultReceiver.Callback() {  @Override public void call(Bundle t1) {
			
			try {
				t1.setClassLoader(classLoader);
				Object message = ((Value)t1.getParcelable(MESSAGE)).get();
				String label = t1.getString(LABEL);
				info("Receiving message of type '" + app.tupleType + "' label '" + label + "' from " + app.packageName + "." + app.activityName);
				sneer().tupleSpace().publisher()
					.type(app.tupleType)
					.audience(peer)
					.field("label", label)
					.pub(message);
			} catch (final Throwable t) {
				toastOnMainThread("Error receiving message from plugin: " + t.getMessage(), Toast.LENGTH_LONG);
				Log.w(SneerAndroidCore.class.getSimpleName(), "Error receiving message from plugin", t);
			}			
		}});
		
		intent.putExtra(RESULT_RECEIVER, result);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	private void startViewMessage(final SneerPluginInfo app, String label, Object message) {
		Intent intent = new Intent();
		intent.setClassName(app.packageName, app.activityName);
		
		intent.putExtra(MESSAGE, Value.of(message));
		intent.putExtra(LABEL, label);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	private void startPartnerSession(final SneerPluginInfo app, final PublicKey partner) {
		final long sessionId = nextSessionId.getAndIncrement();
		
//		sneer().tupleSpace().publisher()
//			.audience(sneer().self().publicKey().current())
//			.type("sneer/session")
//			.field("session", sessionId)
//			.field("partyPuk", peer)
//			.field("sessionType", app.tupleType)
//			.field("lastMessageSeen", (long)0)
//			.pub();

		Intent intent = new Intent();
		intent.setClassName(app.packageName, app.activityName);
		intent.putExtra(RESULT_RECEIVER, createPatnerSession(app, partner, sessionId, sneer().self().publicKey().current()));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	private void resumePartnerSession(final SneerPluginInfo app, Tuple tuple) {
		long sessionId = (Long) tuple.get("session");
		PublicKey host = (PublicKey) tuple.get("host");
		PublicKey partner = tuple.author().equals(sneer().self().publicKey().current()) ? tuple.audience() : tuple.author(); 
		
		Intent intent = new Intent();
		intent.setClassName(app.packageName, app.activityName);
		intent.putExtra(RESULT_RECEIVER, createPatnerSession(app, partner, sessionId, host));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	protected SharedResultReceiver createPatnerSession(SneerPluginInfo app, PublicKey partner, long sessionId, PublicKey host) {
		return new SharedResultReceiver(new PartnerSession(app.tupleType, host, partner, sessionId, context.getClassLoader(), sneer()));
	}

	
	private static byte[] bitmapFor(Drawable icon) {
		Bitmap bitmap = ((BitmapDrawable)icon).getBitmap();
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
		return stream.toByteArray();
	}


	private Resources resourceForPackage(String packageName) throws NameNotFoundException {
		return context.getPackageManager().getResourcesForApplication(packageName);
	}


	private static void info(String msg) {
		Log.i(SneerAndroidCore.class.getSimpleName(), msg);
	}


	@Override
	public SneerAdmin admin() {
		return sneerAdmin;
	}

	@Override
	public boolean checkOnCreate(Activity activity) {
		if (error == null) return true;
		finishWith(error, activity);
		return false;
	}

	private static void finishWith(String message, final Activity activity) {
		if (errorDialog != null) {
			activity.finish();
			return;
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(activity);
		builder.setMessage(message).setCancelable(false).setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int id) {
			errorDialog.dismiss();
			errorDialog = null;
			activity.finish();
		}});
		errorDialog = builder.create();
		errorDialog.show();
	}

	private SneerAdmin initialize(Context context) throws FriendlyException {
	
		File adminDir = new File(context.getFilesDir(), "admin");
		adminDir.mkdirs();
		File secureFile = new File(adminDir, "tupleSpace.sqlite");
		try {
			SneerSqliteDatabase db = SneerSqliteDatabase.openDatabase(secureFile);
			SneerAdmin admin = newSneerAdmin(db);
			return admin;
		} catch (IOException e) {
			SystemReport.updateReport("Error starting Sneer", e);
			throw new FriendlyException("Error starting Sneer", e);
		}
	}

	private static SneerAdmin newSneerAdmin(SneerSqliteDatabase db) {
			try {
				return (SneerAdmin) sneerAdminFactory().getMethod("create", Object.class).invoke(null, db);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
	}

	@Override
	public Sneer sneer() {
		return admin().sneer();
	}

	private static Class<?> sneerAdminFactory() {
		try {
			return Class.forName("sneer.admin.SneerAdminFactory");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	public void toast(final String message, final int length) {
		Toast.makeText(context, message, length).show();
	}

	public void toastOnMainThread(final String message, final int length) {
		AndroidSchedulers.mainThread().createWorker().schedule(new Action0() { @Override public void call() {
			toast(message, length);
		} });
	}


	private static Map<String, SneerPluginInfo> tupleViewers = new HashMap<String, SneerPluginInfo>();
	
	public static boolean isCoreAvailable() {
		return sneerAdminFactory() != null;
	}

	public SneerAndroidCore(Context context) {
		this.context = context;
		SneerPluginInfo.initialDiscovery(context);
		
		try {
			sneerAdmin = initialize(context);
		} catch (FriendlyException e) {
			error = e.getMessage();
		}
		
		SneerPluginInfo.plugins()
			.flatMap(fromSneerPluginInfoList)
			.subscribe(new Action1<List<ConversationMenuItem>>() {  @Override public void call(List<ConversationMenuItem> menuItems) {
				sneer().setConversationMenuItems(menuItems);
			} });
		
		SneerPluginInfo.plugins()
			.flatMap(new Func1<List<SneerPluginInfo>, Observable<Map<String, SneerPluginInfo>>>() {  @Override public Observable<Map<String, SneerPluginInfo>> call(List<SneerPluginInfo> t1) {
				return Observable.from(t1)
						.filter(new Func1<SneerPluginInfo, Boolean>() {  @Override public Boolean call(SneerPluginInfo t1) {
							return t1.canView();
						} })
						.toMap(new Func1<SneerPluginInfo, String>() {  @Override public String call(SneerPluginInfo t1) {
							return t1.tupleType;
						}});
			} })
			.subscribe(new Action1<Map<String, SneerPluginInfo>>() {  @Override public void call(Map<String, SneerPluginInfo> t1) {
				tupleViewers = t1;
			} });
			
		
		startTupleSpaceService(context);
		
		sneer().tupleSpace().filter()
			.localTuples()
			.map(new Func1<Tuple, Long>() {  @Override public Long call(Tuple t1) {
				return (Long) t1.get("session");
			} })
			.scan(new Func2<Long, Long, Long>() {  @Override public Long call(Long t1, Long t2) {
				return Math.max(t1==null?0:t1, t2==null?0:t2);
			} })
			.last()
			.subscribe(new Action1<Long>() {  @Override public void call(Long max) {
				nextSessionId.set(max+1);
			} });
		
	}


	@Override
	public boolean isClickable(Message message) {
		return tupleViewers.containsKey(message.tuple().type());
	}


	@Override
	public void doOnClick(Message message) {
		Tuple tuple = message.tuple();
		SneerPluginInfo viewer = tupleViewers.get(tuple.type());
		if (viewer == null) {
			throw new RuntimeException("Can't find viewer plugin for message type '"+tuple.type()+"'");
		}
		startViewPlugin(viewer, tuple);
	}

}
