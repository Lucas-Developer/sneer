package sneer.android.main;

import sneer.*;
import sneer.admin.*;
import sneer.impl.keys.*;
import sneer.impl.simulator.*;

public class SneerSingleton {
	
	private static SneerAdmin ADMIN = null;
	
	/*
	dir = new File(context.getFilesDir(), "admin");
	SneerAdmin admin = SneerAdminImpl(dir);
	 */

	public static Sneer sneer() {
		return admin().sneer();
	}
	
	public static SneerAdmin admin() {
		if (ADMIN != null) return ADMIN;

		synchronized (SneerSingleton.class) {
			if (ADMIN == null) {
//				ADMIN = new TupleSpaceSneerAdmin(new TuplesFactoryInProcess().newTupleSpace(Keys.createPrivateKey()));
				ADMIN = new SneerAdminSimulator();
				ADMIN.initialize(Keys.createPrivateKey());
			}
			return ADMIN;
		}
	}

}
